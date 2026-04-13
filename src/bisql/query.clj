(ns bisql.query
  (:require [bisql.engine :as engine]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def DEFAULT
  engine/DEFAULT)

(def ALL
  engine/ALL)

(def query-location
  engine/query-location)

(def analyze-template
  engine/analyze-template)

(def parse-template
  engine/parse-template)

(def renderer-plan
  engine/renderer-plan)

(def evaluate-renderer-plan
  engine/evaluate-renderer-plan)

(def emit-renderer-form
  engine/emit-renderer-form)

(def compile-renderer
  engine/compile-renderer)

(def evaluate-renderer
  engine/evaluate-renderer)

(def render-compiled-query
  engine/render-compiled-query)

(def render-query
  engine/render-query)

(defn- resource-path-for
  [base-path filename]
  (if (str/blank? base-path)
    filename
    (str base-path "/" filename)))

(defn- sql-filename?
  [filename]
  (str/ends-with? filename ".sql"))

(defn- query-name-from-filename
  [filename]
  (subs (.getName (io/file filename)) 0 (- (count (.getName (io/file filename))) 4)))

(defn- project-relative-path
  [resource-path resource]
  (try
    (let [project-root (.toPath (.getCanonicalFile (io/file ".")))
          file-path (.toPath (.getCanonicalFile (io/file resource)))]
      (if (.startsWith file-path project-root)
        (str/replace (str (.relativize project-root file-path)) #"\\+" "/")
        resource-path))
    (catch Exception _
      resource-path)))

(defn- load-query-resource
  [filename base-path]
  (when-not (sql-filename? filename)
    (throw (ex-info "Query file name must end with .sql."
                    {:filename filename
                     :base-path base-path})))
  (let [query-name (query-name-from-filename filename)
        resource-path (resource-path-for base-path filename)
        resource (io/resource resource-path)]
    (when-not resource
      (throw (ex-info "SQL resource not found."
                      {:query-name query-name
                       :filename filename
                       :base-path base-path
                       :resource-path resource-path})))
    {:filename filename
     :query-name query-name
     :base-path base-path
     :resource-path resource-path
     :project-relative-path (project-relative-path resource-path resource)
     :sql-template (slurp resource)}))

(defn load-queries
  "Loads a SQL file and returns all query templates keyed by query name."
  ([filename]
   (load-queries filename {}))
  ([filename {:keys [base-path]
              :or {base-path "sql"}}]
   (let [{:keys [query-name resource-path project-relative-path sql-template]}
         (load-query-resource filename base-path)]
     (try
       (let [blocks (engine/parse-query-blocks sql-template)
             multiple? (> (count blocks) 1)]
         (when (and multiple? (some #(nil? (:name %)) blocks))
           (throw (ex-info "Multiple queries require :name declarations."
                           {:filename filename
                            :base-path base-path
                            :resource-path resource-path})))
         (reduce
          (fn [queries {:keys [name sql-template source-line]}]
            (let [{:keys [query-name function-name namespace-suffix]}
                  (engine/query-location query-name name)]
              (when (contains? queries query-name)
                (throw (ex-info "Duplicate query name."
                                {:query-name query-name
                                 :filename filename
                                 :base-path base-path
                                 :resource-path resource-path})))
              (assoc queries
                     query-name
                     (engine/loaded-template query-name
                                             function-name
                                             namespace-suffix
                                             base-path
                                             resource-path
                                             project-relative-path
                                             source-line
                                             sql-template))))
          {}
          blocks))
       (catch clojure.lang.ExceptionInfo ex
         (let [{:keys [query-name]} (engine/query-location query-name nil)]
           (throw (ex-info (ex-message ex)
                           (merge {:filename filename
                                   :base-path base-path
                                   :resource-path resource-path
                                   :query-name query-name}
                                  (ex-data ex))
                           ex))))))))

(defn load-queries-from-file
  "Loads query templates directly from a filesystem SQL file."
  ([relative-path file-path]
   (load-queries-from-file relative-path file-path {}))
  ([relative-path file-path {:keys [base-path]
                             :or {base-path "sql"}}]
   (when-not (sql-filename? relative-path)
     (throw (ex-info "Query file name must end with .sql."
                     {:filename relative-path
                      :base-path base-path
                      :file-path file-path})))
   (let [query-name (query-name-from-filename relative-path)
         resource-path (resource-path-for base-path relative-path)
         sql-template (slurp file-path)
         project-relative-file-path (try
                                      (let [project-root (.toPath (.getCanonicalFile (io/file ".")))
                                            path (.toPath (.getCanonicalFile (io/file file-path)))]
                                        (str/replace (str (.relativize project-root path)) #"\\+" "/"))
                                      (catch Exception _
                                        resource-path))]
     (try
       (let [blocks (engine/parse-query-blocks sql-template)
             multiple? (> (count blocks) 1)]
         (when (and multiple? (some #(nil? (:name %)) blocks))
           (throw (ex-info "Multiple queries require :name declarations."
                           {:filename relative-path
                            :base-path base-path
                            :resource-path resource-path})))
         (reduce
          (fn [queries {:keys [name sql-template source-line]}]
            (let [{:keys [query-name function-name namespace-suffix]}
                  (engine/query-location query-name name)]
              (when (contains? queries query-name)
                (throw (ex-info "Duplicate query name."
                                {:query-name query-name
                                 :filename relative-path
                                 :base-path base-path
                                 :resource-path resource-path})))
              (assoc queries
                     query-name
                     (engine/loaded-template query-name
                                             function-name
                                             namespace-suffix
                                             base-path
                                             resource-path
                                             project-relative-file-path
                                             source-line
                                             sql-template))))
          {}
          blocks))
       (catch clojure.lang.ExceptionInfo ex
         (let [{:keys [query-name]} (engine/query-location query-name nil)]
           (throw (ex-info (ex-message ex)
                           (merge {:filename relative-path
                                   :base-path base-path
                                   :resource-path resource-path
                                   :query-name query-name}
                                  (ex-data ex))
                           ex))))))))

(defn load-query
  "Loads a SQL file and returns a single query template.
   If the file contains multiple queries, this function throws."
  ([filename]
   (load-query filename {}))
  ([filename {:keys [base-path]
              :or {base-path "sql"}}]
   (let [queries (load-queries filename {:base-path base-path})]
     (if (= 1 (count queries))
       (val (first queries))
       (throw (ex-info "Multiple queries found; use load-queries."
                       {:filename filename
                        :base-path base-path
                        :query-names (sort (keys queries))}))))))
