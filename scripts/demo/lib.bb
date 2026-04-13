(ns demo.lib
  (:require [bisql.query :as bisql]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

(defn render-form?
  [form]
  (and (seq? form)
       (#{'render-query 'render} (first form))))

(defn input-template
  [form]
  (when (render-form? form)
    (case (first form)
      render-query
      (eval (second form))

      render
      (let [[template-or-resource maybe-load-options _params] (rest form)]
        (if _params
          (bisql/load-query (eval template-or-resource)
                            (eval maybe-load-options))
          (let [template (eval template-or-resource)]
            (if (string? template)
              (bisql/load-query template)
              template)))))))

(defn call-form-lines
  [form]
  (str/split-lines (pr-str form)))

(defn input-sql-lines
  [form]
  (when-let [sql-template (:sql-template (input-template form))]
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
  [title form result description]
  (println (str "\n### " title) "\n")
  (print-code-block "1. Input Form" "clj" (call-form-lines form))
  (when-let [lines (seq (input-sql-lines form))]
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
  (print-description description))

(defn error-output-lines
  [error]
  (str/split-lines
   (with-out-str
     (pprint/pprint {:message (ex-message error)
                     :data (ex-data error)}))))

(defn show-error-example
  [title form error]
  (println (str "\n### " title) "\n")
  (print-code-block "1. Input Form" "clj" (call-form-lines form))
  (when-let [lines (seq (input-sql-lines form))]
    (print-code-block "2. Input SQL" "sql" lines))
  (print-code-block "3. Output Error" "clj" (error-output-lines error)))

(defmacro example
  ([title form]
   `(show-example ~title '~form ~form nil))
  ([title form description]
   `(show-example ~title '~form ~form ~description)))

(defmacro error-example
  [title form]
  `(show-error-example ~title
                       '~form
                       (try
                         ~form
                         nil
                         (catch clojure.lang.ExceptionInfo ex#
                           ex#))))
