(ns examples.markdown
  (:require [bisql.query :as bisql]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

(defn render
  ([template-or-resource params]
   (bisql/render-query (if (string? template-or-resource)
                         (bisql/load-query template-or-resource)
                         template-or-resource)
                       params))
  ([resource load-options params]
   (bisql/render-query (bisql/load-query resource load-options) params)))

(defn normalize-edn-value
  [x]
  (cond
    (and (symbol? x) (= "bisql/DEFAULT" (str x))) bisql/DEFAULT
    (and (symbol? x) (= "bisql/ALL" (str x))) bisql/ALL
    (and (list? x)
         (symbol? (first x))
         (= "bisql/LIKE_STARTS_WITH" (str (first x))))
    (bisql/LIKE_STARTS_WITH (normalize-edn-value (second x)))
    (and (list? x)
         (symbol? (first x))
         (= "bisql/LIKE_ENDS_WITH" (str (first x))))
    (bisql/LIKE_ENDS_WITH (normalize-edn-value (second x)))
    (and (list? x)
         (symbol? (first x))
         (= "bisql/LIKE_CONTAINS" (str (first x))))
    (bisql/LIKE_CONTAINS (normalize-edn-value (second x)))
    (map? x) (into (empty x) (map (fn [[k v]] [k (normalize-edn-value v)])) x)
    (vector? x) (mapv normalize-edn-value x)
    (list? x) (apply list (map normalize-edn-value x))
    (seq? x) (doall (map normalize-edn-value x))
    :else x))

(defn render-input-form
  [{:keys [kind resource load-options query-name params sql-template]}]
  (case kind
    :resource
    (if (seq load-options)
      (list 'render resource load-options params)
      (list 'render resource params))

    :resource-query
    (list 'render
          (list 'get
                (if (seq load-options)
                  (list 'bisql/load-queries resource load-options)
                  (list 'bisql/load-queries resource))
                query-name)
          params)

    :direct-template
    (list 'render {:sql-template sql-template} params)))

(defn input-template
  [{:keys [kind resource load-options query-name sql-template]}]
  (case kind
    :resource
    (if (seq load-options)
      (bisql/load-query resource load-options)
      (bisql/load-query resource))

    :resource-query
    (get (if (seq load-options)
           (bisql/load-queries resource load-options)
           (bisql/load-queries resource))
         query-name)

    :direct-template
    {:sql-template sql-template}))

(defn execute-example
  [{:keys [input]}]
  (let [{:keys [params]} input]
    (render (input-template input) (normalize-edn-value params))))

(defn call-form-lines
  [example]
  (str/split-lines (pr-str (render-input-form (:input example)))))

(defn input-sql-lines
  [example]
  (when-let [sql-template (:sql-template (input-template (:input example)))]
    (str/split-lines sql-template)))

(defn rendered-sql-lines
  [result]
  (when (string? (:sql result))
    (str/split-lines (:sql result))))

(defn print-code-block
  [label language lines]
  (println (str label ":"))
  (println (str "```" language))
  (doseq [line lines]
    (println line))
  (println "```")
  (println))

(defn print-anonymous-code-block
  [language lines]
  (println (str "```" language))
  (doseq [line lines]
    (println line))
  (println "```")
  (println))

(defn output-summary
  [result]
  (cond-> {}
    (contains? result :params) (assoc :params (:params result))
    (and (contains? result :meta)
         (seq (:meta result))) (assoc :meta (:meta result))))

(defn print-description
  [description]
  (when (some? description)
    (doseq [line (str/split-lines (str description))]
      (println line))))

(defn show-example
  [{:keys [title description] :as example}]
  (let [result (execute-example example)]
    (println (str "\n### " title) "\n")
    (print-code-block "1. Input Form" "clj" (call-form-lines example))
    (when-let [lines (seq (input-sql-lines example))]
      (print-code-block "2. Input SQL" "sql" lines))
    (println "3. Output SQL and Params:")
    (when-let [lines (seq (rendered-sql-lines result))]
      (print-anonymous-code-block "sql" lines))
    (let [summary (output-summary result)]
      (when (seq summary)
        (print-anonymous-code-block "clj"
                                    (str/split-lines
                                     (with-out-str
                                       (pprint/pprint summary))))))
    (print-description description)))

(defn error-output-lines
  [error]
  (str/split-lines
   (with-out-str
     (pprint/pprint {:message (ex-message error)
                     :data (ex-data error)}))))

(defn show-error-example
  [{:keys [title] :as example}]
  (let [error (try
                (execute-example example)
                nil
                (catch clojure.lang.ExceptionInfo ex
                  ex))]
    (println (str "\n### " title) "\n")
    (print-code-block "1. Input Form" "clj" (call-form-lines example))
    (when-let [lines (seq (input-sql-lines example))]
      (print-code-block "2. Input SQL" "sql" lines))
    (print-code-block "3. Output Error" "clj" (error-output-lines error))))

(defn render-examples-markdown
  []
  (let [examples-doc (-> "docs/data/rendering-examples.edn"
                         slurp
                         edn/read-string)]
    (with-out-str
      (println (str "# " (:title examples-doc)))
      (doseq [{:keys [title examples]} (:groups examples-doc)]
        (println)
        (println (str "## " title))
        (doseq [example examples]
          (if (:error? example)
            (show-error-example example)
            (show-example example))))
      (when-let [notes (seq (:notes examples-doc))]
        (println "## Notes")
        (println)
        (doseq [note notes]
          (println (str "- " note)))))))
