(ns demo.lib
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]))

(defn render-query-form?
  [form]
  (and (seq? form)
       (= 'render-query (first form))))

(defn input-template
  [form]
  (when (render-query-form? form)
    (eval (second form))))

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

(defn show-example
  [title form result]
  (println (str "\n### " title) "\n")
  (print-code-block "1. Input Form" "clj" (call-form-lines form))
  (when-let [lines (seq (input-sql-lines form))]
    (print-code-block "2. Input SQL" "sql" lines))
  (when-let [lines (seq (rendered-sql-lines result))]
    (print-code-block "3. Output SQL" "sql" lines))
  (print-code-block "4. Output Data" "clj"
                    (str/split-lines (with-out-str (pprint/pprint result)))))

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
  [title form]
  `(show-example ~title '~form ~form))

(defmacro error-example
  [title form]
  `(show-error-example ~title
                       '~form
                       (try
                         ~form
                         nil
                         (catch clojure.lang.ExceptionInfo ex#
                           ex#))))
