(ns bisql.validation
  (:require [malli.core :as m]
            [malli.error :as me]
            [clojure.walk :as walk]))

(def ^:private valid-validation-modes
  #{:strict :when-present :off})

(def ^:dynamic *bisql-malli-validation-mode*
  {:in :off
   :out :off})

(defn- normalize-phase-mode
  [mode]
  (if (contains? valid-validation-modes mode)
    mode
    (throw (ex-info "Unsupported validation mode."
                    {:mode mode
                     :valid-modes valid-validation-modes}))))

(defn- normalize-validation-mode
  [mode]
  (cond
    (keyword? mode)
    {:in (normalize-phase-mode mode)
     :out (normalize-phase-mode mode)}

    (map? mode)
    {:in (normalize-phase-mode (or (:in mode) :off))
     :out (normalize-phase-mode (or (:out mode) :off))}

    :else
    (throw (ex-info "Validation mode must be a keyword or a map."
                    {:mode mode
                     :type (type mode)}))))

(defn validation-mode
  []
  (normalize-validation-mode *bisql-malli-validation-mode*))

(defn set-malli-validation-mode!
  [mode]
  (let [normalized-mode (normalize-validation-mode mode)]
    (alter-var-root #'*bisql-malli-validation-mode*
                    (constantly normalized-mode))
    normalized-mode))

(defn phase-mode
  [phase]
  (get (validation-mode) phase))

(defn- phase-label
  [phase]
  (case phase
    :in "input"
    :out "output"
    (name phase)))

(defn- validation-failure-message
  [phase query-name humanized]
  (str "Malli " (phase-label phase) " validation failed"
       (when query-name
         (str " for " query-name))
       "."
       (when humanized
         (str " " (pr-str humanized)))))

(defn- missing-schema-message
  [phase query-name]
  (str "Required Malli " (phase-label phase) " schema metadata is missing"
       (when query-name
         (str " for " query-name))
       "."))

(defn- resolve-schema-symbol
  [sym]
  (let [resolved-var (if-let [ns-name (namespace sym)]
                       (let [ns-sym (symbol ns-name)
                             _ (require ns-sym)
                             target-ns (find-ns ns-sym)]
                         (some-> target-ns
                                 (ns-resolve (symbol (name sym)))))
                       (ns-resolve *ns* sym))]
    (if-let [v resolved-var]
      (let [value (var-get v)]
        (if (or (vector? value)
                (list? value)
                (map? value))
          value
          sym))
      sym)))

(defn- realize-schema-form
  [schema-form]
  (walk/postwalk
   (fn [node]
     (if (symbol? node)
       (resolve-schema-symbol node)
       node))
   schema-form))

(def ^:private realized-schema-form
  (memoize realize-schema-form))

(defn schema-for-phase
  [queryish phase]
  (let [schema-key (case phase
                     :in :malli/in
                     :out :malli/out)]
    (some-> (meta queryish) schema-key)))

(defn validate-query-data!
  [phase queryish value]
  (let [mode (phase-mode phase)
        query-name (some-> (meta queryish) :query-name)
        schema-form (schema-for-phase queryish phase)]
    (case mode
      :off
      nil

      :when-present
      (when schema-form
        (let [schema (realized-schema-form schema-form)]
          (when-not (m/validate schema value)
            (let [explanation (m/explain schema value)
                  humanized (me/humanize explanation)]
              (throw (ex-info (validation-failure-message phase query-name humanized)
                              {:phase phase
                               :query-name query-name
                               :schema-form schema-form
                               :value value
                               :explanation explanation
                               :humanized humanized}))))))

      :strict
      (if-not schema-form
        (throw (ex-info (missing-schema-message phase query-name)
                        {:phase phase
                         :query-name query-name
                         :validation-mode mode}))
        (let [schema (realized-schema-form schema-form)]
          (when-not (m/validate schema value)
            (let [explanation (m/explain schema value)
                  humanized (me/humanize explanation)]
              (throw (ex-info (validation-failure-message phase query-name humanized)
                              {:phase phase
                               :query-name query-name
                               :schema-form schema-form
                               :value value
                               :explanation explanation
                               :humanized humanized})))))))))
