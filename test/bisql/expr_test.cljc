(ns bisql.expr-test
  (:require [bisql.expr :as expr]
            [clojure.test :refer [deftest is testing]]))

(deftest parse-supports-logical-and-comparison-expressions
  (is (= {:op :or
          :left {:op :and
                 :left {:op :identifier :name "active"}
                 :right {:op :comparison
                         :operator "="
                         :left {:op :identifier :name "status"}
                         :right {:op :identifier :name "expected_status"}}}
          :right {:op :comparison
                  :operator ">="
                  :left {:op :identifier :name "age"}
                  :right {:op :identifier :name "minimum_age"}}}
         (expr/parse "(active and status = expected_status) or age >= minimum_age"))))

(deftest evaluate-supports-identifier-truthiness
  (is (true? (expr/evaluate (expr/parse "active")
                            {"active" true})))
  (is (false? (expr/evaluate (expr/parse "active")
                             {"active" nil}))))

(deftest evaluate-supports-logical-and-comparison-operators
  (let [resolver {"active" true
                  "status" "pending"
                  "expected_status" "pending"
                  "user.age" 20
                  "minimum_age" 18}]
    (is (true? (expr/evaluate (expr/parse "active and status = expected_status")
                              resolver)))
    (is (true? (expr/evaluate (expr/parse "user.age >= minimum_age")
                              resolver)))
    (is (true? (expr/evaluate (expr/parse "status = expected_status or active")
                              resolver)))))

(deftest evaluate-rejects-ordering-comparison-for-nil
  (let [error (try
                (expr/evaluate (expr/parse "age >= minimum_age")
                               {"age" nil
                                "minimum_age" 18})
                nil
                (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) ex
                  ex))]
    (is (= "Ordering comparisons do not support nil values."
           (ex-message error)))))

(deftest parse-rejects-unsupported-operators-and-trailing-tokens
  (testing "not is not supported in the initial expression language"
    (let [error (try
                  (expr/parse "not active")
                  nil
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) ex
                    ex))]
      (is (= "Unexpected trailing tokens in conditional expression."
             (ex-message error)))))
  (testing "literal values are not supported in the initial expression language"
    (let [error (try
                  (expr/parse "status = \"active\"")
                  nil
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) ex
                    ex))]
      (is (= "Unsupported token in conditional expression."
             (ex-message error))))))
