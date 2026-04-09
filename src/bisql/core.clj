(ns bisql.core
  (:require [bisql.crud :as crud]
            [bisql.define :as define]
            [bisql.query :as query]
            [bisql.adapter.next-jdbc]))

(def load-query
  query/load-query)

(def load-queries
  query/load-queries)

(def analyze-template
  query/analyze-template)

(def render-query
  query/render-query)

(def default
  query/default)

(def generate-crud
  crud/generate-crud)

(def render-crud-files
  crud/render-crud-files)

(def write-crud-files!
  crud/write-crud-files!)

(def render-crud-query-namespaces
  crud/render-crud-query-namespaces)

(def write-crud-query-namespaces!
  crud/write-crud-query-namespaces!)

(defmacro defrender
  "Defines one rendering function per query found under the current query namespace path,
   or under a relative/absolute path when one is provided."
  ([]
   (let [ns-sym (ns-name *ns*)
         entries (define/definition-entries ns-sym nil)]
     (define/ensure-unique-var-names! ns-sym entries)
     `(do ~@(mapv (fn [{:keys [template var-name metadata]}]
                    `(def ~(with-meta var-name metadata)
                       (with-meta
                         (fn
                           ([] (query/render-query ~template {}))
                           ([template-params#] (query/render-query ~template template-params#)))
                         ~metadata)))
                  entries))))
  ([path]
   (when-not (string? path)
     (throw (ex-info "defrender expects a path string when one argument is provided."
                     {:argument path
                      :type (type path)})))
   (let [ns-sym (ns-name *ns*)
         entries (define/definition-entries ns-sym path)]
     (define/ensure-unique-var-names! ns-sym entries)
     `(do ~@(mapv (fn [{:keys [template var-name metadata]}]
                    `(def ~(with-meta var-name metadata)
                       (with-meta
                         (fn
                           ([] (query/render-query ~template {}))
                           ([template-params#] (query/render-query ~template template-params#)))
                         ~metadata)))
                  entries)))))

(defmacro defquery
  "Defines one executable query function per query found in a SQL file or directory.
   The default adapter is :next-jdbc."
  ([]
   `(defquery nil {}))
  ([path]
   (when-not (string? path)
     (throw (ex-info "defquery expects a path string when one argument is provided."
                     {:argument path
                      :type (type path)})))
   `(defquery ~path {}))
  ([path options]
   (when (some? path)
     (when-not (string? path)
       (throw (ex-info "defquery path must be a string."
                       {:filename path
                        :type (type path)}))))
   (when-not (map? options)
     (throw (ex-info "defquery options must be a map."
                     {:options options
                      :type (type options)})))
   (let [adapter (or (:adapter options) :next-jdbc)
         load-options (dissoc options :adapter)]
     (case adapter
       :next-jdbc `(bisql.adapter.next-jdbc/defquery ~path ~load-options)
       (throw (ex-info "Unsupported adapter."
                       {:adapter adapter})))))) 
