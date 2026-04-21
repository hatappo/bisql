(ns bisql.engine
  (:require [bisql.expr :as expr]
            [clojure.string :as str]
            #?(:clj [clojure.edn :as edn]
               :cljs [cljs.reader :as reader]))
  #?(:clj (:import [java.io PushbackReader StringReader])))

(declare parse-template)
(declare renderer-plan)
(declare emit-renderer-form)
(declare evaluate-renderer-plan)
(declare parse-template-nodes)
(declare parse-variable-nodes)
(declare postprocess-sql)

(def DEFAULT
  #?(:clj (Object.)
     :cljs (js-obj)))

(def ALL
  #?(:clj (Object.)
     :cljs (js-obj)))

(defrecord LikePattern [mode value])

(defn LIKE_STARTS_WITH
  [value]
  (->LikePattern :starts-with value))

(defn LIKE_ENDS_WITH
  [value]
  (->LikePattern :ends-with value))

(defn LIKE_CONTAINS
  [value]
  (->LikePattern :contains value))

(def ^:private missing ::missing)
(def ^:private default-namespace-suffix "core")

(defn- skip-leading-whitespace
  [s]
  (str/replace s #"^\s+" ""))

(defn- regex-matcher
  [pattern s]
  #?(:clj
     (re-matcher pattern s)
     :cljs
     #js {:regex (js/RegExp. (.-source pattern)
                             (let [flags (.-flags pattern)]
                               (if (str/includes? flags "g")
                                 flags
                                 (str flags "g"))))
          :input s
          :match nil}))

(defn- regex-find
  ([matcher]
   (regex-find matcher nil))
  ([matcher cursor]
   #?(:clj
     (if (some? cursor)
        (.find matcher ^long cursor)
        (.find matcher))
      :cljs
      (let [regex (aget matcher "regex")
            input (aget matcher "input")]
        (when (some? cursor)
          (set! (.-lastIndex regex) cursor))
        (if-let [match (.exec regex input)]
          (do
            (aset matcher "match" match)
            true)
          (do
            (aset matcher "match" nil)
            false))))))

(defn- regex-group
  [matcher idx]
  #?(:clj
     (.group matcher idx)
     :cljs
     (some-> (aget matcher "match") (aget idx))))

(defn- regex-start
  [matcher]
  #?(:clj
     (.start matcher)
     :cljs
     (some-> (aget matcher "match") .-index)))

(defn- regex-end
  [matcher]
  #?(:clj
     (.end matcher)
     :cljs
     (when-let [match (aget matcher "match")]
       (+ (.-index match) (count (aget match 0))))))

(defn- parse-declaration-block
  [sql]
  (let [pattern #"(?s)^/\*:([A-Za-z0-9\-/]+)\s*\n?(.*?)\*/"
        matcher (volatile! (regex-matcher pattern sql))]
    (when (regex-find @matcher)
      {:directive (some-> (regex-group @matcher 1) keyword)
       :body (regex-group @matcher 2)
       :rest (subs sql (regex-end @matcher))})))

#?(:clj
   (def ^:private declaration-eof ::declaration-eof))

(defn- read-edn-body
  [body]
  #?(:clj
     (let [reader (PushbackReader. (StringReader. body))
           value (edn/read {:eof declaration-eof} reader)
           trailing (edn/read {:eof declaration-eof} reader)]
       (when (= value declaration-eof)
         (throw (ex-info "Empty EDN value." {})))
       (when-not (= trailing declaration-eof)
         (throw (ex-info "Trailing EDN data." {})))
       value)
     :cljs
     (let [trimmed-body (str/trim body)]
       (when (str/blank? trimmed-body)
         (throw (ex-info "Empty EDN value." {})))
       (reader/read-string trimmed-body))))

(defn- read-declaration-value
  [directive body]
  (if (= directive :doc)
    (let [trimmed-body (str/trim body)]
      (try
        (let [value (read-edn-body body)]
          (cond
            (string? value) value
            (re-find #"\s" trimmed-body) trimmed-body
            :else (str value)))
        (catch #?(:clj Exception :cljs :default) _
          trimmed-body)))
    (try
      (read-edn-body body)
      (catch #?(:clj Exception :cljs :default) ex
        (throw (ex-info "Invalid declaration value."
                        {:directive directive
                         :body body}
                        ex))))))

(defn- normalize-query-name
  [value]
  (cond
    (string? value) value
    (keyword? value) (name value)
    (symbol? value) (name value)
    :else (throw (ex-info "Query name declaration must be a string, keyword, or symbol."
                          {:directive :name
                           :value value
                           :type (type value)}))))

(defn- split-qualified-query-name
  [query-name]
  (let [segments (str/split query-name #"\.")]
    {:namespace-suffix (not-empty (vec (butlast segments)))
     :function-name (last segments)}))

(defn- qualified-query-name
  [namespace-suffix function-name]
  (str/join "." (concat namespace-suffix [function-name])))

(defn- resolve-query-location
  [context-query-name declared-query-name]
  (when-let [fallback-name (or context-query-name declared-query-name)]
    (let [fallback-parts (split-qualified-query-name fallback-name)
          declared-parts (when declared-query-name
                           (split-qualified-query-name declared-query-name))
          namespace-suffix (vec (or (:namespace-suffix declared-parts)
                                    (:namespace-suffix fallback-parts)
                                    [default-namespace-suffix]))
          function-name (or (:function-name declared-parts)
                            (:function-name fallback-parts))]
      {:query-name (qualified-query-name namespace-suffix function-name)
       :function-name function-name
       :namespace-suffix namespace-suffix})))

(defn query-location
  "Resolves query-name, function-name, and namespace-suffix from a logical query name."
  ([query-name]
   (resolve-query-location query-name nil))
  ([context-query-name declared-query-name]
   (resolve-query-location context-query-name declared-query-name)))

(defn- extract-declarations
  [sql]
  (loop [remaining (skip-leading-whitespace sql)
         result {}]
    (if-let [{:keys [directive body rest]} (parse-declaration-block remaining)]
      (do
        (when (contains? result directive)
          (throw (ex-info "Duplicate declaration block."
                          {:directive directive})))
        (recur (skip-leading-whitespace rest)
               (assoc result directive (read-declaration-value directive body))))
      (do
        (when (str/starts-with? remaining "/*:")
          (throw (ex-info "Invalid declaration block."
                          {})))
        (when (re-find #"/\*:" remaining)
          (throw (ex-info "Declaration blocks must appear at the beginning of the SQL template block."
                          {})))
        {:meta result
         :sql-template remaining}))))

(defn template-context
  [template]
  (cond-> {}
    (:query-name template) (assoc :query-name (:query-name template))
    (:base-path template) (assoc :base-path (:base-path template))
    (:resource-path template) (assoc :resource-path (:resource-path template))
    (:project-relative-path template) (assoc :project-relative-path (:project-relative-path template))
    (:source-line template) (assoc :source-line (:source-line template))))

(defn- line-number-at-index
  [s idx]
  (inc (count (filter #(= % \newline) (subs s 0 idx)))))

(def ^:private query-block-start-pattern
  #"(?m)^[\t ]*/\*:name(?:\s|\*/)")

(defn- query-block-start-indexes
  [sql]
    (let [matcher (volatile! (regex-matcher query-block-start-pattern sql))]
    (loop [indexes []]
      (if (regex-find @matcher)
        (recur (conj indexes (regex-start @matcher)))
        indexes))))

(defn- chunk-sql
  [sql start end]
  (skip-leading-whitespace (subs sql start end)))

(defn parse-query-blocks
  [sql]
  (let [trimmed-sql (skip-leading-whitespace sql)
        trim-offset (- (count sql) (count trimmed-sql))
        start-indexes (query-block-start-indexes trimmed-sql)]
    (cond
      (str/blank? trimmed-sql)
      []

      (empty? start-indexes)
      (let [{:keys [meta]} (extract-declarations trimmed-sql)]
        [{:name (some-> (:name meta) normalize-query-name)
          :sql-template trimmed-sql
          :source-line (line-number-at-index sql trim-offset)}])

      :else
      (let [end-indexes (concat (rest start-indexes) [(count trimmed-sql)])
            prefixed-block (chunk-sql trimmed-sql 0 (first start-indexes))
            query-blocks (mapv #(chunk-sql trimmed-sql %1 %2)
                               start-indexes
                               end-indexes)
            block-entries (cond-> (mapv (fn [start block-sql]
                                          {:sql-template block-sql
                                           :source-line (line-number-at-index sql (+ trim-offset start))})
                                        start-indexes
                                        query-blocks)
                            (not (str/blank? prefixed-block))
                            (into [{:sql-template prefixed-block
                                    :source-line (line-number-at-index sql trim-offset)}]))]
        (mapv (fn [{:keys [sql-template source-line]}]
                (let [{:keys [meta]} (extract-declarations sql-template)]
                  {:name (some-> (:name meta) normalize-query-name)
                   :sql-template sql-template
                   :source-line source-line}))
              block-entries)))))

(defn loaded-template
  [query-name function-name namespace-suffix base-path resource-path project-relative-path source-line sql-template]
  {:query-name query-name
   :function-name function-name
   :namespace-suffix namespace-suffix
   :base-path base-path
   :resource-path resource-path
   :project-relative-path project-relative-path
   :source-line source-line
   :sql-template sql-template})

(defn analyze-template
  "Analyzes a template and returns declaration metadata plus the declaration-free SQL template."
  [template]
  {:pre [(map? template)]}
  (let [context (template-context template)
        {:keys [meta sql-template]} (extract-declarations (:sql-template template))
        declared-query-name (some-> (:name meta) normalize-query-name)
        {:keys [query-name function-name namespace-suffix]}
        (resolve-query-location (:query-name context) declared-query-name)]
    (merge context
           {:query-name query-name
            :function-name function-name
            :namespace-suffix namespace-suffix
            :sql-template sql-template
            :meta meta})))

(defn- whitespace?
  [ch]
  (boolean (re-matches #"\s" (str ch))))

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

(defn- path-segments
  [parameter-name]
  (str/split parameter-name #"\."))

(defn- candidate-keys
  [segment]
  [(keyword segment) segment (symbol segment)])

(defn- lookup-segment
  [value segment]
  (if (associative? value)
    (reduce (fn [_ k]
              (if (contains? value k)
                (reduced (get value k))
                missing))
            missing
            (candidate-keys segment))
    missing))

(defn parameter-key
  [parameter-name]
  (keyword parameter-name))

(defn- resolved-parameter-value
  [template-params parameter-name]
  (reduce (fn [value segment]
            (if (= value missing)
              missing
              (lookup-segment value segment)))
          template-params
          (path-segments parameter-name)))

(defn parameter-value
  [template-params parameter-name]
  (let [value (resolved-parameter-value template-params parameter-name)]
    (when (= value missing)
      (throw (ex-info (str "Missing query parameter: " parameter-name)
                      {:parameter (parameter-key parameter-name)})))
    value))

(defn- default-value?
  [value]
  (identical? value DEFAULT))

(defn- all-value?
  [value]
  (identical? value ALL))

(defn like-pattern?
  [value]
  (instance? LikePattern value))

(defn- escape-like-pattern
  [s]
  (-> s
      (str/replace "\\" "\\\\")
      (str/replace "%" "\\%")
      (str/replace "_" "\\_")))

(defn render-like-pattern-value
  [value parameter-name]
  (when-not (string? (:value value))
    (throw (ex-info "LIKE helpers require a string value."
                    {:parameter (parameter-key parameter-name)
                     :value (:value value)
                     :type (type (:value value))})))
  (let [escaped (escape-like-pattern (:value value))]
    (case (:mode value)
      :starts-with (str escaped "%")
      :ends-with (str "%" escaped)
      :contains (str "%" escaped "%")
      (throw (ex-info "Unsupported LIKE helper mode."
                      {:parameter (parameter-key parameter-name)
                       :mode (:mode value)})))))

(defn- render-bind-variable
  [template-params parameter-name collection?]
  (let [value (parameter-value template-params parameter-name)]
    (if collection?
      (do
        (when (default-value? value)
          (throw (ex-info "DEFAULT is not allowed in collection binding."
                          {:parameter (parameter-key parameter-name)
                           :value value})))
        (when (all-value? value)
          (throw (ex-info "ALL is not allowed in collection binding."
                          {:parameter (parameter-key parameter-name)
                           :value value})))
        (when-not (sequential? value)
          (throw (ex-info "Collection binding requires a sequential value."
                          {:parameter (parameter-key parameter-name)
                           :value value})))
        (when (some default-value? value)
          (throw (ex-info "DEFAULT is not allowed inside collection binding."
                          {:parameter (parameter-key parameter-name)
                           :value value})))
        (when (some all-value? value)
          (throw (ex-info "ALL is not allowed inside collection binding."
                          {:parameter (parameter-key parameter-name)
                           :value value})))
        (when (some like-pattern? value)
          (throw (ex-info "LIKE helpers are not allowed inside collection binding."
                          {:parameter (parameter-key parameter-name)
                           :value value})))
        (when (empty? value)
          (throw (ex-info "Collection binding does not allow empty values."
                          {:parameter (parameter-key parameter-name)})))
        {:sql (str "("
                   (str/join ", " (repeat (count value) "?"))
                   ")")
         :params (vec value)})
      (cond
        (default-value? value)
        {:sql "DEFAULT"
         :params []}
        (all-value? value)
        {:sql "ALL"
         :params []}
        (like-pattern? value)
        {:sql "?"
         :params [(render-like-pattern-value value parameter-name)]}
        :else
        {:sql "?"
         :params [value]}))))

(defn- render-literal-variable
  [template-params parameter-name]
  (let [value (parameter-value template-params parameter-name)]
    (cond
      (string? value)
      (do
        (when (str/includes? value "'")
          (throw (ex-info "Literal string values must not contain single quotes."
                          {:parameter (parameter-key parameter-name)
                           :value value})))
        {:sql (str "'" value "'")
         :params []})

      (number? value)
      {:sql (str value)
       :params []}

      :else
      (throw (ex-info "Unsupported literal value type."
                      {:parameter (parameter-key parameter-name)
                       :value value
                       :type (type value)})))))

(defn- render-raw-variable
  [template-params parameter-name]
  {:sql (str (parameter-value template-params parameter-name))
   :params []})

(defn render-variable
  [template-params sigil parameter-name collection?]
  (case sigil
    "$" (render-bind-variable template-params parameter-name collection?)
    "^" (render-literal-variable template-params parameter-name)
    "!" (render-raw-variable template-params parameter-name)))

(defn variable-context
  [parameter-name sigil collection?]
  {:parameter (parameter-key parameter-name)
   :sigil sigil
   :collection? collection?})

(defn- eval-condition
  [expression template-params]
  (expr/evaluate
   (expr/parse expression)
   (fn [parameter-name]
     (let [value (resolved-parameter-value template-params parameter-name)]
       (if (= value missing)
         nil
         value)))))

(defn- trim-leading-newline
  [s]
  (if (str/starts-with? s "\n")
    (subs s 1)
    s))

(defn- remove-trailing-clause-keyword-from-sql
  [sql]
  (or (some->> sql
               (re-matches #"(?s)(.*(?:^|\n))[ \t]*(WHERE|HAVING)[ \t\r\n]*$")
               second)
      (some->> sql
               (re-matches #"(?s)(.*?)(?:WHERE|HAVING)[ \t\r\n]*$")
               second)
      sql))

(defn- normalize-sql-for-context
  [current-sql fragment-sql]
  (if (and (seq current-sql)
           (str/ends-with? current-sql "\n"))
    (trim-leading-newline fragment-sql)
    fragment-sql))

(defn remove-trailing-clause-keyword
  [out]
  (let [current (str out)
        updated (remove-trailing-clause-keyword-from-sql current)]
    (when-not (= current updated)
      #?(:clj
         (.setLength ^StringBuilder out 0)
         :cljs
         (.setLength ^js out 0))
      #?(:clj
         (.append ^StringBuilder out ^String updated)
         :cljs
         (.append ^js out updated)))))

(defn- trailing-set-clause-sql?
  [sql]
  (boolean
   (or (re-matches #"(?s).*(?:^|\n)[ \t]*SET[ \t\r\n]*$" sql)
       (re-matches #"(?s).*SET[ \t\r\n]*$" sql))))

(defn trailing-set-clause?
  [out]
  (trailing-set-clause-sql? (str out)))

(defn- trailing-values-clause-sql?
  [sql]
  (boolean
   (or (re-matches #"(?s).*(?:^|\n)[ \t]*VALUES[ \t\r\n]*$" sql)
       (re-matches #"(?s).*VALUES[ \t\r\n]*$" sql))))

(defn trailing-values-clause?
  [out]
  (trailing-values-clause-sql? (str out)))

(def ^:private clause-context-pattern
  #"(?is)\b(WHERE|HAVING|SET|VALUES|LIMIT|OFFSET)\b")

(def ^:private statement-kind-pattern
  #"(?is)^\s*(SELECT|INSERT|UPDATE|DELETE)\b")

(defn- keyword->context
  [s]
  (-> s str/lower-case keyword))

(defn- statement-kind-from-sql
  [sql]
  (some->> (re-find statement-kind-pattern sql)
           second
           keyword->context))

(defn- update-context-from-sql
  [current-context sql]
  (let [matches (re-seq clause-context-pattern sql)]
    (if-let [matched (last matches)]
      (keyword->context (second matched))
      current-context)))

(defn- annotate-node-contexts
  [nodes statement-kind initial-clause-context]
  (loop [remaining nodes
         clause-context initial-clause-context
         annotated []]
    (if-let [node (first remaining)]
      (let [remaining (rest remaining)]
        (case (:op node)
          :text
          (let [annotated-node (assoc node
                                      :context clause-context
                                      :statement-kind statement-kind)
                next-context (update-context-from-sql clause-context (:sql node))]
            (recur remaining next-context (conj annotated annotated-node)))

          :variable
          (recur remaining clause-context
                 (conj annotated (assoc node
                                        :context clause-context
                                        :statement-kind statement-kind)))

          :if
          (let [annotated-node (assoc node
                                      :context clause-context
                                      :statement-kind statement-kind
                                      :branches (mapv (fn [{:keys [expr body]}]
                                                        {:expr expr
                                                         :body (annotate-node-contexts body statement-kind clause-context)})
                                                      (:branches node)))]
            (recur remaining clause-context (conj annotated annotated-node)))

          :for
          (let [annotated-node (assoc node
                                      :context clause-context
                                      :statement-kind statement-kind
                                      :body (annotate-node-contexts (:body node) statement-kind clause-context))]
            (recur remaining clause-context (conj annotated annotated-node)))))
      annotated)))

(defn- parse-inline-fragment-args
  [raw-args]
  (let [trimmed (str/trim (or raw-args ""))]
    (if-let [[_ condition fragment] (re-matches #"(?s)^(.*?)(?:\s*=>\s*(.+))?$" trimmed)]
      {:arg1 (some-> condition str/trim not-empty)
       :arg4 (some-> fragment str/trim not-empty)}
      {:arg1 nil
       :arg4 nil})))

(defn- parse-for-args
  [raw-args]
  (let [trimmed (str/trim (or raw-args ""))]
    (if-let [[_ item-name collection-name separator]
             (re-matches #"(?s)^([A-Za-z0-9\-\.]+)\s+in\s+([A-Za-z0-9\-\.]+)(?:\s+separating\s+(.+?))?$"
                         trimmed)]
      {:arg1 item-name
       :arg2 collection-name
       :arg3 separator}
      (throw (ex-info "Invalid for directive."
                      {:directive "for"
                       :args raw-args})))))

(defn- parse-control-directive
  [matcher]
  (let [directive (regex-group matcher 1)
        raw-args (regex-group matcher 2)
        parsed-args (case directive
                      ("if" "elseif") (parse-inline-fragment-args raw-args)
                      "else" {:arg4 (some->> raw-args
                                             str/trim
                                             (re-matches #"(?s)^=>\s*(.+)$")
                                             second
                                             str/trim
                                             not-empty)}
                      "for" (parse-for-args raw-args)
                      {})]
    (merge {:directive directive
            :start (regex-start matcher)
            :end (regex-end matcher)}
           parsed-args)))

(def ^:private control-directive-pattern
  #"/\*%(if|elseif|else|for|end)(.*?)\*/")

(defn- append-conditional-branch
  [branches current-expr branch-start branch-end sql inline-body]
  (let [block-body (subs sql branch-start branch-end)
        body (if inline-body
               (do
                 (when-not (str/blank? block-body)
                   (throw (ex-info "Conditional branch cannot mix inline fragments with block body content."
                                   {:sql sql
                                    :start branch-start
                                    :end branch-end})))
                 inline-body)
               block-body)]
    (conj branches
          {:expr current-expr
           :body body})))

(defn- parse-conditional-branches
  [sql if-start if-end initial-expr]
  (loop [cursor if-end
         depth 1
         branches []
         current-expr initial-expr
         branch-start if-end
         current-inline-body nil
         else-seen? false]
    (let [matcher (volatile! (regex-matcher control-directive-pattern sql))]
      (if-not (regex-find @matcher cursor)
        (throw (ex-info "Unterminated conditional block."
                        {:sql sql
                         :start if-start}))
        (let [{:keys [directive start end arg1 arg4]} (parse-control-directive @matcher)]
          (cond
            (> depth 1)
            (recur end
                   (case directive
                     ("if" "for") (inc depth)
                     "end" (dec depth)
                     depth)
                   branches
                   current-expr
                   branch-start
                   current-inline-body
                   else-seen?)

            (contains? #{"if" "for"} directive)
            (recur end
                   (inc depth)
                   branches
                   current-expr
                   branch-start
                   current-inline-body
                   else-seen?)

            (= directive "elseif")
            (do
              (when else-seen?
                (throw (ex-info "Conditional block cannot contain elseif after else."
                                {:sql sql
                                 :start start})))
              (recur end
                     depth
                     (append-conditional-branch branches current-expr branch-start start sql current-inline-body)
                     arg1
                     end
                     (some-> arg4 str/trim not-empty)
                     else-seen?))

            (= directive "else")
            (do
              (when else-seen?
                (throw (ex-info "Conditional block cannot contain multiple else blocks."
                                {:sql sql
                                 :start start})))
              (recur end
                     depth
                     (append-conditional-branch branches current-expr branch-start start sql current-inline-body)
                     nil
                     end
                     (some-> arg4 str/trim not-empty)
                     true))

            (= directive "end")
            {:branches (append-conditional-branch branches current-expr branch-start start sql current-inline-body)
             :end end}))))))

(defn- parse-for-block
  [sql for-start for-end item-name collection-name]
  (loop [cursor for-end
         depth 1]
    (let [matcher (volatile! (regex-matcher control-directive-pattern sql))]
      (if-not (regex-find @matcher cursor)
        (throw (ex-info "Unterminated for block."
                        {:sql sql
                         :start for-start}))
        (let [{:keys [directive start end]} (parse-control-directive @matcher)]
          (cond
            (= directive "for")
            (if (= depth 1)
              (throw (ex-info "Nested for blocks are not supported."
                              {:sql sql
                               :start start
                               :item item-name
                               :collection collection-name}))
              (recur end (inc depth)))

            (= directive "if")
            (recur end (inc depth))

            (= directive "end")
            (if (= depth 1)
              {:body (subs sql for-end start)
               :end end}
              (recur end (dec depth)))

            :else
            (recur end depth)))))))

(defn- parse-template-nodes
  [segment]
  (let [matcher (volatile! (regex-matcher #"/\*%(if|for)(.*?)\*/" segment))]
    (loop [cursor 0
           nodes []]
      (if-not (regex-find @matcher cursor)
        (into nodes (parse-variable-nodes (subs segment cursor)))
        (let [{:keys [directive arg1 arg2 arg3 start end]} (parse-control-directive @matcher)
              block-start start
              block-end end
              nodes (into nodes (parse-variable-nodes (subs segment cursor block-start)))]
          (if (= directive "if")
            (let [{:keys [branches end]}
                  (parse-conditional-branches segment block-start block-end arg1)
                  node {:op :if
                        :branches (mapv (fn [{:keys [expr body]}]
                                          {:expr expr
                                           :body (parse-template-nodes body)})
                                        branches)}]
              (recur end (conj nodes node)))
            (let [{:keys [body end]}
                  (parse-for-block segment block-start block-end arg1 arg2)
                  node {:op :for
                        :item-name arg1
                        :collection-name arg2
                        :separator (some-> arg3 str/trim not-empty)
                        :body (parse-template-nodes body)}]
              (recur end (conj nodes node)))))))))

(defn parse-template
  "Parses a declaration-free SQL template string into an intermediate representation."
  [sql]
  {:pre [(string? sql)]}
  (let [statement-kind (statement-kind-from-sql sql)]
    {:op :template
     :statement-kind statement-kind
     :nodes (annotate-node-contexts (parse-template-nodes sql) statement-kind nil)}))

(defn- renderer-plan-step
  [node]
  (case (:op node)
    :text
    {:op :append-text
     :sql (:sql node)
     :context (:context node)
     :statement-kind (:statement-kind node)}

    :variable
    {:op :append-variable
     :sigil (:sigil node)
     :parameter-name (:parameter-name node)
     :collection? (:collection? node)
     :context (:context node)
     :statement-kind (:statement-kind node)}

    :if
    {:op :branch
     :context (:context node)
     :statement-kind (:statement-kind node)
     :branches (mapv (fn [{:keys [expr body]}]
                       {:expr expr
                        :steps (mapv renderer-plan-step body)})
                     (:branches node))}

    :for
    {:op :for-each
     :item-name (:item-name node)
     :collection-name (:collection-name node)
     :separator (:separator node)
     :context (:context node)
     :statement-kind (:statement-kind node)
     :steps (mapv renderer-plan-step (:body node))}))

(defn renderer-plan
  "Builds an execution-oriented renderer plan from a parsed template."
  [parsed-template]
  {:pre [(map? parsed-template)]}
  {:op :renderer-plan
   :statement-kind (:statement-kind parsed-template)
   :steps (mapv renderer-plan-step (:nodes parsed-template))})

(defn selected-conditional-branch
  [branches template-params]
  (some (fn [{:keys [expr] :as branch}]
          (when (or (nil? expr)
                    (eval-condition expr template-params))
            branch))
        branches))

(defn- parse-variable-nodes
  [sql]
  (let [matcher (volatile! (regex-matcher #"/\*([\$\^\!])([A-Za-z0-9\-\.]+)\*/" sql))
        length (count sql)]
    (loop [cursor 0
           nodes []]
      (if-not (regex-find @matcher cursor)
        (cond-> nodes
          (< cursor length)
          (conj {:op :text
                 :sql (subs sql cursor)}))
        (let [start (regex-start @matcher)
              end (regex-end @matcher)
              sigil (regex-group @matcher 1)
              parameter-name (regex-group @matcher 2)
              sample-start end
              nodes (cond-> nodes
                      (< cursor start)
                      (conj {:op :text
                             :sql (subs sql cursor start)}))]
          (if (or (>= sample-start length)
                  (whitespace? (.charAt sql sample-start)))
            (recur end
                   (conj nodes
                         {:op :text
                          :sql (subs sql start end)}))
            (let [collection? (and (= sigil "$")
                                   (= (.charAt sql sample-start) \())
                  sample-end (cond
                               (= sigil "!")
                               (consume-sample-token sql sample-start)
                               collection? (consume-sample-collection sql sample-start)
                               :else (consume-sample-token sql sample-start))]
              (recur sample-end
                     (conj nodes
                           {:op :variable
                            :sigil sigil
                            :parameter-name parameter-name
                            :collection? collection?})))))))))

(defn append-fragment!
  [out accumulated-bind-params {:keys [sql bind-params]}]
  (.append out ^String sql)
  (reduce conj! accumulated-bind-params bind-params))

(defn normalize-fragment-for-context
  [out fragment]
  (let [sql (:sql fragment)
        sql (if (and (pos? (.length out))
                     (= \newline (.charAt out (dec (.length out)))))
              (trim-leading-newline sql)
              sql)]
    (assoc fragment :sql sql)))

(defn consume-leading-conditional-operator-from-text
  [sql]
  (when-let [match (re-find #"(?is)\A([ \t\r\n]*)(AND|OR)([ \t\r\n]*)" sql)]
    (let [[matched _ _ _] match]
      (subs sql (count matched)))))

(defn- empty-render-state
  []
  {:sql ""
   :bind-params []})

(defn- append-render-fragment
  [state {:keys [sql bind-params]}]
  (-> state
      (update :sql str sql)
      (update :bind-params into bind-params)))

(defn- render-plan-result
  [state]
  {:sql (:sql state)
   :bind-params (:bind-params state)})

(defn- bind-fragment
  [{:keys [sql params]}]
  {:sql sql
   :bind-params params})

(declare evaluate-render-plan-steps)

(defn- evaluate-append-text-step
  [step state skip-leading-operator?]
  (let [sql (:sql step)
        [state sql] (if skip-leading-operator?
                      (if-let [trimmed (consume-leading-conditional-operator-from-text sql)]
                        [state trimmed]
                        [(update state :sql remove-trailing-clause-keyword-from-sql) sql])
                      [state sql])]
    [(update state :sql str sql) false]))

(defn- evaluate-append-variable-step
  [step state template-params skip-leading-operator?]
  (let [parameter-name (:parameter-name step)
        sigil (:sigil step)
        collection? (:collection? step)
        context (variable-context parameter-name sigil collection?)
        state (if skip-leading-operator?
                (update state :sql remove-trailing-clause-keyword-from-sql)
                state)]
    (try
      [(append-render-fragment state
                               (bind-fragment
                                (render-variable template-params sigil parameter-name collection?)))
       false]
      (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) ex
        (throw (ex-info (ex-message ex)
                        (merge context (ex-data ex))
                        ex))))))

(defn- evaluate-branch-step
  [step state template-params skip-leading-operator?]
  (let [state (if skip-leading-operator?
                (update state :sql remove-trailing-clause-keyword-from-sql)
                state)]
    (if-let [selected-branch (selected-conditional-branch (:branches step) template-params)]
      (let [body-result (evaluate-render-plan-steps (:steps selected-branch)
                                                    template-params
                                                    (empty-render-state))
            normalized-fragment {:sql (normalize-sql-for-context (:sql state)
                                                                 (:sql body-result))
                                 :bind-params (:bind-params body-result)}]
        [(append-render-fragment state normalized-fragment) false])
      [state true])))

(defn- evaluate-for-each-step
  [step state template-params skip-leading-operator?]
  (let [collection-name (:collection-name step)
        item-name (:item-name step)
        separator (:separator step)
        items (parameter-value template-params collection-name)]
    (when-not (sequential? items)
      (throw (ex-info "For block requires a sequential value."
                      {:parameter (parameter-key collection-name)
                       :value items})))
    (if (seq items)
      [(reduce (fn [current-state [idx item]]
                 (let [body-params (assoc template-params (keyword item-name) item)
                       body-result (evaluate-render-plan-steps (:steps step)
                                                              body-params
                                                              (empty-render-state))
                       fragment-sql (cond-> (:sql body-result)
                                      separator
                                      (str/replace #"\r?\n$" ""))
                       fragment-sql (normalize-sql-for-context (:sql current-state) fragment-sql)
                       current-state (cond-> current-state
                                       (and separator (pos? idx))
                                       (update :sql str separator))]
                   (append-render-fragment current-state
                                           {:sql fragment-sql
                                            :bind-params (:bind-params body-result)})))
               state
               (map-indexed vector items))
       false]
      (do
        (when (and (not skip-leading-operator?)
                   (trailing-set-clause-sql? (:sql state)))
          (throw (ex-info "Empty for block is not allowed in SET clause."
                          {:parameter (parameter-key collection-name)
                           :item (keyword item-name)})))
        (when (and (not skip-leading-operator?)
                   (trailing-values-clause-sql? (:sql state)))
          (throw (ex-info "Empty for block is not allowed in VALUES clause."
                          {:parameter (parameter-key collection-name)
                           :item (keyword item-name)})))
        [state true]))))

(defn- evaluate-render-plan-step
  [step state template-params skip-leading-operator?]
  (case (:op step)
    :append-text
    (evaluate-append-text-step step state skip-leading-operator?)

    :append-variable
    (evaluate-append-variable-step step state template-params skip-leading-operator?)

    :branch
    (evaluate-branch-step step state template-params skip-leading-operator?)

    :for-each
    (evaluate-for-each-step step state template-params skip-leading-operator?)))

(defn- evaluate-render-plan-steps
  [steps template-params initial-state]
  (loop [remaining steps
         state initial-state
         skip-leading-operator? false]
    (if-let [step (first remaining)]
      (let [[next-state next-skip-leading-operator?]
            (evaluate-render-plan-step step state template-params skip-leading-operator?)]
        (recur (rest remaining) next-state next-skip-leading-operator?))
      (cond-> state
        skip-leading-operator?
        (update :sql remove-trailing-clause-keyword-from-sql)))))

(defn evaluate-renderer-plan
  "Evaluates a renderer plan and returns rendered SQL plus bind parameters."
  [plan template-params]
  {:pre [(map? plan) (map? template-params)]}
  (-> (evaluate-render-plan-steps (:steps plan) template-params (empty-render-state))
      render-plan-result))

#?(:clj
   (defn compile-renderer
     "Compiles a parsed template into a reusable renderer function."
     [parsed-template]
     {:pre [(map? parsed-template)]}
     (eval (emit-renderer-form parsed-template)))
   :cljs
   (defn compile-renderer
     "Builds a reusable renderer function from a parsed template via the renderer-plan interpreter."
     [parsed-template]
     {:pre [(map? parsed-template)]}
     (let [plan (renderer-plan parsed-template)]
       (fn [template-params]
         (evaluate-renderer-plan plan template-params)))))

(declare emit-sequential-render-body-form)
(declare emit-render-plan-form)

(defn- emit-append-text-step-form
  [step out-sym skip-leading-operator-sym]
  (let [sql (:sql step)]
    `(let [sql# ~sql
           sql# (if ~skip-leading-operator-sym
                  (if-let [trimmed# (~(var consume-leading-conditional-operator-from-text) sql#)]
                    trimmed#
                    (do
                      (~(var remove-trailing-clause-keyword) ~out-sym)
                      sql#))
                  sql#)]
       (.append ~out-sym ^String sql#)
       false)))

(defn- emit-bind-variable-step-form
  [parameter-name collection? out-sym bind-params-sym params-sym]
  (if collection?
    `(let [value# (~(var parameter-value) ~params-sym ~parameter-name)]
       (when (identical? value# DEFAULT)
         (throw (ex-info "DEFAULT is not allowed in collection binding."
                         {:parameter (~(var parameter-key) ~parameter-name)
                          :value value#})))
       (when (identical? value# ALL)
         (throw (ex-info "ALL is not allowed in collection binding."
                         {:parameter (~(var parameter-key) ~parameter-name)
                          :value value#})))
       (when-not (sequential? value#)
         (throw (ex-info "Collection binding requires a sequential value."
                         {:parameter (~(var parameter-key) ~parameter-name)
                          :value value#})))
       (when (some #(identical? % DEFAULT) value#)
         (throw (ex-info "DEFAULT is not allowed inside collection binding."
                         {:parameter (~(var parameter-key) ~parameter-name)
                          :value value#})))
       (when (some #(identical? % ALL) value#)
         (throw (ex-info "ALL is not allowed inside collection binding."
                         {:parameter (~(var parameter-key) ~parameter-name)
                          :value value#})))
       (when (some #(~(var like-pattern?) %) value#)
         (throw (ex-info "LIKE helpers are not allowed inside collection binding."
                         {:parameter (~(var parameter-key) ~parameter-name)
                          :value value#})))
       (when (empty? value#)
         (throw (ex-info "Collection binding does not allow empty values."
                         {:parameter (~(var parameter-key) ~parameter-name)})))
       (.append ~out-sym "(")
       (.append ~out-sym ^String (str/join ", " (repeat (count value#) "?")))
       (.append ~out-sym ")")
       (reduce conj! ~bind-params-sym value#)
       false)
    `(let [value# (~(var parameter-value) ~params-sym ~parameter-name)]
       (cond
         (identical? value# DEFAULT)
         (do
           (.append ~out-sym "DEFAULT")
           false)

         (identical? value# ALL)
         (do
           (.append ~out-sym "ALL")
           false)

         (~(var like-pattern?) value#)
         (do
           (.append ~out-sym "?")
           (conj! ~bind-params-sym (~(var render-like-pattern-value) value# ~parameter-name))
           false)

         :else
         (do
           (.append ~out-sym "?")
           (conj! ~bind-params-sym value#)
           false)))))

(defn- emit-literal-variable-step-form
  [parameter-name out-sym params-sym]
  `(let [value# (~(var parameter-value) ~params-sym ~parameter-name)]
     (cond
       (string? value#)
       (do
         (when (str/includes? value# "'")
           (throw (ex-info "Literal string values must not contain single quotes."
                           {:parameter (~(var parameter-key) ~parameter-name)
                            :value value#})))
         (.append ~out-sym ^String (str "'" value# "'"))
         false)

       (number? value#)
       (do
         (.append ~out-sym ^String (str value#))
         false)

       :else
       (throw (ex-info "Unsupported literal value type."
                       {:parameter (~(var parameter-key) ~parameter-name)
                        :value value#
                        :type (type value#)})))))

(defn- emit-raw-variable-step-form
  [parameter-name out-sym params-sym]
  `(do
     (.append ~out-sym ^String (str (~(var parameter-value) ~params-sym ~parameter-name)))
     false))

(defn- emit-append-variable-step-form
  [step out-sym bind-params-sym params-sym skip-leading-operator-sym]
  (let [parameter-name (:parameter-name step)
        sigil (:sigil step)
        collection? (:collection? step)
        context (variable-context parameter-name sigil collection?)]
    `(do
       (when ~skip-leading-operator-sym
         (~(var remove-trailing-clause-keyword) ~out-sym))
       (try
         ~(case sigil
            "$" (emit-bind-variable-step-form parameter-name collection? out-sym bind-params-sym params-sym)
            "^" (emit-literal-variable-step-form parameter-name out-sym params-sym)
            "!" (emit-raw-variable-step-form parameter-name out-sym params-sym))
         (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) ex#
           (throw (ex-info (ex-message ex#)
                           (merge ~context (ex-data ex#))
                           ex#)))))))

(defn- emit-branch-body-fn-form
  [steps params-sym]
  (let [body-out-sym (gensym "if_body_out__")
        body-bind-params-sym (gensym "if_body_bind_params__")
        body-params-sym (gensym "if_body_params__")
        body-form (emit-sequential-render-body-form steps
                                                    body-out-sym
                                                    body-bind-params-sym
                                                    body-params-sym)]
    `(fn []
       (let [~body-out-sym (StringBuilder.)
             ~body-bind-params-sym (transient [])
             ~body-params-sym ~params-sym]
         ~body-form
         {:sql (str ~body-out-sym)
          :bind-params (persistent! ~body-bind-params-sym)}))))

(defn- emit-branch-step-form
  [step out-sym bind-params-sym params-sym skip-leading-operator-sym]
  (let [compiled-branches
        (mapv (fn [{:keys [expr steps]}]
                `{:expr ~expr
                  :body ~(emit-branch-body-fn-form steps params-sym)})
              (:branches step))]
    `(do
       (when ~skip-leading-operator-sym
         (~(var remove-trailing-clause-keyword) ~out-sym))
       (if-let [selected-branch# (~(var selected-conditional-branch) ~(vec compiled-branches) ~params-sym)]
         (let [body-fn# (:body selected-branch#)]
           (~(var append-fragment!)
            ~out-sym
            ~bind-params-sym
            (~(var normalize-fragment-for-context)
             ~out-sym
             (body-fn#)))
           false)
         true))))

(defn- emit-for-each-step-form
  [step out-sym bind-params-sym params-sym skip-leading-operator-sym]
  (let [collection-name (:collection-name step)
        item-name (:item-name step)
        separator (:separator step)
        items-sym (gensym "items__")
        idx-sym (gensym "idx__")
        item-sym (gensym "item__")
        body-out-sym (gensym "body_out__")
        body-bind-params-sym (gensym "body_bind_params__")
        body-params-sym (gensym "body_params__")
        fragment-sql-sym (gensym "fragment_sql__")
        body-form (emit-sequential-render-body-form (:steps step)
                                                    body-out-sym
                                                    body-bind-params-sym
                                                    body-params-sym)]
    `(let [~items-sym (~(var parameter-value) ~params-sym ~collection-name)]
       (when-not (sequential? ~items-sym)
         (throw (ex-info "For block requires a sequential value."
                         {:parameter (~(var parameter-key) ~collection-name)
                          :value ~items-sym})))
       (if (seq ~items-sym)
         (do
           (doseq [[~idx-sym ~item-sym] (map-indexed vector ~items-sym)]
             (let [~body-out-sym (StringBuilder.)
                   ~body-bind-params-sym (transient [])
                   ~body-params-sym (assoc ~params-sym (keyword ~item-name) ~item-sym)]
               ~body-form
               (let [~fragment-sql-sym (str ~body-out-sym)
                     ~fragment-sql-sym ~(if separator
                                          `(str/replace ~fragment-sql-sym #"\r?\n$" "")
                                          fragment-sql-sym)
                     ~fragment-sql-sym (:sql (~(var normalize-fragment-for-context)
                                          ~out-sym
                                          {:sql ~fragment-sql-sym
                                           :bind-params []}))]
                 ~@(when separator
                     [`(when (pos? ~idx-sym)
                         (.append ~out-sym ^String ~separator))])
                 (.append ~out-sym ^String ~fragment-sql-sym)
                 (reduce conj! ~bind-params-sym (persistent! ~body-bind-params-sym)))))
           false)
         (do
           (when (and (not ~skip-leading-operator-sym)
                      (~(var trailing-set-clause?) ~out-sym))
             (throw (ex-info "Empty for block is not allowed in SET clause."
                             {:parameter (~(var parameter-key) ~collection-name)
                              :item (keyword ~item-name)})))
           (when (and (not ~skip-leading-operator-sym)
                      (~(var trailing-values-clause?) ~out-sym))
             (throw (ex-info "Empty for block is not allowed in VALUES clause."
                             {:parameter (~(var parameter-key) ~collection-name)
                              :item (keyword ~item-name)})))
           true)))))

(defn- emit-render-plan-step-form
  [step out-sym bind-params-sym params-sym skip-leading-operator-sym]
  (case (:op step)
    :append-text
    (emit-append-text-step-form step out-sym skip-leading-operator-sym)

    :append-variable
    (emit-append-variable-step-form step out-sym bind-params-sym params-sym skip-leading-operator-sym)

    :branch
    (emit-branch-step-form step out-sym bind-params-sym params-sym skip-leading-operator-sym)

    :for-each
    (emit-for-each-step-form step out-sym bind-params-sym params-sym skip-leading-operator-sym)))

(defn- emit-sequential-render-form
  [steps out-sym bind-params-sym params-sym]
  (let [initial-skip-sym (gensym "skip__")]
    (loop [remaining steps
           current-skip-sym initial-skip-sym
           bindings [initial-skip-sym false]]
      (if-let [step (first remaining)]
        (let [next-skip-sym (gensym "skip__")
              step-form (emit-render-plan-step-form step
                                                    out-sym
                                                    bind-params-sym
                                                    params-sym
                                                    current-skip-sym)]
          (recur (rest remaining)
                 next-skip-sym
                 (conj bindings next-skip-sym step-form)))
        {:bindings bindings
         :final-skip-sym current-skip-sym}))))

(defn- emit-sequential-render-body-form
  [steps out-sym bind-params-sym params-sym]
  (let [{:keys [bindings final-skip-sym]}
        (emit-sequential-render-form steps out-sym bind-params-sym params-sym)]
    `(let [~@bindings]
       (when ~final-skip-sym
         (~(var remove-trailing-clause-keyword) ~out-sym)))))

(defn- emit-render-plan-form
  [plan]
  (let [steps (:steps plan)
        out-sym (gensym "out__")
        bind-params-sym (gensym "bind_params__")
        params-sym (gensym "params__")
        {:keys [bindings final-skip-sym]}
        (emit-sequential-render-form steps out-sym bind-params-sym params-sym)]
    `(fn [~params-sym]
       (let [~out-sym (StringBuilder.)
             ~bind-params-sym (transient [])
             ~@bindings]
         (when ~final-skip-sym
           (~(var remove-trailing-clause-keyword) ~out-sym))
         {:sql (str ~out-sym)
          :bind-params (persistent! ~bind-params-sym)}))))

(defn emit-renderer-form
  "Emits a reusable renderer function form from a parsed template."
  [parsed-template]
  {:pre [(map? parsed-template)]}
  (emit-render-plan-form (renderer-plan parsed-template)))

(defn evaluate-renderer
  "Evaluates a parsed template and returns rendered SQL plus bind parameters."
  [parsed-template template-params]
  {:pre [(map? parsed-template) (map? template-params)]}
  ((compile-renderer parsed-template) template-params))

(defn render-compiled-query
  "Renders an already analyzed template with a precompiled renderer."
  [template renderer template-params]
  {:pre [(map? template) (fn? renderer) (map? template-params)]}
  (let [context (template-context template)]
    (try
      (let [{:keys [query-name meta]} template
            {:keys [sql bind-params]} (renderer template-params)
            postprocessed-sql (postprocess-sql sql)]
        (merge (cond-> context
                 query-name (assoc :query-name query-name))
               {:sql postprocessed-sql
                :params bind-params
                :meta meta}))
      (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) ex
        (throw (ex-info (ex-message ex)
                        (merge context (ex-data ex))
                        ex))))))

(defn- postprocess-sql
  [sql]
  (str/trim sql))

(defn render-query
  "Renders a loaded template into executable SQL plus parameters."
  [template template-params]
  {:pre [(map? template) (map? template-params)]}
  (let [context (template-context template)]
    (try
      (let [analyzed-template (analyze-template template)
            renderer (compile-renderer (parse-template (:sql-template analyzed-template)))]
        (render-compiled-query analyzed-template renderer template-params))
      (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) ex
        (throw (ex-info (ex-message ex)
                        (merge context (ex-data ex))
                        ex))))))
