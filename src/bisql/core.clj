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

(def parse-template
  query/parse-template)

(def renderer-plan
  query/renderer-plan)

(def emit-ir-form
  query/emit-ir-form)

(def compile-ir
  query/compile-ir)

(def evaluate-ir
  query/evaluate-ir)

(def render-compiled-query
  query/render-compiled-query)

(def render-query
  query/render-query)

(def DEFAULT
  query/DEFAULT)

(def ALL
  query/ALL)

(def generate-crud
  crud/generate-crud)

(def render-crud-files
  crud/render-crud-files)

(def write-crud-files!
  crud/write-crud-files!)

(def render-declaration-files
  define/render-declaration-files)

(def write-declaration-files!
  define/write-declaration-files!)

(defmacro defrender
  "Defines one rendering function per query found under the current query namespace path,
   or under a relative/absolute path when one is provided."
  ([]
   (let [ns-sym (ns-name *ns*)
         entries (define/definition-entries ns-sym nil)]
     (define/ensure-unique-var-names! entries)
     `(do ~@(mapv (fn [{:keys [template ir target-ns var-name]}]
                    (let [template-data (list 'quote template)
                          renderer-form (query/emit-ir-form ir)
                          metadata-data (list 'quote (define/render-function-metadata template))]
                      `(let [renderer# ~renderer-form]
                         (define/define-function-var!
                           '~target-ns
                           '~var-name
                           ~metadata-data
                           (with-meta
                             (fn
                               ([] (query/render-compiled-query ~template-data renderer# {}))
                               ([template-params#]
                                (query/render-compiled-query ~template-data renderer# template-params#)))
                             ~metadata-data)))))
                  entries))))
  ([path]
   (when-not (string? path)
     (throw (ex-info "defrender expects a path string when one argument is provided."
                     {:argument path
                      :type (type path)})))
   (let [ns-sym (ns-name *ns*)
         entries (define/definition-entries ns-sym path)]
     (define/ensure-unique-var-names! entries)
     `(do ~@(mapv (fn [{:keys [template ir target-ns var-name]}]
                    (let [template-data (list 'quote template)
                          renderer-form (query/emit-ir-form ir)
                          metadata-data (list 'quote (define/render-function-metadata template))]
                      `(let [renderer# ~renderer-form]
                         (define/define-function-var!
                           '~target-ns
                           '~var-name
                           ~metadata-data
                           (with-meta
                             (fn
                               ([] (query/render-compiled-query ~template-data renderer# {}))
                               ([template-params#]
                                (query/render-compiled-query ~template-data renderer# template-params#)))
                             ~metadata-data)))))
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
