(ns bisql.define
  (:require [bisql.query :as query]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private navigation-stub-key ::navigation-stub)

(defn build-query-docstring
  ([template]
   (build-query-docstring template {:include-sql-template? true}))
  ([{:keys [meta project-relative-path resource-path source-line sql-template]}
    {:keys [include-sql-template?]
     :or {include-sql-template? true}}]
  (let [declared-doc (some-> (:doc meta) str str/trim not-empty)
        source-ref (str (or project-relative-path resource-path) ":" (or source-line 1))
        source-section (str "This function is generated from SQL: " source-ref)]
    (str
     (when declared-doc
       (str declared-doc "\n"))
     source-section
     (when include-sql-template?
       (str "\n\n" sql-template))))))

(defn query-function-metadata
  [template]
  (let [{:keys [query-name function-name namespace-suffix resource-path base-path
                project-relative-path source-line sql-template meta]} template
        reserved {:query-name query-name
                  :function-name function-name
                  :namespace-suffix namespace-suffix
                  :resource-path resource-path
                  :base-path base-path
                  :project-relative-path project-relative-path
                  :source-line source-line
                  :sql-template sql-template}]
    (-> (merge meta reserved)
        (assoc :declared-doc (:doc meta)
               :doc (build-query-docstring template)
               navigation-stub-key false))))

(defn navigation-stub-metadata
  ([template arglists]
   (navigation-stub-metadata template arglists {}))
  ([template arglists options]
  (let [cardinality (some-> template :meta :cardinality)]
    (cond-> (array-map :arglists (list 'quote arglists))
      cardinality
      (assoc :cardinality cardinality)

      true
      (assoc navigation-stub-key true
             :doc (build-query-docstring template options))))))

(defn render-function-metadata
  [template]
  (assoc (query-function-metadata template)
         :arglists '([]
                     [template-params])))

(defn executable-query-function-metadata
  [template]
  (assoc (query-function-metadata template)
         :arglists '([datasource]
                     [datasource template-params])))

(defn var-symbol-from-function-name
  [function-name]
  (symbol function-name))

(defn- target-namespace-symbol
  [resource-path namespace-suffix]
  (let [parent-path (some-> resource-path io/file .getParent str)
        parent-segments (cond-> []
                          parent-path
                          (into (str/split (str/replace parent-path "\\" "/") #"/")))
        suffix-segments (or namespace-suffix [])
        namespace-path (some->> (concat parent-segments suffix-segments)
                                (map #(str/replace % "_" "-"))
                                (str/join "."))]
    (symbol namespace-path)))

(defn ensure-var-name-available!
  [target-ns var-name resource-path query-name]
  (when-let [ns-obj (find-ns target-ns)]
    (when-let [existing (ns-resolve ns-obj var-name)]
      (when-not (true? (navigation-stub-key (meta existing)))
        (throw (ex-info "Query function var name already exists."
                        {:namespace (str target-ns)
                         :var-name var-name
                         :resource-path resource-path
                         :query-name query-name
                         :existing existing}))))))

(defn ensure-unique-var-names!
  [entries]
  (doseq [[[target-ns var-name] grouped] (group-by (juxt :target-ns :var-name) entries)]
    (when (> (count grouped) 1)
      (throw (ex-info "Multiple queries resolve to the same var name."
                      {:namespace (str target-ns)
                       :var-name var-name
                       :resource-paths (mapv :resource-path grouped)
                       :query-names (mapv :query-name grouped)})))
    (let [{:keys [resource-path query-name]} (first grouped)]
      (ensure-var-name-available! target-ns var-name resource-path query-name))))

(defn define-function-var!
  [target-ns var-name metadata f]
  (let [ns-obj (or (find-ns target-ns) (create-ns target-ns))
        v (intern ns-obj (with-meta var-name metadata) f)]
    (alter-meta! v merge metadata)
    v))

(defn- sql-filename?
  [path]
  (str/ends-with? path ".sql"))

(defn- namespace-path
  [ns-sym]
  (-> (str ns-sym)
      (str/replace "." "/")
      (str/replace "-" "_")))

(defn- normalize-path
  [path]
  (some-> path
          (str/replace "\\" "/")
          (str/replace #"^\./" "")
          (str/replace #"/+$" "")))

(defn- deps-classpath-roots
  []
  (let [deps-edn (-> "deps.edn" io/file slurp read-string)]
    (mapv normalize-path (:paths deps-edn))))

(defn- relative-to-classpath-root
  [path]
  (let [normalized-path (normalize-path path)
        matching-root (->> (deps-classpath-roots)
                           (filter #(or (= normalized-path %)
                                        (str/starts-with? normalized-path (str % "/"))))
                           (sort-by count >)
                           first)]
    (when matching-root
      (let [prefix (str matching-root "/")]
        (if (= normalized-path matching-root)
          ""
          (subs normalized-path (count prefix)))))))

(defn- path-from-sql-segment
  [path]
  (let [segments (str/split (normalize-path path) #"/")
        sql-index (first (keep-indexed (fn [idx segment]
                                         (when (= segment "sql") idx))
                                       segments))]
    (when sql-index
      (str/join "/" (subvec (vec segments) sql-index)))))

(defn- resolve-target-path
  [ns-sym path]
  (let [current-path (namespace-path ns-sym)]
    (cond
      (nil? path) current-path
      (str/blank? path) current-path
      (str/starts-with? path "/") (subs path 1)
      :else (str current-path "/" path))))

(defn- target-namespace-file-path
  [root-ns-sym target-ns]
  (let [root-prefix (str root-ns-sym)
        target (str target-ns)
        relative-ns (if (= target root-prefix)
                      ""
                      (subs target (inc (count root-prefix))))]
    (str (namespace-path relative-ns) ".clj")))

(defn- resource-file
  [resource-path]
  (some-> (io/resource resource-path) io/file))

(defn- relative-resource-path
  [root-dir file]
  (-> (.toPath root-dir)
      (.relativize (.toPath file))
      str
      (str/replace #"\\+" "/")))

(defn- sql-filenames-under
  [resource-path]
  (let [root (resource-file resource-path)]
    (when-not root
      (throw (ex-info "SQL resource not found."
                      {:resource-path resource-path})))
    (when-not (.isDirectory root)
      (throw (ex-info "Directory path is required when loading queries recursively."
                      {:resource-path resource-path})))
    (->> (file-seq root)
         (filter #(.isFile %))
         (map #(relative-resource-path root %))
         (filter sql-filename?)
         sort
         (mapv #(str resource-path "/" %)))))

(defn templates-for-definition
  [ns-sym path]
  (let [resource-path (resolve-target-path ns-sym path)]
    (if (sql-filename? resource-path)
      (->> (query/load-queries resource-path {:base-path ""})
           vals
           (sort-by :query-name))
      (->> (sql-filenames-under resource-path)
           (mapcat #(->> (query/load-queries % {:base-path ""})
                         vals))
           (sort-by (juxt :resource-path :query-name))
           vec))))

(defn definition-entries
  [ns-sym path]
  (->> (templates-for-definition ns-sym path)
       (mapv (fn [template]
               (let [analyzed-template (query/analyze-template template)
                     parsed-template (query/parse-template (:sql-template analyzed-template))
                     target-ns (target-namespace-symbol (:resource-path analyzed-template)
                                                        (:namespace-suffix analyzed-template))
                     var-name (var-symbol-from-function-name (:function-name analyzed-template))
                     metadata (query-function-metadata analyzed-template)]
                 {:template analyzed-template
                  :parsed-template parsed-template
                  :target-ns target-ns
                  :var-name var-name
                  :metadata metadata
                  :resource-path (:resource-path analyzed-template)
                  :query-name (:query-name analyzed-template)})))))

(defn declaration-entries
  [templates]
  (->> templates
       (mapv (fn [template]
               (let [analyzed-template (query/analyze-template template)
                     target-ns (target-namespace-symbol (:resource-path analyzed-template)
                                                        (:namespace-suffix analyzed-template))
                     var-name (var-symbol-from-function-name (:function-name analyzed-template))]
                 {:template analyzed-template
                  :target-ns target-ns
                  :var-name var-name
                  :resource-path (:resource-path analyzed-template)
                  :query-name (:query-name analyzed-template)})))))

(defn- sql-relative-paths-under
  [root-dir]
  (let [root-file (io/file root-dir)]
    (when-not (.exists root-file)
      (throw (ex-info "SQL base directory not found."
                      {:output-root root-dir})))
    (->> (file-seq root-file)
         (filter #(.isFile %))
         (map (fn [file]
                (-> (.toPath root-file)
                    (.relativize (.toPath file))
                    str
                    (str/replace #"\\+" "/"))))
         (filter sql-filename?)
         sort
         vec)))

(declare emit-literal)

(defn- emit-string-literal
  [s]
  (str "\""
       (str/escape s {\\ "\\\\"
                      \" "\\\""})
       "\""))

(defn- emit-map-literal
  [m indent]
  (if (empty? m)
    "{}"
    (let [child-padding (apply str (repeat (+ indent 2) " "))
          [[first-k first-v] & rest-entries] (seq m)
          first-entry (str "{"
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

(defn- emit-metadata-literal
  [m indent]
  (if (empty? m)
    "^{}"
    (str "^" (emit-map-literal m indent))))

(defn- emit-literal
  [value indent]
  (cond
    (string? value) (emit-string-literal value)
    (map? value) (emit-map-literal value indent)
    :else (pr-str value)))

(defn render-declaration-files
  ([]
   (render-declaration-files {}))
  ([{:keys [output-root suppress-unused-public-var? include-sql-template?]
     :or {output-root "src/sql"
          suppress-unused-public-var? false
          include-sql-template? false}}]
   (let [root-path (or (not-empty (relative-to-classpath-root output-root))
                       (not-empty (path-from-sql-segment output-root))
                       (normalize-path (.getName (io/file output-root))))
         root-ns-sym (symbol (str/replace root-path "/" "."))
         templates (->> (sql-relative-paths-under output-root)
                        (mapcat (fn [relative-path]
                                  (->> (query/load-queries-from-file relative-path
                                                                     (io/file output-root relative-path)
                                                                     {:base-path root-path})
                                       vals)))
                        (sort-by (juxt :resource-path :query-name))
                        vec)
         files (->> (declaration-entries templates)
                    (group-by :target-ns)
                    (sort-by (comp str key))
                    (mapv (fn [[target-ns entries]]
                            (let [content (str "(ns " target-ns ")\n\n"
                                               (->> entries
                                                    (sort-by (juxt :resource-path :query-name))
                                                    (mapv (fn [{:keys [template var-name]}]
                                                            (let [metadata (navigation-stub-metadata template
                                                                                                     '([datasource] [datasource template-params])
                                                                                                     {:include-sql-template? include-sql-template?})]
                                                              (str (when suppress-unused-public-var?
                                                                     "#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}\n")
                                                                   "(declare "
                                                                   (emit-metadata-literal metadata 9) "\n"
                                                                   " " var-name ")\n"))))
                                                    (str/join "\n")))]
                              {:path (target-namespace-file-path root-ns-sym target-ns)
                               :namespace target-ns
                               :content content}))))]
     {:files files})))

(defn write-declaration-files!
  ([]
   (write-declaration-files! {}))
  ([{:keys [output-root suppress-unused-public-var? include-sql-template?]
     :or {output-root "src/sql"
          suppress-unused-public-var? false
          include-sql-template? false}}]
   (let [rendered (render-declaration-files {:output-root output-root
                                             :suppress-unused-public-var? suppress-unused-public-var?
                                             :include-sql-template? include-sql-template?})]
     (doseq [{:keys [path content]} (:files rendered)]
       (let [output-file (io/file output-root path)]
         (io/make-parents output-file)
         (spit output-file content)))
     rendered)))
