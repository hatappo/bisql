(ns bisql.adapter.next-jdbc
  (:require [bisql.define :as define]
            [bisql.query :as query]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [java.sql Time Timestamp]
           [java.time Instant LocalDate LocalDateTime LocalTime OffsetDateTime ZonedDateTime]))

(def ^:private result-set-options
  {:builder-fn rs/as-unqualified-kebab-maps})

(defn ->timestamp
  "Converts common JVM date/time values into java.sql.Timestamp.
   This is an explicit helper for next.jdbc callers and is not applied automatically."
  [value]
  (cond
    (instance? Timestamp value)
    value

    (instance? java.util.Date value)
    (Timestamp. (.getTime ^java.util.Date value))

    (instance? Instant value)
    (Timestamp/from value)

    (instance? LocalDateTime value)
    (Timestamp/valueOf value)

    (instance? OffsetDateTime value)
    (Timestamp/from (.toInstant ^OffsetDateTime value))

    (instance? ZonedDateTime value)
    (Timestamp/from (.toInstant ^ZonedDateTime value))

    :else
    (throw (ex-info "Unsupported timestamp value type."
                    {:value value
                     :type (type value)}))))

(defn ->date
  "Converts common JVM date/time values into java.sql.Date.
   This is an explicit helper for next.jdbc callers and is not applied automatically."
  [value]
  (cond
    (instance? java.sql.Date value)
    value

    (instance? LocalDate value)
    (java.sql.Date/valueOf value)

    (instance? java.util.Date value)
    (java.sql.Date. (.getTime ^java.util.Date value))

    (instance? Instant value)
    (java.sql.Date. (.toEpochMilli ^Instant value))

    (instance? LocalDateTime value)
    (java.sql.Date/valueOf (.toLocalDate ^LocalDateTime value))

    (instance? OffsetDateTime value)
    (java.sql.Date/valueOf (.toLocalDate ^OffsetDateTime value))

    (instance? ZonedDateTime value)
    (java.sql.Date/valueOf (.toLocalDate ^ZonedDateTime value))

    :else
    (throw (ex-info "Unsupported date value type."
                    {:value value
                     :type (type value)}))))

(defn ->time
  "Converts common JVM date/time values into java.sql.Time.
   This is an explicit helper for next.jdbc callers and is not applied automatically."
  [value]
  (cond
    (instance? Time value)
    value

    (instance? LocalTime value)
    (Time/valueOf value)

    (instance? java.util.Date value)
    (Time. (.getTime ^java.util.Date value))

    (instance? Instant value)
    (Time. (.toEpochMilli ^Instant value))

    (instance? LocalDateTime value)
    (Time/valueOf (.toLocalTime ^LocalDateTime value))

    (instance? OffsetDateTime value)
    (Time/valueOf (.toLocalTime ^OffsetDateTime value))

    (instance? ZonedDateTime value)
    (Time/valueOf (.toLocalTime ^ZonedDateTime value))

    :else
    (throw (ex-info "Unsupported time value type."
                    {:value value
                     :type (type value)}))))

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
       :one (jdbc/execute-one! datasource statement result-set-options)
       :many (jdbc/execute! datasource statement result-set-options)
       (throw (ex-info "Unsupported result cardinality."
                       {:cardinality cardinality
                        :query queryish}))))))

(defmacro defquery
  "Defines one executable query function per query found in a SQL file or directory."
  ([]
   (let [ns-sym (ns-name *ns*)
         entries (define/definition-entries ns-sym nil)]
     (define/ensure-unique-var-names! entries)
     `(do ~@(mapv (fn [{:keys [template parsed-template target-ns var-name]}]
                    (let [template-data (list 'quote template)
                          renderer-form (query/emit-renderer-form parsed-template)
                          metadata-data (list 'quote (define/executable-query-function-metadata template))]
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
     `(do ~@(mapv (fn [{:keys [template parsed-template target-ns var-name]}]
                    (let [template-data (list 'quote template)
                          renderer-form (query/emit-renderer-form parsed-template)
                          metadata-data (list 'quote (define/executable-query-function-metadata template))]
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
