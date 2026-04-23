(ns bisql.schema
  (:require [bisql.query :as query]))

(defn uuid-value?
  [value]
  (instance? java.util.UUID value))

(defn local-date?
  [value]
  (or (instance? java.time.LocalDate value)
      (instance? java.sql.Date value)))

(defn local-time?
  [value]
  (or (instance? java.time.LocalTime value)
      (instance? java.sql.Time value)))

(defn offset-time?
  [value]
  (or (instance? java.time.OffsetTime value)
      (instance? java.sql.Time value)))

(defn local-date-time?
  [value]
  (or (instance? java.time.LocalDateTime value)
      (instance? java.sql.Timestamp value)
      (instance? java.util.Date value)))

(defn offset-date-time?
  [value]
  (or (instance? java.time.OffsetDateTime value)
      (instance? java.sql.Timestamp value)
      (instance? java.util.Date value)))

(def malli-default-sentinel
  [:fn #(identical? % query/DEFAULT)])

(def malli-all-sentinel
  [:fn #(identical? % query/ALL)])

(def malli-limit
  [:or int? malli-all-sentinel])

(def malli-offset
  int?)

(defn- split-map-schema-form
  [schema-form error-message]
  (let [[tag maybe-opts & rest] schema-form]
    (when-not (= :map tag)
      (throw (ex-info error-message
                      {:schema schema-form})))
    (if (map? maybe-opts)
      [tag maybe-opts rest]
      [tag {} (cons maybe-opts rest)])))

(defn malli-map-all-entries-optional
  [schema-form]
  (let [[tag opts entries]
        (split-map-schema-form schema-form
                               "malli-map-all-entries-optional requires a :map schema form.")]
    (into [tag opts]
          (map (fn [entry]
                 (let [[k maybe-opts schema] entry]
                   (if (map? maybe-opts)
                     [k (assoc maybe-opts :optional true) schema]
                     [k {:optional true} maybe-opts]))))
          entries)))

(defn- malli-strip-default-sentinel
  [schema-form]
  (if (and (vector? schema-form)
           (= :or (first schema-form)))
    (let [members (vec (remove #(= % malli-default-sentinel)
                               (rest schema-form)))]
      (cond
        (empty? members)
        (throw (ex-info "malli-strip-default-sentinel removed every branch."
                        {:schema schema-form}))

        (= 1 (count members))
        (first members)

        :else
        (into [:or] members)))
    schema-form))

(defn malli-map-all-entries-strip-default-sentinel
  [schema-form]
  (let [[tag opts entries]
        (split-map-schema-form schema-form
                               "malli-map-all-entries-strip-default-sentinel requires a :map schema form.")]
    (into [tag opts]
          (map (fn [entry]
                 (let [[k maybe-opts schema] entry]
                   (if (map? maybe-opts)
                     [k maybe-opts (malli-strip-default-sentinel schema)]
                     [k (malli-strip-default-sentinel maybe-opts)]))))
          entries)))
