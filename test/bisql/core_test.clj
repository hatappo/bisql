(ns bisql.core-test
  (:require [bisql.core :as bisql]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest public-api-vars-exist
  (testing "core namespace exposes the planned public API"
    (is (fn? bisql/load-query))
    (is (fn? bisql/load-queries))
    (is (fn? bisql/render-query))
    (is (some? bisql/default))
    (is (fn? bisql/execute!))
    (is (fn? bisql/execute-one!))
    (is (fn? bisql/generate-crud))
    (is (fn? bisql/render-crud-files))
    (is (fn? bisql/write-crud-files!))))

(deftest render-query-supports-scalar-bind
  (let [result (bisql/render-query
                {:sql-template "SELECT * FROM users WHERE id = /*$id*/1"}
                {:id 42})]
    (is (= "SELECT * FROM users WHERE id = ?" (:sql result)))
    (is (= [42] (:params result)))))

(deftest render-query-supports-in-binding
  (let [result (bisql/render-query
                {:sql-template (str/join "\n"
                                         ["SELECT *"
                                          "FROM users"
                                          "WHERE id IN /*$ids*/(1,2,3)"])}
                {:ids [10 20 30]})]
    (is (= (str/join "\n"
                     ["SELECT *"
                      "FROM users"
                      "WHERE id IN (?, ?, ?)"])
           (:sql result)))
    (is (= [10 20 30] (:params result)))))

(deftest render-query-supports-default-bind-value
  (let [result (bisql/render-query
                {:sql-template "INSERT INTO users (email, status) VALUES (/*$email*/'a', /*$status*/'active')"}
                {:email "alice@example.com"
                 :status bisql/default})]
    (is (= "INSERT INTO users (email, status) VALUES (?, DEFAULT)" (:sql result)))
    (is (= ["alice@example.com"] (:params result)))))

(deftest render-query-supports-literal-string-binding
  (let [result (bisql/render-query
                {:sql-template "SELECT * FROM users WHERE type = /*^type*/'A'"}
                {:type "BOOK"})]
    (is (= "SELECT * FROM users WHERE type = 'BOOK'" (:sql result)))
    (is (= [] (:params result)))))

(deftest render-query-supports-literal-number-binding
  (let [result (bisql/render-query
                {:sql-template "SELECT * FROM users WHERE level = /*^level*/1"}
                {:level 10})]
    (is (= "SELECT * FROM users WHERE level = 10" (:sql result)))
    (is (= [] (:params result)))))

(deftest render-query-supports-raw-binding
  (let [result (bisql/render-query
                {:sql-template "SELECT * FROM users ORDER BY /*!order-by*/id"}
                {:order-by "created_at DESC"})]
    (is (= "SELECT * FROM users ORDER BY created_at DESC" (:sql result)))
    (is (= [] (:params result)))))

(deftest render-query-rejects-literal-strings-with-single-quotes
  (let [error (try
                (bisql/render-query
                 {:sql-template "SELECT * FROM users WHERE type = /*^type*/'A'"}
                 {:type "BO'OK"})
                nil
                (catch clojure.lang.ExceptionInfo ex
                  ex))]
    (is (= "Literal string values must not contain single quotes."
           (ex-message error)))
    (is (= :type (:parameter (ex-data error))))
    (is (= "BO'OK" (:value (ex-data error))))
    (is (= "^" (:sigil (ex-data error))))
    (is (= false (:collection? (ex-data error))))))

(deftest render-query-rejects-unsupported-literal-types
  (let [error (try
                (bisql/render-query
                 {:sql-template "SELECT * FROM users WHERE type = /*^type*/'A'"}
                 {:type :book})
                nil
                (catch clojure.lang.ExceptionInfo ex
                  ex))]
    (is (= "Unsupported literal value type."
           (ex-message error)))
    (is (= :type (:parameter (ex-data error))))
    (is (= :book (:value (ex-data error))))))

(deftest render-query-adds-variable-context-to-collection-errors
  (let [error (try
                (bisql/render-query
                 {:sql-template "SELECT * FROM users WHERE id IN /*$ids*/(1,2,3)"}
                 {:ids 42})
                nil
                (catch clojure.lang.ExceptionInfo ex
                  ex))]
    (is (= "Collection binding requires a sequential value."
           (ex-message error)))
    (is (= :ids (:parameter (ex-data error))))
    (is (= "$" (:sigil (ex-data error))))
    (is (= true (:collection? (ex-data error))))))

(deftest render-query-rejects-default-in-collection-binding
  (let [error (try
                (bisql/render-query
                 {:sql-template "SELECT * FROM users WHERE id IN /*$ids*/(1,2,3)"}
                 {:ids [1 bisql/default 3]})
                nil
                (catch clojure.lang.ExceptionInfo ex
                  ex))]
    (is (= "DEFAULT is not allowed inside collection binding."
           (ex-message error)))
    (is (= :ids (:parameter (ex-data error))))
    (is (= "$" (:sigil (ex-data error))))
    (is (= true (:collection? (ex-data error))))))

(deftest render-query-trims-leading-and-trailing-whitespace
  (let [result (bisql/render-query
                {:sql-template "\n  SELECT * FROM users WHERE id = /*$id*/1  \n"}
                {:id 42})]
    (is (= "SELECT * FROM users WHERE id = ?" (:sql result)))
    (is (= [42] (:params result)))))

(deftest load-query-uses-default-base-path
  (let [result (bisql/load-query "postgresql/public/users/get-by-id.sql")]
    (is (= "sql" (:base-path result)))
    (is (= "get-by-id" (:query-name result)))
    (is (= "sql/postgresql/public/users/get-by-id.sql"
           (:resource-path result)))
    (is (= "SELECT * FROM users WHERE id = /*$id*/ 1"
           (str/trim (:sql-template result))))))

(deftest load-query-keeps-declarations-in-sql-template
  (let [result (bisql/load-query "example-declarations-valid.sql"
                                 {:base-path "sql"})]
    (is (= (str/join "\n"
                     ["/*:doc"
                      "Loads a user by id."
                      "*/"
                      "/*:meta"
                      "{:tags [:example :user]"
                      " :returns :one}"
                      "*/"
                      "SELECT * FROM users WHERE id = /*$id*/1"])
           (str/trim (:sql-template result))))))

(deftest load-queries-supports-multiple-templates-in-a-single-file
  (let [queries (bisql/load-queries "example-multi-queries.sql"
                                    {:base-path "sql"})]
    (is (= #{"find-user-by-id" "find-user-by-email"}
           (set (keys queries))))
    (is (= "find-user-by-id"
           (:query-name (get queries "find-user-by-id"))))
    (is (= "sql/example-multi-queries.sql"
           (:resource-path (get queries "find-user-by-id"))))))

(deftest load-queries-can-select-a-template-by-query-name
  (let [result (get (bisql/load-queries "example-multi-queries.sql"
                                        {:base-path "sql"})
                    "find-user-by-email")]
    (is (= "find-user-by-email" (:query-name result)))
    (is (= (str "/*:name find-user-by-email */\n"
                "SELECT * FROM users WHERE email = /*$email*/'user@example.com'")
           (str/trim (:sql-template result))))))

(deftest load-query-rejects-multiple-templates
  (let [error (try
                (bisql/load-query "example-multi-queries.sql"
                                  {:base-path "sql"})
                nil
                (catch clojure.lang.ExceptionInfo ex
                  ex))]
    (is (= "Multiple queries found; use load-queries."
           (ex-message error)))
    (is (= ["find-user-by-email" "find-user-by-id"]
           (:query-names (ex-data error))))))

(deftest load-query-requires-sql-extension
  (let [error (try
                (bisql/load-query "example-declarations-valid"
                                  {:base-path "sql"})
                nil
                (catch clojure.lang.ExceptionInfo ex
                  ex))]
    (is (= "Query file name must end with .sql."
           (ex-message error)))
    (is (= "example-declarations-valid"
           (:filename (ex-data error))))))

(deftest render-query-returns-doc-and-meta-declarations
  (let [result (bisql/render-query
                (bisql/load-query "example-declarations-valid.sql"
                                  {:base-path "sql"})
                {:id 42})]
    (is (= "Loads a user by id." (:doc result)))
    (is (= {:tags [:example :user]
            :returns :one}
           (:meta result)))
    (is (= "SELECT * FROM users WHERE id = ?"
           (:sql result)))
    (is (= [42] (:params result)))
    (is (= "example-declarations-valid" (:query-name result)))
    (is (= "sql/example-declarations-valid.sql" (:resource-path result)))))

(deftest render-query-adds-template-context-to-errors
  (let [error (try
                (bisql/render-query
                 (bisql/load-query "example-declarations-invalid-meta.sql"
                                   {:base-path "sql"})
                 {})
                nil
                (catch clojure.lang.ExceptionInfo ex
                  ex))]
    (is (= "Invalid meta declaration."
           (ex-message error)))
    (is (= "example-declarations-invalid-meta"
           (:query-name (ex-data error))))
    (is (= "sql/example-declarations-invalid-meta.sql"
           (:resource-path (ex-data error))))))

(deftest render-query-rejects-duplicate-declarations
  (let [error (try
                (bisql/render-query
                 (bisql/load-query "example-declarations-duplicate.sql"
                                   {:base-path "sql"})
                 {})
                nil
                (catch clojure.lang.ExceptionInfo ex
                  ex))]
    (is (= "Duplicate declaration block."
           (ex-message error)))
    (is (= :doc (:directive (ex-data error))))
    (is (= "example-declarations-duplicate"
           (:query-name (ex-data error))))
    (is (= "sql/example-declarations-duplicate.sql"
           (:resource-path (ex-data error))))))

(deftest render-query-rejects-trailing-declarations
  (let [error (try
                (bisql/render-query
                 (bisql/load-query "example-declarations-trailing.sql"
                                   {:base-path "sql"})
                 {})
                nil
                (catch clojure.lang.ExceptionInfo ex
                  ex))]
    (is (= "Declaration blocks must appear at the beginning of the SQL file."
           (ex-message error)))
    (is (= :doc (:directive (ex-data error))))
    (is (= "example-declarations-trailing"
           (:query-name (ex-data error))))
    (is (= "sql/example-declarations-trailing.sql"
           (:resource-path (ex-data error))))))

(deftest render-query-rejects-invalid-meta-declarations
  (let [error (try
                (bisql/render-query
                 (bisql/load-query "example-declarations-invalid-meta.sql"
                                   {:base-path "sql"})
                 {})
                nil
                (catch clojure.lang.ExceptionInfo ex
                  ex))]
    (is (= "Invalid meta declaration."
           (ex-message error)))
    (is (= :meta (:directive (ex-data error))))))

(deftest render-query-rejects-unknown-declarations
  (let [error (try
                (bisql/render-query
                 (bisql/load-query "example-declarations-unknown.sql"
                                   {:base-path "sql"})
                 {})
                nil
                (catch clojure.lang.ExceptionInfo ex
                  ex))]
    (is (= "Unsupported declaration block."
           (ex-message error)))
    (is (= :foo (:directive (ex-data error))))
    (is (= "example-declarations-unknown"
           (:query-name (ex-data error))))
    (is (= "sql/example-declarations-unknown.sql"
           (:resource-path (ex-data error))))))

(deftest render-query-rejects-declarations-with-space-before-name
  (let [error (try
                (bisql/render-query
                 (bisql/load-query "example-declarations-space-before-name.sql"
                                   {:base-path "sql"})
                 {})
                nil
                (catch clojure.lang.ExceptionInfo ex
                  ex))]
    (is (= "Unsupported declaration block."
           (ex-message error)))
    (is (= :foo (:directive (ex-data error))))
    (is (= "example-declarations-space-before-name"
           (:query-name (ex-data error))))
    (is (= "sql/example-declarations-space-before-name.sql"
           (:resource-path (ex-data error))))))

(deftest render-query-supports-if-blocks
  (let [result (bisql/render-query
                {:sql-template (str/join "\n"
                                         ["SELECT *"
                                          "FROM users"
                                          "WHERE 1 = 1"
                                          "/*%if name */"
                                          "  AND name = /*$name*/'foo'"
                                          "/*%end */"])}
                {:name "Alice"})]
    (is (= (str/join "\n"
                     ["SELECT *"
                      "FROM users"
                      "WHERE 1 = 1"
                      "  AND name = ?"])
           (:sql result)))
    (is (= ["Alice"] (:params result)))))

(deftest render-query-omits-if-blocks-when-condition-is-false
  (let [result (bisql/render-query
                {:sql-template (str/join "\n"
                                         ["SELECT *"
                                          "FROM users"
                                          "WHERE 1 = 1"
                                          "/*%if name */"
                                          "  AND name = /*$name*/'foo'"
                                          "/*%end */"])}
                {:name nil})]
    (is (= (str/join "\n"
                     ["SELECT *"
                      "FROM users"
                      "WHERE 1 = 1"])
           (:sql result)))
    (is (= [] (:params result)))))

(deftest render-query-uses-clojure-truthiness-for-if
  (let [result (bisql/render-query
                {:sql-template (str/join "\n"
                                         ["SELECT *"
                                          "FROM users"
                                          "WHERE 1 = 1"
                                          "/*%if active */"
                                          "  AND active = true"
                                          "/*%end */"])}
                {:active ""})]
    (is (= (str/join "\n"
                     ["SELECT *"
                      "FROM users"
                      "WHERE 1 = 1"
                      "  AND active = true"])
           (:sql result)))
    (is (= [] (:params result)))))

(deftest render-query-omits-if-blocks-for-false
  (let [result (bisql/render-query
                {:sql-template (str/join "\n"
                                         ["SELECT *"
                                          "FROM users"
                                          "WHERE 1 = 1"
                                          "/*%if active */"
                                          "  AND active = true"
                                          "/*%end */"])}
                {:active false})]
    (is (= (str/join "\n"
                     ["SELECT *"
                      "FROM users"
                      "WHERE 1 = 1"])
           (:sql result)))
    (is (= [] (:params result)))))
