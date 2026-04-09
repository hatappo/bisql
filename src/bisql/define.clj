(ns bisql.define
  (:require [bisql.query :as query]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn query-function-metadata
  [template]
  (let [{:keys [query-name resource-path base-path sql-template meta]} template
        reserved {:query-name query-name
                  :resource-path resource-path
                  :base-path base-path
                  :sql-template sql-template}]
    (merge meta reserved)))

(defn var-symbol-from-query-name
  [query-name]
  (symbol query-name))

(defn ensure-var-name-available!
  [ns-sym var-name resource-path query-name]
  (when-let [existing (ns-resolve ns-sym var-name)]
    (throw (ex-info "Query function var name already exists."
                    {:namespace (str ns-sym)
                     :var-name var-name
                     :resource-path resource-path
                     :query-name query-name
                     :existing existing}))))

(defn ensure-unique-var-names!
  [ns-sym entries]
  (doseq [[var-name grouped] (group-by :var-name entries)]
    (when (> (count grouped) 1)
      (throw (ex-info "Multiple queries resolve to the same var name."
                      {:namespace (str ns-sym)
                       :var-name var-name
                       :resource-paths (mapv :resource-path grouped)
                       :query-names (mapv :query-name grouped)})))
    (let [{:keys [resource-path query-name]} (first grouped)]
      (ensure-var-name-available! ns-sym var-name resource-path query-name))))

(defn- sql-filename?
  [path]
  (str/ends-with? path ".sql"))

(defn- namespace-path
  [ns-sym]
  (-> (str ns-sym)
      (str/replace "." "/")
      (str/replace "-" "_")))

(defn- resolve-target-path
  [ns-sym path]
  (let [current-path (namespace-path ns-sym)]
    (cond
      (nil? path) current-path
      (str/blank? path) current-path
      (str/starts-with? path "/") (subs path 1)
      :else (str current-path "/" path))))

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
                     var-name (var-symbol-from-query-name (:query-name analyzed-template))
                     metadata (query-function-metadata analyzed-template)]
                 {:template analyzed-template
                  :var-name var-name
                  :metadata metadata
                  :resource-path (:resource-path analyzed-template)
                  :query-name (:query-name analyzed-template)})))))
