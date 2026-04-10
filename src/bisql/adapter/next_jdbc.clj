(ns bisql.adapter.next-jdbc
  (:require [bisql.define :as define]
            [bisql.query :as query]
            [next.jdbc :as jdbc]))

(defn- rendered-query
  [queryish template-params]
  (cond
    (fn? queryish)
    (queryish template-params)

    (and (map? queryish)
         (contains? queryish :sql)
         (contains? queryish :params))
    queryish

    (and (map? queryish)
         (contains? queryish :renderer))
    (query/render-compiled-query queryish (:renderer queryish) template-params)

    (map? queryish)
    (query/render-query queryish template-params)

    :else
    (throw (ex-info "Unsupported query value."
                    {:query queryish
                     :type (type queryish)}))))

(defn- result-cardinality
  [queryish rendered]
  (or (some-> (meta queryish) :cardinality)
      (some-> rendered :meta :cardinality)
      :many))

(defn exec!
  "Renders and executes a query via next.jdbc.
   The result cardinality is resolved from :cardinality metadata and must be :one or :many."
  ([datasource queryish]
   (exec! datasource queryish {}))
  ([datasource queryish template-params]
   (let [{:keys [sql params] :as rendered} (rendered-query queryish template-params)
         cardinality (result-cardinality queryish rendered)
         statement (into [sql] params)]
     (case cardinality
       :one (jdbc/execute-one! datasource statement)
       :many (jdbc/execute! datasource statement)
       (throw (ex-info "Unsupported result cardinality."
                       {:cardinality cardinality
                        :query queryish}))))))

(defmacro defquery
  "Defines one executable query function per query found in a SQL file or directory."
  ([]
   (let [ns-sym (ns-name *ns*)
         entries (define/definition-entries ns-sym nil)]
     (define/ensure-unique-var-names! entries)
     `(do ~@(mapv (fn [{:keys [template ir target-ns var-name metadata]}]
                    (let [template-data (list 'quote template)
                          renderer-form (query/emit-ir-form ir)
                          metadata-data (list 'quote metadata)]
                      `(let [renderer# ~renderer-form
                             compiled-template# (with-meta
                                                  (assoc ~template-data :renderer renderer#)
                                                  ~metadata-data)]
                         (define/define-function-var!
                           '~target-ns
                           '~var-name
                           ~metadata-data
                           (with-meta
                             (fn
                               ([datasource#] (exec! datasource# compiled-template# {}))
                               ([datasource# template-params#]
                                (exec! datasource# compiled-template# template-params#)))
                             ~metadata-data)))))
                  entries))))
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
   (let [ns-sym (ns-name *ns*)
         entries (define/definition-entries ns-sym path)]
     (define/ensure-unique-var-names! entries)
     `(do ~@(mapv (fn [{:keys [template ir target-ns var-name metadata]}]
                    (let [template-data (list 'quote template)
                          renderer-form (query/emit-ir-form ir)
                          metadata-data (list 'quote metadata)]
                      `(let [renderer# ~renderer-form
                             compiled-template# (with-meta
                                                  (assoc ~template-data :renderer renderer#)
                                                  ~metadata-data)]
                         (define/define-function-var!
                           '~target-ns
                           '~var-name
                           ~metadata-data
                           (with-meta
                             (fn
                               ([datasource#] (exec! datasource# compiled-template# {}))
                               ([datasource# template-params#]
                                (exec! datasource# compiled-template# template-params#)))
                             ~metadata-data)))))
                  entries)))))
