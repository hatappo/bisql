(ns bisql.crud
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [bisql.schema :as bisql.schema]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def ^:private metadata-query-options
  {:builder-fn rs/as-unqualified-lower-maps})

(declare render-schema-files)

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

(defn- defaultable-column?
  [column]
  (or (some? (:column_default column))
      (= "YES" (:is_identity column))))

(defn- bind-comment
  ([column]
   (bind-comment nil column))
  ([parameter-prefix column]
   (bind-comment parameter-prefix column nil))
  ([parameter-prefix column {:keys [default-to?]}]
   (let [segments (cond-> []
                    parameter-prefix (conj parameter-prefix)
                    true (conj (kebab-name (:column_name column))))]
     (str "/*$"
          (str/join "." segments)
          (when default-to? " default-to ")
          "*/"
          (if default-to?
            "DEFAULT"
            (sample-token column))))))

(defn- bind-comment-with-sample
  [parameter-path sample-column]
  (str "/*$"
       parameter-path
       "*/"
       (sample-token sample-column)))

(defn- starts-with-sample-token
  [column]
  (let [token (sample-token column)]
    (if (str/starts-with? token "'")
      (str (subs token 0 (dec (count token))) "%'")
      token)))

(defn- bind-comment-starts-with
  [column]
  (str "/*$"
       (kebab-name (:column_name column))
       "*/"
       (starts-with-sample-token column)))

(defn- text-column?
  [{:keys [data_type]}]
  (#{"text" "character varying" "character"} data_type))

(defn- exact-predicate-spec
  [column]
  {:column column
   :operator :eq})

(defn- starts-with-predicate-spec
  [column]
  {:column column
   :operator :starts-with})

(defn- where-clause
  ([predicate-specs]
   (where-clause nil predicate-specs))
  ([parameter-prefix predicate-specs]
  (when (seq predicate-specs)
    (str/join "\n"
              (map-indexed
               (fn [idx {:keys [column operator]}]
                 (str (if (zero? idx) "WHERE " "  AND ")
                      (:column_name column)
                      (case operator
                        :eq
                        (str " = " (bind-comment parameter-prefix column))

                        :starts-with
                        (str " LIKE "
                             (if parameter-prefix
                               (str "/*$"
                                    parameter-prefix
                                    "."
                                    (kebab-name (:column_name column))
                                    "*/"
                                    (starts-with-sample-token column))
                               (bind-comment-starts-with column))
                             " ESCAPE '\\'"))))
               predicate-specs)))))

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
  [table predicate-specs order-columns]
  (str/join "\n"
            (remove nil?
                    [(str "SELECT * FROM " table)
                     (where-clause predicate-specs)
                     (order-by-clause order-columns)
                     (list-limit-clause)])))

(defn- count-template
  [table predicate-specs]
  (str/join "\n"
            (remove nil?
                    [(str "SELECT COUNT(*) AS count FROM " table)
                     (where-clause predicate-specs)])))

(defn- get-template
  [table predicate-columns]
  (str/join "\n"
            [(str "SELECT * FROM " table)
             (where-clause (map exact-predicate-spec predicate-columns))]))

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

(defn- table-schema-file-path
  [dialect schema table]
  (str dialect "/" schema "/" table "/schema.clj"))

(defn- table-schema-namespace-symbol
  [dialect schema table]
  (symbol (str "sql."
               (kebab-name dialect)
               "."
               (kebab-name schema)
               "."
               (kebab-name table)
               ".schema")))

(defn- declaration-key-name
  [k]
  (if-let [ns-name (namespace k)]
    (str ns-name "/" (name k))
    (name k)))

(def ^:private default-sentinel-schema-symbol
  'bisql.schema/malli-default-sentinel)

(def ^:private limit-schema-symbol
  'bisql.schema/malli-limit)

(def ^:private offset-schema-symbol
  'bisql.schema/malli-offset)

(defn- query-block
  [{:keys [name meta sql-template]}]
  (let [declarations (concat [[:name name]]
                             (sort-by key (dissoc (or meta {}) :name)))
        declaration-lines (map (fn [[k v]]
                                 (str "/*:" (declaration-key-name k) " "
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
   :upsert 1
   :get 2
   :count 3
   :list 4
   :update 5
   :delete 6})

(def ^:private crud-generation-issue-url
  "https://github.com/hatappo/bisql/issues")

(defn- sort-templates-for-file
  [templates]
  (sort-by (juxt #(get crud-kind-order (:kind %) Long/MAX_VALUE)
                 :name)
           templates))

(defn- duplicate-template-warning
  [table name duplicate-count]
  (str "WARNING: Multiple generated CRUD templates resolved to the same name `"
       name
       "` for table `"
       table
       "`, and their SQL template or metadata differed. "
       "The first template was kept from "
       duplicate-count
       " candidates. "
       "This likely indicates a gap in bisql's CRUD generation logic. "
       "Please report it here: "
       crud-generation-issue-url))

(defn- dedupe-templates-by-name
  [templates]
  (let [{:keys [ordered-names templates-by-name]}
        (reduce (fn [{:keys [ordered-names templates-by-name] :as acc} template]
                  (let [template-key [(:table template) (:name template)]]
                    (if (contains? templates-by-name template-key)
                      (update acc :templates-by-name update template-key conj template)
                      {:ordered-names (conj ordered-names template-key)
                       :templates-by-name (assoc templates-by-name template-key [template])})))
                {:ordered-names []
                 :templates-by-name {}}
                templates)]
    (reduce (fn [{:keys [templates warnings]} template-key]
              (let [grouped (get templates-by-name template-key)
                    kept-template (first grouped)
                    distinct-shapes (->> grouped
                                         (map (juxt :meta :sql-template))
                                         distinct)]
                {:templates (conj templates kept-template)
                 :warnings (cond-> warnings
                             (and (> (count grouped) 1)
                                  (> (count distinct-shapes) 1))
                             (conj (duplicate-template-warning
                                    (:table kept-template)
                                    (:name kept-template)
                                    (count grouped))))}))
            {:templates []
             :warnings []}
            ordered-names)))

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
  [crud-result {:keys [output-root derive-schemas?]
                :or {output-root "src/sql"
                     derive-schemas? true}}]
  (let [sql-file-result (render-crud-files crud-result)
        schema-files (render-schema-files crud-result {:derive-schemas? derive-schemas?})
        files (vec (concat (:files sql-file-result) schema-files))]
    (doseq [{:keys [path content]} files]
      (let [output-file (io/file output-root path)]
        (io/make-parents output-file)
        (spit output-file content)))
    {:dialect (:dialect sql-file-result)
     :schema (:schema sql-file-result)
     :sql-files (:files sql-file-result)
     :schema-files schema-files
     :files files}))

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
  [_column]
  true)

(defn- updateable-column?
  [column]
  (insertable-column? column))

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
                                       (bind-comment nil column {:default-to? (:insert-default-to? column)})
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
    "/*%for row in rows separating , */"
    "("
    (str/join "\n" (map-indexed (fn [idx column]
                                  (str "  "
                                       (bind-comment "row" column {:default-to? (:insert-default-to? column)})
                                       (when (< idx (dec (count columns))) ",")))
                                columns))
    ")"
    "/*%end */"
    "RETURNING *"]))

(defn- delete-template
  [table predicate-columns]
  (str/join "\n"
            [(str "DELETE FROM " table)
             (where-clause (map exact-predicate-spec predicate-columns))
             "RETURNING *"]))

(defn- dynamic-set-clause
  [sample-column]
  (str/join "\n"
            ["SET"
             "/*%for item in updates separating , */"
             (str "  /*!item.name*/"
                  (:column_name sample-column)
                  " = "
                  (bind-comment-with-sample "item.value" sample-column))
             "/*%end */"]))

(defn- nested-dynamic-set-clause
  [sample-column]
  (str/join "\n"
            ["SET"
             "  /*%for item in updates separating , */"
             (str "    /*!item.name*/"
                  (:column_name sample-column)
                  " = "
                  (bind-comment-with-sample "item.value" sample-column))
             "  /*%end */"]))

(defn- update-template
  [table predicate-columns sample-column]
  (str/join "\n"
            [(str "UPDATE " table)
             (dynamic-set-clause sample-column)
             (where-clause "where" (map exact-predicate-spec predicate-columns))
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

(defn- upsert-template
  [table constraint-name conflict-column-names insert-columns update-columns]
  (let [conflict-column-names (set conflict-column-names)
        update-lines (when (seq update-columns)
                       [(nested-dynamic-set-clause (last update-columns))])]
    (str/join
     "\n"
     (concat
      [(str "INSERT INTO " table " (")
       (str/join "\n" (map-indexed (fn [idx column]
                                     (str "  "
                                          (:column_name column)
                                          (when (< idx (dec (count insert-columns))) ",")))
                                   insert-columns))
       ")"
       "VALUES ("
       (str/join "\n" (map-indexed (fn [idx column]
                                     (str "  "
                                          (bind-comment "inserts"
                                                        column
                                                        {:default-to? (and (:insert-default-to? column)
                                                                           (not (contains? conflict-column-names
                                                                                           (:column_name column))))})
                                          (when (< idx (dec (count insert-columns))) ",")))
                                   insert-columns))
       ")"
       (str "ON CONFLICT ON CONSTRAINT " constraint-name)
       "/*%if updates */"
       "DO UPDATE"]
      (if (seq update-columns)
        (concat update-lines
                ["/*%else => DO NOTHING */"
                 "/*%end */"])
        ["DO NOTHING"])
      ["RETURNING *"]))))

(defn- generate-upsert-templates
  [schema table columns constraints]
  (let [insert-columns (filterv insertable-column? columns)]
    (->> constraints
         (map (fn [{:keys [constraint_name column_names]}]
                (let [update-columns (filterv updateable-column? columns)]
                  (named-template-entry schema
                                        table
                                        :upsert
                                        (str "upsert-by-" (joined-name column_names))
                                        column_names
                                        (upsert-template table
                                                         constraint_name
                                                         column_names
                                                         insert-columns
                                                         update-columns)
                                        :meta {:cardinality :one}
                                        :set-columns (mapv :column_name update-columns)))))
         vec)))

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
                    set-columns (filterv updateable-column? columns)]
                (when (seq set-columns)
                  (template-entry schema
                                  table
                                  :update
                                  predicate-column-names
                                  (update-template table predicate-columns (last set-columns))
                                  :meta {:cardinality :one}
                                  :set-columns (mapv :column_name set-columns))))))
       (remove nil?)
       vec))

(defn- generate-count-templates
  [schema table columns-by-name unique-column-groups index-column-groups]
  (letfn [(count-template-candidates [column-groups strict-prefix?]
            (->> column-groups
                 (mapcat
                  (fn [column-group]
                    (let [column-group (normalize-column-names column-group)
                          prefix-lengths (if strict-prefix?
                                           (range 0 (count column-group))
                                           (range 0 (inc (count column-group))))]
                      (map (fn [prefix-length]
                             (subvec column-group 0 prefix-length))
                           prefix-lengths))))
                 distinct))
          (count-template-name [prefix-column-names]
            (if (seq prefix-column-names)
              (str "count-by-" (joined-name prefix-column-names))
              "count"))
          (count-starts-with-candidates [column-groups]
            (->> column-groups
                 (map normalize-column-names)
                 distinct))
          (count-starts-with-template-name [column-names]
            (str "count-by-" (joined-name column-names) "-starting-with"))]
    (let [candidates (concat (count-template-candidates unique-column-groups true)
                             (count-template-candidates index-column-groups false))
          starts-with-candidates (->> (concat (count-starts-with-candidates unique-column-groups)
                                              (count-starts-with-candidates index-column-groups))
                                      (filter seq)
                                      (filter #(text-column? (columns-by-name (last %)))))]
      (mapv (fn [{:keys [name columns predicate-specs]}]
              (named-template-entry schema
                                    table
                                    :count
                                    name
                                    columns
                                    (count-template table predicate-specs)
                                    :meta {:cardinality :one}))
            (concat
             (map (fn [prefix-column-names]
                    (let [predicate-columns (mapv columns-by-name prefix-column-names)]
                      {:name (count-template-name prefix-column-names)
                       :columns prefix-column-names
                       :predicate-specs (mapv exact-predicate-spec predicate-columns)}))
                  candidates)
             (map (fn [column-names]
                    (let [columns (mapv columns-by-name column-names)
                          exact-columns (pop columns)
                          starts-with-column (peek columns)]
                      {:name (count-starts-with-template-name column-names)
                       :columns column-names
                       :predicate-specs (-> (mapv exact-predicate-spec exact-columns)
                                            (conj (starts-with-predicate-spec starts-with-column)))}))
                  starts-with-candidates))))))

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
          (list-starts-with-candidates [column-groups]
            (->> column-groups
                 (map normalize-column-names)
                 distinct))
          (list-starts-with-template-name [column-names]
            (str "list-by-" (joined-name column-names) "-starting-with"))
          (list-template-name [{:keys [prefix-column-names order-column-names]} colliding-base-names]
            (let [base-name (list-template-base-name prefix-column-names)]
              (if (and (contains? colliding-base-names base-name)
                       (seq order-column-names))
                (str base-name "-order-by-" (joined-name order-column-names))
                base-name)))]
    (let [candidates (concat (list-template-candidates unique-column-groups true)
                             (list-template-candidates index-column-groups false))
          starts-with-candidates (->> (concat (list-starts-with-candidates unique-column-groups)
                                              (list-starts-with-candidates index-column-groups))
                                      (filter seq)
                                      (filter #(text-column? (columns-by-name (last %)))))
          colliding-base-names (->> candidates
                                    (group-by #(list-template-base-name (:prefix-column-names %)))
                                    (keep (fn [[base-name entries]]
                                            (when (> (count entries) 1)
                                              base-name)))
                                    set)]
      (mapv (fn [{:keys [prefix-column-names order-column-names] :as candidate}]
              (let [{:keys [name columns predicate-specs]}
                    (when (:starts-with? candidate) candidate)
                    predicate-columns (mapv columns-by-name prefix-column-names)
                    predicate-specs (or predicate-specs (mapv exact-predicate-spec predicate-columns))
                    order-columns (mapv columns-by-name order-column-names)]
                (named-template-entry schema
                                      table
                                      :list
                                      (or name
                                          (list-template-name candidate colliding-base-names))
                                      (or columns prefix-column-names)
                                      (select-template table predicate-specs order-columns)
                                      :meta {:cardinality :many})))
            (concat candidates
                    (map (fn [column-names]
                           (let [columns (mapv columns-by-name column-names)
                                 exact-columns (pop columns)
                                 starts-with-column (peek columns)]
                             {:starts-with? true
                              :name (list-starts-with-template-name column-names)
                              :columns column-names
                              :predicate-specs (-> (mapv exact-predicate-spec exact-columns)
                                                   (conj (starts-with-predicate-spec starts-with-column)))
                              :prefix-column-names column-names
                              :order-column-names [(:column_name starts-with-column)]}))
                         starts-with-candidates))))))

(defn- map-entry-form
  ([k schema]
   [k schema])
  ([k schema optional?]
   (if optional?
     [k {:optional true} schema]
     [k schema])))

(defn- column-keyword
  [column-name]
  (keyword (kebab-name column-name)))

(defn- nullable-column?
  [column]
  (= "YES" (:is_nullable column)))

(defn- column-schema-leaf
  [{:keys [data_type]}]
  (case data_type
    ("smallint" "integer" "bigint") 'int?
    ("numeric" "real" "double precision") 'number?
    ("text" "character varying" "character") 'string?
    "boolean" 'boolean?
    "uuid" [:fn 'bisql.schema/uuid-value?]
    "date" [:fn 'bisql.schema/local-date?]
    "time without time zone" [:fn 'bisql.schema/local-time?]
    "time with time zone" [:fn 'bisql.schema/offset-time?]
    "timestamp without time zone" [:fn 'bisql.schema/local-date-time?]
    "timestamp with time zone" [:fn 'bisql.schema/offset-date-time?]
    "bytea" 'bytes?
    'any?))

(defn- maybe-schema-form
  [schema nullable?]
  (if nullable?
    [:maybe schema]
    schema))

(defn- defaultable-schema-form
  [schema allow-default? column]
  (if (and allow-default? (defaultable-column? column))
    [:or schema default-sentinel-schema-symbol]
    schema))

(defn- column-value-schema-form
  [column & {:keys [allow-default?]
             :or {allow-default? false}}]
  (-> (column-schema-leaf column)
      (maybe-schema-form (nullable-column? column))
      (defaultable-schema-form allow-default? column)))

(defn- map-schema-form
  [entries]
  (into [:map {:closed true}] entries))

(defn- schema-var-symbol
  [dialect schema table var-name]
  (symbol (str (table-schema-namespace-symbol dialect schema table))
          (name var-name)))

(defn- row-schema-definition
  [columns derive?]
  (if derive?
    ['row
     '(bisql.schema/malli-map-all-entries-required
       (bisql.schema/malli-map-all-entries-strip-default-sentinel insert))]
    ['row
     (map-schema-form
      (mapv (fn [column]
              (map-entry-form (column-keyword (:column_name column))
                              (column-value-schema-form column)))
            columns))]))

(defn- insert-schema-form
  ([columns]
   (insert-schema-form columns nil))
  ([columns required-column-names]
   (let [required-column-names (set required-column-names)]
     (map-schema-form
      (mapv (fn [column]
              (map-entry-form (column-keyword (:column_name column))
                              (column-value-schema-form column :allow-default? true)
                              (and (:insert-default-to? column)
                                   (not (contains? required-column-names
                                                   (:column_name column))))))
            (filterv insertable-column? columns))))))

(defn- insert-schema-definition
  [columns]
  ['insert (insert-schema-form columns)])

(defn- update-schema-definition
  [_columns derive?]
  (if derive?
    ['update
     '(bisql.schema/malli-map-all-entries-optional insert)]
    ['update
     (let [[_ insert-schema-form] (insert-schema-definition _columns)]
       (bisql.schema/malli-map-all-entries-optional insert-schema-form))]))

(defn- simple-params-schema-form
  [columns-by-name column-names]
  (map-schema-form
   (mapv (fn [column-name]
           (let [column (columns-by-name column-name)]
             (map-entry-form (column-keyword column-name)
                             (column-value-schema-form column))))
         column-names)))

(defn- list-params-schema-form
  [columns-by-name column-names]
  (map-schema-form
   (concat
    (mapv (fn [column-name]
            (let [column (columns-by-name column-name)]
              (map-entry-form (column-keyword column-name)
                              (column-value-schema-form column))))
          column-names)
    [[:limit limit-schema-symbol]
     [:offset offset-schema-symbol]])))

(defn- update-params-schema-form
  [dialect schema table columns-by-name predicate-column-names]
  (map-schema-form
   [[:where (simple-params-schema-form columns-by-name predicate-column-names)]
    [:updates (schema-var-symbol dialect schema table 'update)]]))

(defn- upsert-params-schema-form
  [dialect schema table columns conflict-column-names set-column-names]
  (let [conflict-column-names (set conflict-column-names)
        conflict-needs-required-insert-schema?
        (some (fn [column]
                (and (:insert-default-to? column)
                     (contains? conflict-column-names (:column_name column))))
              columns)
        inserts-schema (if conflict-needs-required-insert-schema?
                         (insert-schema-form columns conflict-column-names)
                         (schema-var-symbol dialect schema table 'insert))]
    (cond-> (map-schema-form [[:inserts inserts-schema]])
      (seq set-column-names)
      (conj [:updates [:maybe (schema-var-symbol dialect schema table 'update)]]))))

(defn- query-schema-definitions
  [columns {:keys [derive-schemas?]
            :or {derive-schemas? true}}]
  (let [definitions (concat
                     [(insert-schema-definition columns)
                      (update-schema-definition columns derive-schemas?)
                      (row-schema-definition columns derive-schemas?)]
                     )]
    (:definitions
     (reduce (fn [{:keys [seen definitions] :as acc} [definition-name schema-form]]
               (if (contains? seen definition-name)
                 acc
                 {:seen (conj seen definition-name)
                  :definitions (conj definitions [definition-name schema-form])}))
             {:seen #{}
              :definitions []}
             definitions))))

(defn- template-input-schema-form
  [dialect schema table columns columns-by-name template]
  (case (:kind template)
    :insert (if (= (:name template) "crud.insert-many")
              (map-schema-form [[:rows [:sequential (schema-var-symbol dialect schema table 'insert)]]])
              (schema-var-symbol dialect schema table 'insert))
    :get (simple-params-schema-form columns-by-name (:columns template))
    :count (simple-params-schema-form columns-by-name (:columns template))
    :delete (simple-params-schema-form columns-by-name (:columns template))
    :list (list-params-schema-form columns-by-name (:columns template))
    :update (update-params-schema-form dialect schema table columns-by-name (:columns template))
    :upsert (upsert-params-schema-form dialect schema table columns (:columns template) (:set-columns template))))

(defn- template-output-schema-form
  [dialect schema table template]
  (let [row-symbol (schema-var-symbol dialect schema table 'row)]
    (cond
      (= (:kind template) :get) [:maybe row-symbol]
      (= (:kind template) :list) [:sequential row-symbol]
      (= (:kind template) :count) [:map {:closed true} [:count 'int?]]
      (= (:name template) "crud.insert-many") [:sequential row-symbol]
      :else row-symbol)))

(defn- attach-malli-metadata
  [dialect schema columns-by-table templates]
  (mapv (fn [template]
          (let [table (:table template)
                columns (get columns-by-table table)
                columns-by-name (into {} (map (juxt :column_name identity)
                                              columns))]
            (update template
                    :meta
                    (fn [meta]
                      (assoc (or meta {})
                             :malli/in (template-input-schema-form dialect schema table columns columns-by-name template)
                             :malli/out (template-output-schema-form dialect schema table template))))))
        templates))

(defn- pprint-str
  [value]
  (str/trimr
   (with-out-str
     (binding [pprint/*print-right-margin* 100]
       (pprint/pprint value)))))

(defn- indent-lines
  [s prefix]
  (str/join "\n" (map #(str prefix %) (str/split-lines s))))

(defn- schema-definition-block
  [[definition-name schema-form] _options]
  (str "#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}\n"
       "(def " definition-name "\n"
       (indent-lines (pprint-str schema-form) "  ")
       ")\n"))

(defn- table-schema-content
  [dialect schema table columns _templates {:keys [derive-schemas?]
                                            :or {derive-schemas? true}
                                            :as options}]
  (let [definitions (query-schema-definitions columns {:derive-schemas? derive-schemas?})]
    (str "(ns " (table-schema-namespace-symbol dialect schema table) "\n"
         "  (:refer-clojure :exclude [update])\n"
         "  (:require [bisql.schema :as bisql.schema]))\n\n"
         (str/join "\n" (map #(schema-definition-block % options) definitions)))))

(defn- render-schema-files
  [{:keys [dialect schema columns-by-table templates]}
   {:as options}]
  (if (seq columns-by-table)
    (->> columns-by-table
         (sort-by key)
         (mapv (fn [[table columns]]
                 {:table table
                  :path (table-schema-file-path dialect schema table)
                  :content (table-schema-content dialect
                                                schema
                                                table
                                                columns
                                                (sort-templates-for-file
                                                 (filterv #(= table (:table %)) templates))
                                                options)})))
    []))

(defn- table-columns-query
  [schema]
  ["SELECT table_name,
           column_name,
           data_type,
           is_identity,
           is_nullable,
           column_default,
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
                :constraint_name (:constraint_name (first constraint-rows))
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

(defn- primary-key-column-name-set
  [constraints]
  (->> constraints
       (filter #(= "PRIMARY KEY" (:constraint_type %)))
       (mapcat :column_names)
       set))

(defn- annotate-insert-default-to-columns
  [columns constraints]
  (let [primary-key-column-names (primary-key-column-name-set constraints)]
    (mapv (fn [column]
            (cond-> column
              (and (contains? primary-key-column-names (:column_name column))
                   (defaultable-column? column))
              (assoc :insert-default-to? true)))
          columns)))

(defn generate-crud
  "Generates minimal SQL templates from PostgreSQL schema metadata.
   Initial implementation generates get-by-* and list-by-* templates."
  [datasource options]
  (let [{:keys [dialect schema]
         :or {dialect "postgresql"
              schema "public"}} options
        {:keys [columns-by-table constraints-by-table indexes-by-table]}
        (load-schema-metadata datasource schema)
        columns-by-table (into {}
                               (map (fn [[table columns]]
                                      [table (annotate-insert-default-to-columns
                                              columns
                                              (get constraints-by-table table []))]))
                               columns-by-table)
        generated-templates
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
                  (generate-upsert-templates schema table columns (get constraints-by-table table []))
                  (generate-update-templates schema table columns columns-by-name unique-column-groups)
                  (generate-delete-templates schema table columns-by-name unique-column-groups)
                  (generate-get-templates schema table columns-by-name unique-column-groups)
                  (generate-count-templates schema
                                            table
                                            columns-by-name
                                            unique-column-groups
                                            index-column-groups)
                   (generate-list-templates schema
                                            table
                                            columns-by-name
                                            unique-column-groups
                                            index-column-groups)))))
             (sort-by (juxt :table :kind :name))
             vec)
        {:keys [templates warnings]}
        (dedupe-templates-by-name generated-templates)]
    {:dialect dialect
     :schema schema
     :columns-by-table columns-by-table
     :templates (attach-malli-metadata dialect schema columns-by-table templates)
     :warnings warnings}))
