(ns bisql.expr
  (:require [clojure.string :as str]))

(def ^:private end-token ::end)
(declare parse-expression)

(defn- identifier-char?
  [ch]
  (boolean
   (or (re-matches #"[A-Za-z0-9]" (str ch))
       (contains? #{\- \. \_} ch))))

(defn- whitespace?
  [ch]
  (boolean (re-matches #"\s" (str ch))))

(defn- comparison-operator-at
  [s idx]
  (cond
    (str/starts-with? (subs s idx) "<=") "<="
    (str/starts-with? (subs s idx) ">=") ">="
    (str/starts-with? (subs s idx) "!=") "!="
    (str/starts-with? (subs s idx) "=") "="
    (str/starts-with? (subs s idx) "<") "<"
    (str/starts-with? (subs s idx) ">") ">"))

(defn tokenize
  [expression]
  {:pre [(string? expression)]}
  (loop [idx 0
         tokens []]
    (if (>= idx (count expression))
      tokens
      (let [ch (.charAt expression idx)]
        (cond
          (whitespace? ch)
          (recur (inc idx) tokens)

          (#{\( \)} ch)
          (recur (inc idx) (conj tokens {:type (if (= ch \() :lparen :rparen)
                                         :text (str ch)}))

          :else
          (if-let [operator (comparison-operator-at expression idx)]
            (recur (+ idx (count operator))
                   (conj tokens {:type :operator
                                 :text operator}))
            (if (identifier-char? ch)
              (let [end (loop [cursor (inc idx)]
                          (if (and (< cursor (count expression))
                                   (identifier-char? (.charAt expression cursor)))
                            (recur (inc cursor))
                            cursor))
                    token-text (subs expression idx end)
                    token-type (case token-text
                                 "and" :and
                                 "or" :or
                                 :identifier)]
                (recur end (conj tokens {:type token-type
                                         :text token-text})))
              (throw (ex-info "Unsupported token in conditional expression."
                              {:expression expression
                               :index idx
                               :token (str ch)})))))))))

(defn- peek-token
  [tokens idx]
  (get tokens idx {:type end-token}))

(defn- parse-operand
  [tokens idx expression]
  (let [token (peek-token tokens idx)]
    (when-not (= :identifier (:type token))
      (throw (ex-info "Expected identifier in conditional expression."
                      {:expression expression
                       :token (:text token)})))
    [{:op :identifier
      :name (:text token)}
     (inc idx)]))

(defn- parse-comparison
  [tokens idx expression]
  (let [[left next-idx] (parse-operand tokens idx expression)
        token (peek-token tokens next-idx)]
    (if (= :operator (:type token))
      (let [[right final-idx] (parse-operand tokens (inc next-idx) expression)]
        [{:op :comparison
          :operator (:text token)
          :left left
          :right right}
         final-idx])
      [left next-idx])))

(defn- parse-group
  [tokens idx expression]
  (let [token (peek-token tokens idx)]
    (if (= :lparen (:type token))
      (let [[expr next-idx] (parse-expression tokens (inc idx) expression)
            closing-token (peek-token tokens next-idx)]
        (when-not (= :rparen (:type closing-token))
          (throw (ex-info "Expected closing ')' in conditional expression."
                          {:expression expression
                           :token (:text closing-token)})))
        [expr (inc next-idx)])
      (parse-comparison tokens idx expression))))

(defn- parse-and
  [tokens idx expression]
  (loop [[left next-idx] (parse-group tokens idx expression)]
    (let [token (peek-token tokens next-idx)]
      (if (= :and (:type token))
        (let [[right final-idx] (parse-group tokens (inc next-idx) expression)]
          (recur [{:op :and
                   :left left
                   :right right}
                  final-idx]))
        [left next-idx]))))

(defn parse-expression
  [tokens idx expression]
  (loop [[left next-idx] (parse-and tokens idx expression)]
    (let [token (peek-token tokens next-idx)]
      (if (= :or (:type token))
        (let [[right final-idx] (parse-and tokens (inc next-idx) expression)]
          (recur [{:op :or
                   :left left
                   :right right}
                  final-idx]))
        [left next-idx]))))

(defn parse
  [expression]
  {:pre [(string? expression)]}
  (let [trimmed (str/trim expression)]
    (when (str/blank? trimmed)
      (throw (ex-info "Conditional expression must not be blank."
                      {:expression expression})))
    (let [tokens (tokenize trimmed)
          [ast next-idx] (parse-expression tokens 0 trimmed)
          token (peek-token tokens next-idx)]
      (when-not (= end-token (:type token))
        (throw (ex-info "Unexpected trailing tokens in conditional expression."
                        {:expression trimmed
                         :token (:text token)})))
      ast)))

(defn- truthy?
  [value]
  (not (or (nil? value) (false? value))))

(defn- compare-values
  [operator left right]
  (case operator
    "=" (= left right)
    "!=" (not= left right)
    (do
      (when (or (nil? left) (nil? right))
        (throw (ex-info "Ordering comparisons do not support nil values."
                        {:operator operator
                         :left left
                         :right right})))
      (let [order (try
                    (compare left right)
                    (catch #?(:clj Exception :cljs :default) ex
                      (throw (ex-info "Ordering comparison requires comparable values."
                                      {:operator operator
                                       :left left
                                       :right right}
                                      ex))))]
        (case operator
          "<" (neg? order)
          "<=" (not (pos? order))
          ">" (pos? order)
          ">=" (not (neg? order))
          (throw (ex-info "Unsupported comparison operator."
                          {:operator operator})))))))

(defn evaluate
  [ast resolve-value]
  {:pre [(ifn? resolve-value)]}
  (case (:op ast)
    :identifier
    (truthy? (resolve-value (:name ast)))

    :comparison
    (compare-values (:operator ast)
                    (resolve-value (get-in ast [:left :name]))
                    (resolve-value (get-in ast [:right :name])))

    :and
    (and (evaluate (:left ast) resolve-value)
         (evaluate (:right ast) resolve-value))

    :or
    (or (evaluate (:left ast) resolve-value)
        (evaluate (:right ast) resolve-value))

    (throw (ex-info "Unsupported expression node."
                    {:ast ast}))))
