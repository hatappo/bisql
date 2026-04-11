(ns bisql.query
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io PushbackReader StringReader]))

(declare parse-template)
(declare emit-ir-form)
(declare compile-ir)
(declare evaluate-ir)
(declare parse-template-nodes)
(declare parse-variable-nodes)
(declare postprocess-sql)

(def default
  (Object.))

(def ALL
  (Object.))

(def ^:private missing ::missing)
(def ^:private default-namespace-suffix "core")

(defn- skip-leading-whitespace
  [s]
  (str/replace s #"^\s+" ""))

(defn- parse-declaration-block
  [sql]
  (let [pattern #"(?s)^/\*:([A-Za-z0-9\-]+)\s*\n?(.*?)\*/"
        matcher (re-matcher pattern sql)]
    (when (.find matcher)
      {:directive (some-> (.group matcher 1) keyword)
       :body (.group matcher 2)
       :rest (subs sql (.end matcher))})))

(def ^:private declaration-eof ::declaration-eof)

(defn- read-edn-body
  [body]
  (let [reader (PushbackReader. (StringReader. body))
        value (edn/read {:eof declaration-eof} reader)
        trailing (edn/read {:eof declaration-eof} reader)]
    (when (= value declaration-eof)
      (throw (ex-info "Empty EDN value." {})))
    (when-not (= trailing declaration-eof)
      (throw (ex-info "Trailing EDN data." {})))
    value))

(defn- read-declaration-value
  [directive body]
  (try
    (read-edn-body body)
    (catch Exception ex
      (if (= directive :doc)
        (str/trim body)
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
  [query-name]
  (resolve-query-location query-name nil))

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

(defn- preprocess-template
  [query]
  query)

(defn- template-context
  [template]
  (cond-> {}
    (:query-name template) (assoc :query-name (:query-name template))
    (:base-path template) (assoc :base-path (:base-path template))
    (:resource-path template) (assoc :resource-path (:resource-path template))
    (:project-relative-path template) (assoc :project-relative-path (:project-relative-path template))
    (:source-line template) (assoc :source-line (:source-line template))))

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

(defn- line-number-at-index
  [s idx]
  (inc (count (filter #(= % \newline) (subs s 0 idx)))))

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

(defn- loaded-template
  [query-name function-name namespace-suffix base-path resource-path project-relative-path source-line sql-template]
  {:query-name query-name
   :function-name function-name
   :namespace-suffix namespace-suffix
   :base-path base-path
   :resource-path resource-path
   :project-relative-path project-relative-path
   :source-line source-line
   :sql-template sql-template})

(defn load-queries
  "Loads a SQL file and returns all query templates keyed by query name."
  ([filename]
   (load-queries filename {}))
  ([filename {:keys [base-path]
              :or {base-path "sql"}}]
   (let [{:keys [query-name resource-path project-relative-path sql-template]}
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
          (fn [queries {:keys [name sql-template source-line]}]
            (let [{:keys [query-name function-name namespace-suffix]}
                  (resolve-query-location query-name name)]
              (when (contains? queries query-name)
                (throw (ex-info "Duplicate query name."
                                {:query-name query-name
                                 :filename filename
                                 :base-path base-path
                                 :resource-path resource-path})))
              (assoc queries
                     query-name
                     (loaded-template query-name
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
         (let [{:keys [query-name]} (resolve-query-location query-name nil)]
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
       (let [blocks (parse-query-blocks sql-template)
             multiple? (> (count blocks) 1)]
         (when (and multiple? (some #(nil? (:name %)) blocks))
           (throw (ex-info "Multiple queries require :name declarations."
                           {:filename relative-path
                            :base-path base-path
                            :resource-path resource-path})))
         (reduce
          (fn [queries {:keys [name sql-template source-line]}]
            (let [{:keys [query-name function-name namespace-suffix]}
                  (resolve-query-location query-name name)]
              (when (contains? queries query-name)
                (throw (ex-info "Duplicate query name."
                                {:query-name query-name
                                 :filename relative-path
                                 :base-path base-path
                                 :resource-path resource-path})))
              (assoc queries
                     query-name
                     (loaded-template query-name
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
         (let [{:keys [query-name]} (resolve-query-location query-name nil)]
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
      (throw (ex-info "Missing query parameter."
                      {:parameter (parameter-key parameter-name)})))
    value))

(defn- default-value?
  [value]
  (identical? value default))

(defn- all-value?
  [value]
  (identical? value ALL))

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

(defn- truthy?
  [value]
  (not (or (nil? value) (false? value))))

(defn- eval-condition
  [parameter-name template-params]
  (truthy? (let [value (resolved-parameter-value template-params parameter-name)]
             (if (= value missing)
               nil
               value))))

(defn- trim-leading-newline
  [s]
  (if (str/starts-with? s "\n")
    (subs s 1)
    s))

(defn remove-trailing-clause-keyword
  [out]
  (let [current (str out)
        updated (or (some->> current
                             (re-matches #"(?s)(.*(?:^|\n))[ \t]*(WHERE|HAVING)[ \t\r\n]*$")
                             second)
                    (some->> current
                             (re-matches #"(?s)(.*?)(?:WHERE|HAVING)[ \t\r\n]*$")
                             second)
                    current)]
    (when-not (= current updated)
      (.setLength out 0)
      (.append out ^String updated))))

(defn trailing-set-clause?
  [out]
  (boolean
   (or (re-matches #"(?s).*(?:^|\n)[ \t]*SET[ \t\r\n]*$" (str out))
       (re-matches #"(?s).*SET[ \t\r\n]*$" (str out)))))

(defn trailing-values-clause?
  [out]
  (boolean
   (or (re-matches #"(?s).*(?:^|\n)[ \t]*VALUES[ \t\r\n]*$" (str out))
       (re-matches #"(?s).*VALUES[ \t\r\n]*$" (str out)))))

(defn trim-trailing-for-separator
  [s]
  (or (some->> s
               (re-matches #"(?is)(.*?)[ \t\r\n]*,[ \t\r\n]*$")
               second)
      (some->> s
               (re-matches #"(?is)(.*?)[ \t\r\n]+(?:AND|OR)[ \t\r\n]*$")
               second)
      s))

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

(defn- parse-control-directive
  [matcher]
  {:directive (.group matcher 1)
   :arg1 (.group matcher 2)
   :arg2 (.group matcher 3)
   :start (.start matcher)
   :end (.end matcher)})

(def ^:private control-directive-pattern
  #"/\*%(if|elseif|else|for|end)(?:\s+([A-Za-z0-9\-\.]+)(?:\s+in\s+([A-Za-z0-9\-\.]+))?)?\s*\*/")

(defn- append-conditional-branch
  [branches current-expr branch-start branch-end sql]
  (conj branches
        {:expr current-expr
         :body (subs sql branch-start branch-end)}))

(defn- parse-conditional-branches
  [sql if-start if-end initial-expr]
  (loop [cursor if-end
         depth 1
         branches []
         current-expr initial-expr
         branch-start if-end
         else-seen? false]
    (let [matcher (re-matcher control-directive-pattern sql)]
      (if-not (.find matcher cursor)
        (throw (ex-info "Unterminated conditional block."
                        {:sql sql
                         :start if-start}))
        (let [{:keys [directive arg1 start end]} (parse-control-directive matcher)]
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
                   else-seen?)

            (contains? #{"if" "for"} directive)
            (recur end
                   (inc depth)
                   branches
                   current-expr
                   branch-start
                   else-seen?)

            (= directive "elseif")
            (do
              (when else-seen?
                (throw (ex-info "Conditional block cannot contain elseif after else."
                                {:sql sql
                                 :start start})))
              (recur end
                     depth
                     (append-conditional-branch branches current-expr branch-start start sql)
                     arg1
                     end
                     else-seen?))

            (= directive "else")
            (do
              (when else-seen?
                (throw (ex-info "Conditional block cannot contain multiple else blocks."
                                {:sql sql
                                 :start start})))
              (recur end
                     depth
                     (append-conditional-branch branches current-expr branch-start start sql)
                     nil
                     end
                     true))

            (= directive "end")
            {:branches (append-conditional-branch branches current-expr branch-start start sql)
             :end end}))))))

(defn- parse-for-block
  [sql for-start for-end item-name collection-name]
  (loop [cursor for-end
         depth 1]
    (let [matcher (re-matcher control-directive-pattern sql)]
      (if-not (.find matcher cursor)
        (throw (ex-info "Unterminated for block."
                        {:sql sql
                         :start for-start}))
        (let [{:keys [directive start end]} (parse-control-directive matcher)]
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
  (let [matcher (re-matcher #"/\*%(if|for)\s+([A-Za-z0-9\-\.]+)(?:\s+in\s+([A-Za-z0-9\-\.]+))?\s*\*/" segment)]
    (loop [cursor 0
           nodes []]
      (if-not (.find matcher cursor)
        (into nodes (parse-variable-nodes (subs segment cursor)))
        (let [directive (.group matcher 1)
              arg1 (.group matcher 2)
              arg2 (.group matcher 3)
              block-start (.start matcher)
              block-end (.end matcher)
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

(defn selected-conditional-branch
  [branches template-params]
  (some (fn [{:keys [expr] :as branch}]
          (when (or (nil? expr)
                    (eval-condition expr template-params))
            branch))
        branches))

(defn- parse-variable-nodes
  [sql]
  (let [matcher (re-matcher #"/\*([\$\^\!])([A-Za-z0-9\-\.]+)\*/" sql)
        length (count sql)]
    (loop [cursor 0
           nodes []]
      (if-not (.find matcher cursor)
        (cond-> nodes
          (< cursor length)
          (conj {:op :text
                 :sql (subs sql cursor)}))
        (let [start (.start matcher)
              end (.end matcher)
              sigil (.group matcher 1)
              parameter-name (.group matcher 2)
              sample-start end
              nodes (cond-> nodes
                      (< cursor start)
                      (conj {:op :text
                             :sql (subs sql cursor start)}))]
          (if (and (not= sigil "!")
                   (or (>= sample-start length)
                       (whitespace? (.charAt sql sample-start))))
            (recur end
                   (conj nodes
                         {:op :text
                          :sql (subs sql start end)}))
            (let [collection? (and (= sigil "$")
                                   (= (.charAt sql sample-start) \())
                  sample-end (cond
                               (= sigil "!")
                               (if (or (>= sample-start length)
                                       (whitespace? (.charAt sql sample-start)))
                                 end
                                 (consume-sample-token sql sample-start))
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

(defn compile-ir
  "Compiles parsed template IR into a reusable renderer function."
  [ir]
  {:pre [(map? ir)]}
  (eval (emit-ir-form ir)))

(declare emit-sequential-render-body-form)

(defn- emit-compiled-node-form
  [node out-sym bind-params-sym params-sym skip-leading-operator-sym]
  (case (:op node)
    :text
    (let [sql (:sql node)]
      `(let [sql# ~sql
             sql# (if ~skip-leading-operator-sym
                    (if-let [trimmed# (~(var consume-leading-conditional-operator-from-text) sql#)]
                      trimmed#
                      (do
                        (~(var remove-trailing-clause-keyword) ~out-sym)
                        sql#))
                    sql#)]
         (.append ~out-sym ^String sql#)
         false))

    :variable
    (let [parameter-name (:parameter-name node)
          sigil (:sigil node)
          collection? (:collection? node)
          context (variable-context parameter-name sigil collection?)]
      `(do
         (when ~skip-leading-operator-sym
           (~(var remove-trailing-clause-keyword) ~out-sym))
         (try
           ~(case sigil
              "$"
              (if collection?
                `(let [value# (~(var parameter-value) ~params-sym ~parameter-name)]
                   (when (identical? value# default)
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
                   (when (some #(identical? % default) value#)
                     (throw (ex-info "DEFAULT is not allowed inside collection binding."
                                     {:parameter (~(var parameter-key) ~parameter-name)
                                      :value value#})))
                   (when (some #(identical? % ALL) value#)
                     (throw (ex-info "ALL is not allowed inside collection binding."
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
                     (identical? value# default)
                     (do
                       (.append ~out-sym "DEFAULT")
                       false)

                     (identical? value# ALL)
                     (do
                       (.append ~out-sym "ALL")
                       false)

                     :else
                     (do
                       (.append ~out-sym "?")
                       (conj! ~bind-params-sym value#)
                       false))))

              "^"
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
                                    :type (type value#)}))))

              "!"
              `(do
                 (.append ~out-sym ^String (str (~(var parameter-value) ~params-sym ~parameter-name)))
                 false))
           (catch clojure.lang.ExceptionInfo ex#
             (throw (ex-info (ex-message ex#)
                             (merge ~context (ex-data ex#))
                             ex#))))))

    :if
    (let [compiled-branches
          (mapv (fn [{:keys [expr body]}]
                  (let [body-out-sym (gensym "if_body_out__")
                        body-bind-params-sym (gensym "if_body_bind_params__")
                        body-params-sym (gensym "if_body_params__")
                        body-form (emit-sequential-render-body-form body
                                                                    body-out-sym
                                                                    body-bind-params-sym
                                                                    body-params-sym)]
                    {:expr expr
                     :body-out-sym body-out-sym
                     :body-bind-params-sym body-bind-params-sym
                     :body-params-sym body-params-sym
                     :body-form body-form}))
                (:branches node))]
      `(let [compiled-branches#
             ~(vec (map (fn [{:keys [expr body-out-sym body-bind-params-sym body-params-sym body-form]}]
                          `{:expr ~expr
                            :body (fn []
                                    (let [~body-out-sym (StringBuilder.)
                                          ~body-bind-params-sym (transient [])
                                          ~body-params-sym ~params-sym]
                                      ~body-form
                                      {:sql (str ~body-out-sym)
                                       :bind-params (persistent! ~body-bind-params-sym)}))})
                        compiled-branches))]
         (do
           (when ~skip-leading-operator-sym
             (~(var remove-trailing-clause-keyword) ~out-sym))
           (if-let [selected-branch# (~(var selected-conditional-branch) compiled-branches# ~params-sym)]
             (let [body-fn# (:body selected-branch#)]
               (~(var append-fragment!)
                ~out-sym
                ~bind-params-sym
                (~(var normalize-fragment-for-context)
                 ~out-sym
                 (body-fn#)))
               false)
             true))))

    :for
    (let [collection-name (:collection-name node)
          item-name (:item-name node)
          body-out-sym (gensym "body_out__")
          body-bind-params-sym (gensym "body_bind_params__")
          body-params-sym (gensym "body_params__")
          body-form (emit-sequential-render-body-form (:body node)
                                                      body-out-sym
                                                      body-bind-params-sym
                                                      body-params-sym)]
      `(let [items# (~(var parameter-value) ~params-sym ~collection-name)]
         (when-not (sequential? items#)
           (throw (ex-info "For block requires a sequential value."
                           {:parameter (~(var parameter-key) ~collection-name)
                            :value items#})))
         (if (seq items#)
           (do
             (doseq [[idx# item#] (map-indexed vector items#)]
               (let [~body-out-sym (StringBuilder.)
                     ~body-bind-params-sym (transient [])
                     ~body-params-sym (assoc ~params-sym (keyword ~item-name) item#)]
                 ~body-form
                 (let [fragment-sql# (str ~body-out-sym)
                       fragment-sql# (if (= idx# (dec (count items#)))
                                       (~(var trim-trailing-for-separator) fragment-sql#)
                                       fragment-sql#)
                       fragment-sql# (:sql (~(var normalize-fragment-for-context)
                                            ~out-sym
                                            {:sql fragment-sql#
                                             :bind-params []}))]
                   (.append ~out-sym ^String fragment-sql#)
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
             true))))))

(defn- emit-sequential-render-form
  [nodes out-sym bind-params-sym params-sym]
  (let [initial-skip-sym (gensym "skip__")]
    (loop [remaining nodes
           current-skip-sym initial-skip-sym
           bindings [initial-skip-sym false]]
      (if-let [node (first remaining)]
        (let [next-skip-sym (gensym "skip__")
              step-form (emit-compiled-node-form node
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
  [nodes out-sym bind-params-sym params-sym]
  (let [{:keys [bindings final-skip-sym]}
        (emit-sequential-render-form nodes out-sym bind-params-sym params-sym)]
    `(let [~@bindings]
       (when ~final-skip-sym
         (~(var remove-trailing-clause-keyword) ~out-sym)))))

(defn emit-ir-form
  "Emits a reusable renderer function form from parsed template IR."
  [ir]
  {:pre [(map? ir)]}
  (let [out-sym (gensym "out__")
        bind-params-sym (gensym "bind_params__")
        params-sym (gensym "params__")
        {:keys [bindings final-skip-sym]}
        (emit-sequential-render-form (:nodes ir) out-sym bind-params-sym params-sym)]
    `(fn [~params-sym]
       (let [~out-sym (StringBuilder.)
             ~bind-params-sym (transient [])
             ~@bindings]
         (when ~final-skip-sym
           (~(var remove-trailing-clause-keyword) ~out-sym))
         {:sql (str ~out-sym)
          :bind-params (persistent! ~bind-params-sym)}))))

(defn evaluate-ir
  "Evaluates parsed template IR and returns rendered SQL plus bind parameters."
  [ir template-params]
  {:pre [(map? ir) (map? template-params)]}
  ((compile-ir ir) template-params))

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
      (catch clojure.lang.ExceptionInfo ex
        (throw (ex-info (ex-message ex)
                        (merge context (ex-data ex))
                        ex))))))

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
      (let [preprocessed-template (preprocess-template template)
            analyzed-template (analyze-template preprocessed-template)
            renderer (compile-ir (parse-template (:sql-template analyzed-template)))]
        (render-compiled-query analyzed-template renderer template-params))
      (catch clojure.lang.ExceptionInfo ex
        (throw (ex-info (ex-message ex)
                        (merge context (ex-data ex))
                        ex))))))
