(ns bisql.query
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(declare render-template)
(declare render-conditionals)

(def default
  (Object.))

(defn- skip-leading-whitespace
  [s]
  (str/replace s #"^\s+" ""))

(defn- parse-declaration-block
  [sql]
  (let [pattern #"(?s)^/\*:\s*([A-Za-z0-9\-]+)?\s*\n?(.*?)\*/"
        matcher (re-matcher pattern sql)]
    (when (.find matcher)
      {:directive (some-> (.group matcher 1) keyword)
       :body (.group matcher 2)
       :rest (subs sql (.end matcher))})))

(defn- supported-declaration?
  [directive]
  (contains? #{:name :doc :meta} directive))

(defn- read-declaration-value
  [directive body]
  (case directive
    :name (str/trim body)
    :doc (str/trim body)
    :meta (try
            (edn/read-string body)
            (catch Exception ex
              (throw (ex-info "Invalid meta declaration."
                              {:directive directive
                               :body body}
                              ex))))))

(defn- extract-declarations
  [sql]
  (loop [remaining (skip-leading-whitespace sql)
         result {}]
    (if-let [{:keys [directive body rest]} (parse-declaration-block remaining)]
      (do
        (when-not (supported-declaration? directive)
          (throw (ex-info "Unsupported declaration block."
                          {:directive directive})))
        (when (contains? result directive)
          (throw (ex-info "Duplicate declaration block."
                          {:directive directive})))
        (recur (skip-leading-whitespace rest)
               (assoc result directive (read-declaration-value directive body))))
      (do
        (when-let [matcher (re-find #"/\*:\s*([A-Za-z0-9\-]+)?" remaining)]
          (let [directive (some-> (second matcher) keyword)]
            (throw (ex-info (if (supported-declaration? directive)
                              "Declaration blocks must appear at the beginning of the SQL file."
                              "Unsupported declaration block.")
                            {:directive directive}))))
        {:name (:name result)
         :doc (:doc result)
         :meta (:meta result)
         :sql-template remaining}))))

(defn- preprocess-template
  [query]
  query)

(defn- template-context
  [template]
  (cond-> {}
    (:query-name template) (assoc :query-name (:query-name template))
    (:base-path template) (assoc :base-path (:base-path template))
    (:resource-path template) (assoc :resource-path (:resource-path template))))

(defn- sql-filename?
  [filename]
  (str/ends-with? filename ".sql"))

(defn- query-name-from-filename
  [filename]
  (subs (.getName (io/file filename)) 0 (- (count (.getName (io/file filename))) 4)))

(def ^:private query-block-start-pattern
  #"(?m)^[\t ]*/\*:name(?:\s|\*/)")

(defn- query-block-start-indexes
  [sql]
  (let [matcher (re-matcher query-block-start-pattern sql)]
    (loop [indexes []]
      (if (.find matcher)
        (recur (conj indexes (.start matcher)))
        indexes))))

(defn- chunk-sql
  [sql start end]
  (skip-leading-whitespace (subs sql start end)))

(defn- parse-query-blocks
  [sql]
  (let [sql (skip-leading-whitespace sql)
        start-indexes (query-block-start-indexes sql)]
    (cond
      (str/blank? sql)
      []

      (empty? start-indexes)
      (let [{:keys [name]} (extract-declarations sql)]
        [{:name name
          :sql-template sql}])

      :else
      (let [end-indexes (concat (rest start-indexes) [(count sql)])
            prefixed-block (chunk-sql sql 0 (first start-indexes))
            query-blocks (mapv #(chunk-sql sql %1 %2)
                               start-indexes
                               end-indexes)
            block-sqls (cond-> query-blocks
                         (not (str/blank? prefixed-block))
                         (into [prefixed-block]))]
        (mapv (fn [block-sql]
                (let [{:keys [name]} (extract-declarations block-sql)]
                  {:name name
                   :sql-template block-sql}))
              block-sqls)))))

(defn- load-query-resource
  [filename base-path]
  (when-not (sql-filename? filename)
    (throw (ex-info "Query file name must end with .sql."
                    {:filename filename
                     :base-path base-path})))
  (let [query-name (query-name-from-filename filename)
        resource-path (str base-path "/" filename)
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
     :sql-template (slurp resource)}))

(defn- loaded-template
  [query-name base-path resource-path sql-template]
  {:query-name query-name
   :base-path base-path
   :resource-path resource-path
   :sql-template sql-template})

(defn load-queries
  "Loads a SQL file and returns all query templates keyed by query name."
  ([filename]
   (load-queries filename {}))
  ([filename {:keys [base-path]
              :or {base-path "sql"}}]
   (let [{:keys [query-name resource-path sql-template]}
         (load-query-resource filename base-path)]
     (try
       (let [blocks (parse-query-blocks sql-template)
             multiple? (> (count blocks) 1)]
         (when (and multiple? (some #(nil? (:name %)) blocks))
           (throw (ex-info "Multiple queries require :name declarations."
                           {:filename filename
                            :base-path base-path
                            :resource-path resource-path})))
         (reduce
          (fn [queries {:keys [name sql-template]}]
            (let [resolved-name (or name query-name)]
              (when (contains? queries resolved-name)
                (throw (ex-info "Duplicate query name."
                                {:query-name resolved-name
                                 :filename filename
                                 :base-path base-path
                                 :resource-path resource-path})))
              (assoc queries
                     resolved-name
                     (loaded-template resolved-name
                                      base-path
                                      resource-path
                                      sql-template))))
          {}
          blocks))
       (catch clojure.lang.ExceptionInfo ex
         (throw (ex-info (ex-message ex)
                         (merge {:filename filename
                                 :base-path base-path
                                 :resource-path resource-path
                                 :query-name query-name}
                                (ex-data ex))
                         ex)))))))

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

(defn- whitespace?
  [ch]
  (Character/isWhitespace ^char ch))

(defn- delimiter?
  [ch]
  (contains? #{\, \) \( \; \newline \return \tab \space} ch))

(defn- consume-sample-token
  [sql start]
  (let [length (count sql)]
    (when (>= start length)
      (throw (ex-info "Missing sample token after bind variable."
                      {:sql sql
                       :start start})))
    (let [ch (.charAt sql start)]
      (cond
        (= ch \')
        (loop [idx (inc start)]
          (when (>= idx length)
            (throw (ex-info "Unterminated SQL string literal sample."
                            {:sql sql
                             :start start})))
          (if (= (.charAt sql idx) \')
            (inc idx)
            (recur (inc idx))))

        :else
        (loop [idx start]
          (if (or (>= idx length)
                  (delimiter? (.charAt sql idx)))
            idx
            (recur (inc idx))))))))

(defn- consume-sample-collection
  [sql start]
  (let [length (count sql)]
    (when (or (>= start length)
              (not= (.charAt sql start) \())
      (throw (ex-info "Missing sample collection after bind variable."
                      {:sql sql
                       :start start})))
    (loop [idx (inc start)
           depth 1]
      (when (>= idx length)
        (throw (ex-info "Unterminated sample collection."
                        {:sql sql
                         :start start})))
      (let [ch (.charAt sql idx)]
        (cond
          (= ch \() (recur (inc idx) (inc depth))
          (= ch \)) (if (= depth 1)
                      (inc idx)
                      (recur (inc idx) (dec depth)))
          :else (recur (inc idx) depth))))))

(defn- parameter-value
  [template-params name]
  (let [value (get template-params name ::missing)]
    (when (= value ::missing)
      (throw (ex-info "Missing query parameter."
                      {:parameter name})))
    value))

(defn- default-value?
  [value]
  (identical? value default))

(defn- render-bind-variable
  [template-params name collection?]
  (let [value (parameter-value template-params name)]
    (if collection?
      (do
        (when (default-value? value)
          (throw (ex-info "DEFAULT is not allowed in collection binding."
                          {:parameter name
                           :value value})))
        (when-not (sequential? value)
          (throw (ex-info "Collection binding requires a sequential value."
                          {:parameter name
                           :value value})))
        (when (some default-value? value)
          (throw (ex-info "DEFAULT is not allowed inside collection binding."
                          {:parameter name
                           :value value})))
        (when (empty? value)
          (throw (ex-info "Collection binding does not allow empty values."
                          {:parameter name})))
        {:sql (str "("
                   (str/join ", " (repeat (count value) "?"))
                   ")")
         :params (vec value)})
      (if (default-value? value)
        {:sql "DEFAULT"
         :params []}
        {:sql "?"
         :params [value]}))))

(defn- render-literal-variable
  [template-params name]
  (let [value (parameter-value template-params name)]
    (cond
      (string? value)
      (do
        (when (str/includes? value "'")
          (throw (ex-info "Literal string values must not contain single quotes."
                          {:parameter name
                           :value value})))
        {:sql (str "'" value "'")
         :params []})

      (number? value)
      {:sql (str value)
       :params []}

      :else
      (throw (ex-info "Unsupported literal value type."
                      {:parameter name
                       :value value
                       :type (type value)})))))

(defn- render-raw-variable
  [template-params name]
  {:sql (str (parameter-value template-params name))
   :params []})

(defn- render-variable
  [template-params sigil name collection?]
  (case sigil
    "$" (render-bind-variable template-params name collection?)
    "^" (render-literal-variable template-params name)
    "!" (render-raw-variable template-params name)))

(defn- variable-context
  [name sigil collection?]
  {:parameter name
   :sigil sigil
   :collection? collection?})

(defn- truthy?
  [value]
  (not (or (nil? value) (false? value))))

(defn- eval-condition
  [name template-params]
  (truthy? (get template-params (keyword name))))

(defn- trim-leading-newline
  [s]
  (if (str/starts-with? s "\n")
    (subs s 1)
    s))

(defn- find-if-end
  [sql start]
  (loop [cursor start
         depth 1]
    (let [matcher (re-matcher #"/\*%(if|end)(?:\s+([^*]*?))?\s*\*/" sql)]
      (if-not (.find matcher cursor)
        (throw (ex-info "Unterminated conditional block."
                        {:sql sql
                         :start start}))
        (let [directive (.group matcher 1)
              next-depth (cond
                           (= directive "if") (inc depth)
                           (= directive "end") (dec depth)
                           :else depth)]
          (if (zero? next-depth)
            {:start (.start matcher)
             :end (.end matcher)}
            (recur (.end matcher) next-depth)))))))

(defn- render-conditionals
  [sql template-params]
  (let [matcher (re-matcher #"/\*%if\s+([A-Za-z0-9\-]+)\s*\*/" sql)
        out (StringBuilder.)]
    (loop [cursor 0]
      (if-not (.find matcher cursor)
        (do
          (.append out ^String (subs sql cursor))
          (str out))
        (let [if-start (.start matcher)
              if-end (.end matcher)
              expr (.group matcher 1)
              {:keys [start end]} (find-if-end sql if-end)
              body (subs sql if-end start)]
          (.append out ^String (subs sql cursor if-start))
          (when (eval-condition expr template-params)
            (let [rendered-body (render-conditionals body template-params)
                  rendered-body (if (and (pos? (.length out))
                                         (= \newline (.charAt out (dec (.length out)))))
                                  (trim-leading-newline rendered-body)
                                  rendered-body)]
              (.append out ^String rendered-body)))
          (recur end))))))

(defn- render-template
  [sql template-params]
  (let [sql (render-conditionals sql template-params)
        matcher (re-matcher #"/\*([\$\^\!])([A-Za-z0-9\-]+)\*/" sql)
        out (StringBuilder.)
        bind-params (transient [])
        length (count sql)]
    (loop [cursor 0]
      (if-not (.find matcher cursor)
        (do
          (.append out ^String (subs sql cursor))
          {:sql (str out)
           :bind-params (persistent! bind-params)})
        (let [start (.start matcher)
              end (.end matcher)
              sigil (.group matcher 1)
              name (keyword (.group matcher 2))
              sample-start end]
          (if (or (>= sample-start length)
                  (whitespace? (.charAt sql sample-start)))
            (do
              (.append out ^String (subs sql cursor end))
              (recur end))
            (let [collection? (and (= sigil "$")
                                   (= (.charAt sql sample-start) \())
                  context (variable-context name sigil collection?)
                  {:keys [sample-end rendered]}
                  (try
                    (let [sample-end (if collection?
                                       (consume-sample-collection sql sample-start)
                                       (consume-sample-token sql sample-start))
                          rendered (render-variable template-params sigil name collection?)]
                      {:sample-end sample-end
                       :rendered rendered})
                    (catch clojure.lang.ExceptionInfo ex
                      (throw (ex-info (ex-message ex)
                                      (merge context (ex-data ex))
                                      ex))))]
              (.append out ^String (subs sql cursor start))
              (.append out ^String (:sql rendered))
              (reduce conj! bind-params (:params rendered))
              (recur sample-end))))))))

(defn- postprocess-sql
  [sql]
  (str/trim sql))

(defn render-query
  "Renders a loaded template into executable SQL plus parameters.
   Initial implementation supports bind variables and collection binding."
  [template template-params]
  {:pre [(map? template) (map? template-params)]}
  (let [context (template-context template)]
    (try
      (let [; 1. Pre-process the template
            preprocessed-template (preprocess-template template)
            ; 2. Extract declarations such as /*:doc ...*/
            {:keys [doc meta sql-template]} (extract-declarations (:sql-template preprocessed-template))
            ; 3. Render the template to executable SQL
            {:keys [sql bind-params]} (render-template sql-template template-params)
            ; 4. Post-process the SQL such as trimming whitespace
            postprocessed-sql (postprocess-sql sql)]
        (merge context
               {:sql postprocessed-sql
                :params bind-params
                :doc doc
                :meta meta}))
      (catch clojure.lang.ExceptionInfo ex
        (throw (ex-info (ex-message ex)
                        (merge context (ex-data ex))
                        ex))))))
