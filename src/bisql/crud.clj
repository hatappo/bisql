(ns bisql.crud
  (:require [bisql.define :as define]
            [bisql.query :as query]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def ^:private metadata-query-options
  {:builder-fn rs/as-unqualified-lower-maps})

(defn- kebab-name
  [s]
  (str/replace s "_" "-"))

(defn- joined-name
  [columns]
  (str/join "-and-" (map kebab-name columns)))

(defn- sample-token
  [{:keys [column_name data_type]}]
  (cond
    (#{"smallint" "integer" "bigint" "numeric" "real" "double precision"} data_type) "1"
    (#{"date"} data_type) "CURRENT_DATE"
    (#{"timestamp without time zone" "timestamp with time zone"} data_type) "CURRENT_TIMESTAMP"
    (str/includes? column_name "email") "'user@example.com'"
    (or (str/includes? column_name "name")
        (str/includes? column_name "status")
        (str/includes? column_name "state")) "'sample'"
    :else "'sample'"))

(defn- bind-comment
  [column]
  (str "/*$" (kebab-name (:column_name column)) "*/" (sample-token column)))

(defn- where-clause
  [columns]
  (when (seq columns)
    (str/join "\n"
              (map-indexed
               (fn [idx column]
                 (str (if (zero? idx) "WHERE " "  AND ")
                      (:column_name column)
                      " = "
                      (bind-comment column)))
               columns))))

(defn- order-by-clause
  [columns]
  (when (seq columns)
    (str "ORDER BY " (str/join ", " (map :column_name columns)))))

(defn- list-limit-clause
  []
  (str/join "\n"
            ["LIMIT /*$limit*/100"
             "OFFSET /*$offset*/0"]))

(defn- select-template
  [table predicate-columns order-columns]
  (str/join "\n"
            (remove nil?
                    [(str "SELECT * FROM " table)
                     (where-clause predicate-columns)
                     (order-by-clause order-columns)
                     (list-limit-clause)])))

(defn- get-template
  [table predicate-columns]
  (str/join "\n"
            [(str "SELECT * FROM " table)
             (where-clause predicate-columns)]))

(defn- normalize-column-names
  [column-names]
  (vec
   (cond
     (instance? java.sql.Array column-names)
     (seq (.getArray ^java.sql.Array column-names))

     (sequential? column-names)
     column-names

     :else
     [])))

(defn- crud-query-name
  [name]
  (str "crud." name))

(defn- template-entry
  [_schema table kind columns sql-template & {:as extra}]
  (let [name (crud-query-name (str (name kind) "-by-" (joined-name columns)))]
    (merge
     {:table table
      :kind kind
      :name name
      :columns columns
      :query-name name
      :sql-template sql-template}
     extra)))

(defn- named-template-entry
  [_schema table kind name columns sql-template & {:as extra}]
  (let [name (crud-query-name name)]
    (merge
     {:table table
      :kind kind
      :name name
      :columns columns
      :query-name name
      :sql-template sql-template}
     extra)))

(defn- table-file-path
  [dialect schema table]
  (str dialect "/" schema "/" table "/crud.sql"))

(defn- normalize-path
  [path]
  (some-> path
          (str/replace "\\" "/")
          (str/replace #"^\./" "")
          (str/replace #"/+$" "")))

(defn- deps-classpath-roots
  []
  (let [deps-edn (-> "deps.edn" io/file slurp edn/read-string)]
    (mapv normalize-path (:paths deps-edn))))

(defn- relative-to-classpath-root
  [output-root]
  (let [normalized-root (normalize-path output-root)
        matching-root (->> (deps-classpath-roots)
                           (filter #(or (= normalized-root %)
                                        (str/starts-with? normalized-root (str % "/"))))
                           (sort-by count >)
                           first)]
    (when matching-root
      (let [prefix (str matching-root "/")]
        (if (= normalized-root matching-root)
          ""
          (subs normalized-root (count prefix)))))))

(defn- emit-string-literal
  [s]
  (str "\""
       (str/escape s {\\ "\\\\"
                      \" "\\\""})
       "\""))

(declare emit-literal)

(defn- emit-map-literal
  [m indent]
  (if (empty? m)
    "{}"
    (let [padding (apply str (repeat indent " "))
          child-padding (apply str (repeat (+ indent 2) " "))
          entries (map (fn [[k v]]
                         (str child-padding
                              (pr-str k)
                              " "
                              (emit-literal v (+ indent 2))))
                       m)]
      (str "{\n"
           (str/join "\n" entries)
           "\n"
           padding
           "}"))))

(defn- emit-metadata-literal
  [m indent]
  (if (empty? m)
    "^{}"
    (let [child-padding (apply str (repeat (+ indent 2) " "))
          [[first-k first-v] & rest-entries] (seq m)
          first-entry (str "^{"
                           (pr-str first-k)
                           " "
                           (emit-literal first-v (+ indent 1)))
          remaining (map (fn [[k v]]
                           (str child-padding
                                (pr-str k)
                                " "
                                (emit-literal v (+ indent 1))))
                         rest-entries)
          entries (cons first-entry remaining)]
      (str (str/join "\n" entries)
           "}"))))

(defn- emit-literal
  [value indent]
  (cond
    (string? value) (emit-string-literal value)
    (map? value) (emit-map-literal value indent)
    :else (pr-str value)))

(defn- namespace-root-path
  [output-root]
  (or (not-empty (relative-to-classpath-root output-root))
      (normalize-path (.getName (io/file output-root)))))

(defn- query-namespace-segments
  [root-path query-path]
  (->> (str/split (str root-path "/" query-path) #"/")
       (mapv kebab-name)))

(defn- namespace-file-path
  [query-path]
  (str query-path ".clj"))

(defn- namespace-symbol
  [root-path query-path]
  (symbol (str/join "." (query-namespace-segments root-path query-path))))

(defn- query-block
  [{:keys [name meta sql-template]}]
  (let [declarations (concat [[:name name]]
                             (sort-by key (dissoc (or meta {}) :name)))
        declaration-lines (map (fn [[k v]]
                                 (str "/*:" (clojure.core/name k) " "
                                      (if (= k :name)
                                        v
                                        (pr-str v))
                                      " */"))
                               declarations)]
    (str (str/join "\n" declaration-lines)
         "\n"
         sql-template)))

(defn- line-count
  [s]
  (if (str/blank? s)
    0
    (inc (count (filter #(= % \newline) s)))))

(defn- table-query-entries
  [templates]
  (loop [remaining templates
         current-line 1
         entries []]
    (if-let [template (first remaining)]
      (let [block-content (query-block template)
            block-lines (line-count block-content)
            next-line (+ current-line block-lines (if (next remaining) 1 0))]
        (recur (next remaining)
               next-line
               (conj entries (assoc template :source-line current-line))))
      entries)))

(defn- table-file-entry
  [dialect schema table templates]
  (let [query-entries (table-query-entries templates)]
    {:table table
     :path (table-file-path dialect schema table)
     :templates query-entries
     :content (str/join "\n\n" (map query-block query-entries))}))

(def ^:private crud-kind-order
  {:insert 0
   :get 1
   :list 2
   :update 3
   :delete 4})

(defn- sort-templates-for-file
  [templates]
  (sort-by (juxt #(get crud-kind-order (:kind %) Long/MAX_VALUE)
                 :name)
           templates))

(defn render-crud-files
  "Groups generated CRUD templates into one SQL file per table."
  [{:keys [dialect schema templates]}]
  {:dialect dialect
   :schema schema
   :files (->> templates
               (group-by :table)
               (sort-by key)
               (mapv (fn [[table table-templates]]
                       (table-file-entry dialect
                                         schema
                                         table
                                         (sort-templates-for-file table-templates)))))})

(defn write-crud-files!
  "Writes generated CRUD templates as one SQL file per table."
  [crud-result {:keys [output-root]
                :or {output-root "src/sql"}}]
  (let [file-result (render-crud-files crud-result)]
    (doseq [{:keys [path content]} (:files file-result)]
      (let [output-file (io/file output-root path)]
        (io/make-parents output-file)
        (spit output-file content)))
    file-result))

(defn render-crud-query-namespaces
  "Renders one query namespace file per table.
   Each generated namespace declares the generated query vars with docstrings."
  ([crud-result]
   (render-crud-query-namespaces crud-result {:output-root "src/sql"}))
  ([{:keys [dialect schema templates]} {:keys [output-root suppress-unused-public-var?]
                                        :or {output-root "src/sql"
                                             suppress-unused-public-var? false}}]
   (let [root-path (namespace-root-path output-root)
         files (->> (render-crud-files {:dialect dialect
                                        :schema schema
                                        :templates templates})
                    :files
                    (mapv (fn [{:keys [table path templates]}]
                            (let [query-path (subs path 0 (- (count path) 4))
                                  ns-sym (namespace-symbol root-path query-path)
                                  file-path (namespace-file-path query-path)
                                  project-relative-path (str (normalize-path output-root) "/" path)
                                  declare-forms (->> templates
                                                     (mapv (fn [template]
                                                             (let [{:keys [function-name namespace-suffix]}
                                                                   (query/query-location (or (:query-name template)
                                                                                             (:name template)))
                                                                   template-data (assoc template
                                                                                        :query-name (or (:query-name template)
                                                                                                        (:name template))
                                                                                        :function-name function-name
                                                                                        :namespace-suffix namespace-suffix
                                                                                        :project-relative-path project-relative-path)
                                                                   metadata (define/navigation-stub-metadata template-data
                                                                                                             '([datasource] [datasource template-params]))]
                                                               (str (when suppress-unused-public-var?
                                                                      "#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}\n")
                                                                    "(declare "
                                                                    (emit-metadata-literal metadata 9) "\n"
                                                                    "\n"
                                                                    " " function-name ")\n"))))
                                                     (str/join "\n"))
                                  content (str "(ns " ns-sym ")\n\n"
                                               declare-forms)]
                              {:table table
                               :path file-path
                               :namespace ns-sym
                               :query-path query-path
                               :content content}))))]
     {:dialect dialect
      :schema schema
      :files files})))

(defn write-crud-query-namespaces!
  "Writes generated query namespace files, one per table."
  [crud-result {:keys [output-root suppress-unused-public-var?]
                :or {output-root "src/sql"
                     suppress-unused-public-var? false}}]
  (let [rendered (render-crud-query-namespaces crud-result {:output-root output-root
                                                            :suppress-unused-public-var? suppress-unused-public-var?})]
    (doseq [{:keys [path content]} (:files rendered)]
      (let [output-file (io/file output-root path)]
        (io/make-parents output-file)
        (spit output-file content)))
    rendered))

(defn- generate-get-templates
  [schema table columns-by-name unique-column-groups]
  (mapv
   (fn [column-names]
     (let [columns (mapv columns-by-name column-names)]
       (template-entry schema
                       table
                       :get
                       column-names
                       (get-template table columns)
                       :meta {:cardinality :one})))
   unique-column-groups))

(defn- insertable-column?
  [column]
  (not= "YES" (:is_identity column)))

(defn- updatable-column?
  [predicate-column-names column]
  (and (insertable-column? column)
       (not (contains? (set predicate-column-names) (:column_name column)))))

(defn- insert-template
  [table columns]
  (str/join
   "\n"
   [(str "INSERT INTO " table " (")
    (str/join "\n" (map-indexed (fn [idx column]
                                  (str "  "
                                       (:column_name column)
                                       (when (< idx (dec (count columns))) ",")))
                                columns))
    ")"
    "VALUES ("
    (str/join "\n" (map-indexed (fn [idx column]
                                  (str "  "
                                       (bind-comment column)
                                       (when (< idx (dec (count columns))) ",")))
                                columns))
    ")"
    "RETURNING *"]))

(defn- insert-many-template
  [table columns]
  (str/join
   "\n"
   [(str "INSERT INTO " table " (")
    (str/join "\n" (map-indexed (fn [idx column]
                                  (str "  "
                                       (:column_name column)
                                       (when (< idx (dec (count columns))) ",")))
                                columns))
    ")"
    "VALUES"
    "/*%for row in rows */"
    "("
    (str/join "\n" (map-indexed (fn [idx column]
                                  (str "  "
                                       "/*$row." (kebab-name (:column_name column)) "*/"
                                       (sample-token column)
                                       (when (< idx (dec (count columns))) ",")))
                                columns))
    "),"
    "/*%end */"
    "RETURNING *"]))

(defn- delete-template
  [table predicate-columns]
  (str/join "\n"
            [(str "DELETE FROM " table)
             (where-clause predicate-columns)
             "RETURNING *"]))

(defn- update-bind-comment
  [column]
  (bind-comment column))

(defn- set-clause
  [columns]
  (str/join "\n"
            (map-indexed
             (fn [idx column]
               (str (if (zero? idx) "SET " "  , ")
                    (:column_name column)
                    " = "
                    (update-bind-comment column)))
             columns)))

(defn- update-template
  [table predicate-columns set-columns]
  (str/join "\n"
            [(str "UPDATE " table)
             (set-clause set-columns)
             (where-clause predicate-columns)
             "RETURNING *"]))

(defn- generate-insert-templates
  [schema table columns]
  (let [insert-columns (filterv insertable-column? columns)]
    (when (seq insert-columns)
      [(named-template-entry schema
                             table
                             :insert
                             "insert"
                             (mapv :column_name insert-columns)
                             (insert-template table insert-columns)
                             :meta {:cardinality :one})
       (named-template-entry schema
                             table
                             :insert
                             "insert-many"
                             (mapv :column_name insert-columns)
                             (insert-many-template table insert-columns)
                             :meta {:cardinality :many})])))

(defn- generate-delete-templates
  [schema table columns-by-name unique-column-groups]
  (mapv
   (fn [column-names]
     (let [columns (mapv columns-by-name column-names)]
       (template-entry schema
                       table
                       :delete
                       column-names
                       (delete-template table columns)
                       :meta {:cardinality :one})))
   unique-column-groups))

(defn- generate-update-templates
  [schema table columns columns-by-name unique-column-groups]
  (->> unique-column-groups
       (map (fn [predicate-column-names]
              (let [predicate-columns (mapv columns-by-name predicate-column-names)
                    set-columns (filterv (partial updatable-column? predicate-column-names)
                                         columns)]
                (when (seq set-columns)
                  (template-entry schema
                                  table
                                  :update
                                  predicate-column-names
                                  (update-template table predicate-columns set-columns)
                                  :meta {:cardinality :one}
                                  :set-columns (mapv :column_name set-columns))))))
       (remove nil?)
       vec))

(defn- generate-list-templates
  [schema table columns-by-name unique-column-groups index-column-groups]
  (letfn [(list-template-candidates [column-groups strict-prefix?]
            (->> column-groups
                 (mapcat
                  (fn [column-group]
                    (let [column-group (normalize-column-names column-group)
                          prefix-lengths (if strict-prefix?
                                           (range 0 (count column-group))
                                           (range 0 (inc (count column-group))))]
                      (map (fn [prefix-length]
                             {:prefix-column-names (subvec column-group 0 prefix-length)
                              :order-column-names (subvec column-group prefix-length)})
                           prefix-lengths))))
                 (reduce (fn [entries {:keys [prefix-column-names order-column-names] :as entry}]
                           (assoc entries [prefix-column-names order-column-names] entry))
                         {})
                 vals))
          (list-template-base-name [prefix-column-names]
            (if (seq prefix-column-names)
              (str "list-by-" (joined-name prefix-column-names))
              "list"))
          (list-template-name [{:keys [prefix-column-names order-column-names]} colliding-base-names]
            (let [base-name (list-template-base-name prefix-column-names)]
              (if (and (contains? colliding-base-names base-name)
                       (seq order-column-names))
                (str base-name "-order-by-" (joined-name order-column-names))
                base-name)))]
    (let [candidates (concat (list-template-candidates unique-column-groups true)
                             (list-template-candidates index-column-groups false))
          colliding-base-names (->> candidates
                                    (group-by #(list-template-base-name (:prefix-column-names %)))
                                    (keep (fn [[base-name entries]]
                                            (when (> (count entries) 1)
                                              base-name)))
                                    set)]
      (mapv (fn [{:keys [prefix-column-names order-column-names] :as candidate}]
              (let [predicate-columns (mapv columns-by-name prefix-column-names)
                    order-columns (mapv columns-by-name order-column-names)]
                (named-template-entry schema
                                      table
                                      :list
                                      (list-template-name candidate colliding-base-names)
                                      prefix-column-names
                                      (select-template table predicate-columns order-columns)
                                      :meta {:cardinality :many})))
            candidates))))

(defn- table-columns-query
  [schema]
  ["SELECT table_name,
           column_name,
           data_type,
           is_identity,
           ordinal_position
      FROM information_schema.columns
     WHERE table_schema = ?
     ORDER BY table_name, ordinal_position"
   schema])

(defn- unique-constraints-query
  [schema]
  ["SELECT tc.table_name,
           tc.constraint_type,
           tc.constraint_name,
           kcu.column_name,
           kcu.ordinal_position
      FROM information_schema.table_constraints tc
      JOIN information_schema.key_column_usage kcu
        ON tc.constraint_schema = kcu.constraint_schema
       AND tc.constraint_name = kcu.constraint_name
       AND tc.table_name = kcu.table_name
     WHERE tc.table_schema = ?
       AND tc.constraint_type IN ('PRIMARY KEY', 'UNIQUE')
     ORDER BY tc.table_name, tc.constraint_name, kcu.ordinal_position"
   schema])

(defn- indexes-query
  [schema]
  ["SELECT t.relname AS table_name,
           i.relname AS index_name,
           ix.indisunique AS is_unique,
           ix.indisprimary AS is_primary,
           ix.indpred IS NOT NULL AS is_partial,
           ARRAY_AGG(a.attname ORDER BY ord.n) AS column_names,
           BOOL_OR(a.attname IS NULL) AS has_expression
      FROM pg_class t
      JOIN pg_namespace ns
        ON ns.oid = t.relnamespace
      JOIN pg_index ix
        ON ix.indrelid = t.oid
      JOIN pg_class i
        ON i.oid = ix.indexrelid
      JOIN LATERAL UNNEST(ix.indkey) WITH ORDINALITY AS ord(attnum, n)
        ON TRUE
      LEFT JOIN pg_attribute a
        ON a.attrelid = t.oid
       AND a.attnum = ord.attnum
     WHERE ns.nspname = ?
       AND t.relkind = 'r'
     GROUP BY t.relname, i.relname, ix.indisunique, ix.indisprimary, ix.indpred
     ORDER BY t.relname, i.relname"
   schema])

(defn- group-constraint-columns
  [rows]
  (->> rows
       (group-by (juxt :table_name :constraint_name))
       (vals)
       (mapv (fn [constraint-rows]
               {:table_name (:table_name (first constraint-rows))
                :constraint_type (:constraint_type (first constraint-rows))
                :column_names (mapv :column_name constraint-rows)}))))

(defn- load-schema-metadata
  [datasource schema]
  (let [columns (jdbc/execute! datasource (table-columns-query schema) metadata-query-options)
        constraints (jdbc/execute! datasource (unique-constraints-query schema) metadata-query-options)
        indexes (jdbc/execute! datasource (indexes-query schema) metadata-query-options)]
    {:columns-by-table (->> columns
                            (group-by :table_name)
                            (into {} (map (fn [[table rows]]
                                            [table (vec rows)]))))
     :constraints-by-table (->> constraints
                                group-constraint-columns
                                (group-by :table_name))
     :indexes-by-table (->> indexes
                            (map (fn [index-row]
                                   (update index-row :column_names normalize-column-names)))
                            (remove :is_partial)
                            (remove :has_expression)
                            (remove :is_primary)
                            (remove :is_unique)
                            (group-by :table_name))}))

(defn generate-crud
  "Generates minimal SQL templates from PostgreSQL schema metadata.
   Initial implementation generates get-by-* and list-by-* templates."
  [datasource options]
  (let [{:keys [dialect schema]
         :or {dialect "postgresql"
              schema "public"}} options
        {:keys [columns-by-table constraints-by-table indexes-by-table]}
        (load-schema-metadata datasource schema)
        templates
        (->> columns-by-table
             (mapcat
              (fn [[table columns]]
                (let [columns-by-name (into {} (map (juxt :column_name identity) columns))
                      unique-column-groups (->> (get constraints-by-table table [])
                                                (map :column_names))
                      index-column-groups (->> (get indexes-by-table table [])
                                               (mapv :column_names))]
                  (concat
                   (generate-insert-templates schema table columns)
                   (generate-update-templates schema table columns columns-by-name unique-column-groups)
                   (generate-delete-templates schema table columns-by-name unique-column-groups)
                   (generate-get-templates schema table columns-by-name unique-column-groups)
                   (generate-list-templates schema
                                            table
                                            columns-by-name
                                            unique-column-groups
                                            index-column-groups)))))
             (sort-by (juxt :table :kind :name))
             vec)]
    {:dialect dialect
     :schema schema
     :templates templates}))
