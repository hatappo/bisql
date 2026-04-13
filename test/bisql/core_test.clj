(ns bisql.core-test
  (:require [bisql.core :as bisql]
            [bisql.define :as define]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(bisql/defrender "/sql/postgresql/public/users/get-by-id.sql")
(bisql/defrender "/sql/example-declarations-valid.sql")
(bisql/defrender "/sql/example-multi-queries.sql")

(defn- query-fn
  [ns-sym sym]
  (var-get (ns-resolve ns-sym sym)))

(defn- query-var-meta
  [ns-sym sym]
  (meta (ns-resolve ns-sym sym)))

(deftest public-api-vars-exist
  (testing "core namespace exposes the planned public API"
    (is (fn? bisql/load-query))
    (is (fn? bisql/load-queries))
    (is (fn? bisql/analyze-template))
    (is (fn? bisql/parse-template))
    (is (fn? bisql/renderer-plan))
    (is (fn? bisql/evaluate-renderer-plan))
    (is (fn? bisql/emit-renderer-form))
    (is (fn? bisql/compile-renderer))
    (is (fn? bisql/evaluate-renderer))
    (is (fn? bisql/render-compiled-query))
    (is (fn? bisql/render-query))
    (is (:macro (meta #'bisql/defrender)))
    (is (:macro (meta #'bisql/defquery)))
    (is (some? bisql/DEFAULT))
    (is (some? bisql/ALL))
    (is (fn? bisql/generate-crud))
    (is (fn? bisql/render-crud-files))
    (is (fn? bisql/write-crud-files!))
    (is (fn? bisql/render-declaration-files))
    (is (fn? bisql/write-declaration-files!))))

(deftest analyze-template-extracts-metadata
  (let [result (bisql/analyze-template
                (bisql/load-query "example-declarations-valid.sql"
                                  {:base-path "sql"}))]
    (is (= "core.example-declarations-valid" (:query-name result)))
    (is (= "example-declarations-valid" (:function-name result)))
    (is (= ["core"] (:namespace-suffix result)))
    (is (= {:doc "Loads a user by id."
            :cardinality :one
            :tags [:example :user]
            :returns :one}
           (:meta result)))
    (is (= "test/sql/example-declarations-valid.sql" (:project-relative-path result)))
    (is (= 1 (:source-line result)))
    (is (= "SELECT * FROM users WHERE id = /*$id*/1"
           (str/trim (:sql-template result))))))

(deftest defrender-defines-a-render-function
  (let [result ((query-fn 'sql.core 'example-declarations-valid) {:id 42})]
    (is (= "SELECT * FROM users WHERE id = ?" (:sql result)))
    (is (= [42] (:params result)))
    (is (= "core.example-declarations-valid" (:query-name result)))))

(deftest defrender-attaches-template-metadata-to-var
  (let [metadata (query-var-meta 'sql.postgresql.public.users.core 'get-by-id)]
    (is (= "core.get-by-id" (:query-name metadata)))
    (is (= "get-by-id" (:function-name metadata)))
    (is (= ["core"] (:namespace-suffix metadata)))
    (is (= "sql/postgresql/public/users/get-by-id.sql" (:resource-path metadata)))
    (is (= "test/sql/postgresql/public/users/get-by-id.sql" (:project-relative-path metadata)))
    (is (= 1 (:source-line metadata)))
    (is (= "SELECT * FROM users WHERE id = /*$id*/ 1" (str/trim (:sql-template metadata))))))

(deftest defrender-merges-declarations-into-var-metadata
  (let [metadata (query-var-meta 'sql.core 'example-declarations-valid)]
    (is (= "Loads a user by id." (:declared-doc metadata)))
    (is (str/includes? (:doc metadata) "Loads a user by id."))
    (is (str/includes? (:doc metadata) "This function is generated from SQL: "))
    (is (str/includes? (:doc metadata) "test/sql/example-declarations-valid.sql:1"))
    (is (str/includes? (:doc metadata) "SELECT * FROM users WHERE id = /*$id*/1"))
    (is (= :one (:cardinality metadata)))
    (is (= [:example :user] (:tags metadata)))
    (is (= :one (:returns metadata)))
    (is (= "core.example-declarations-valid" (:query-name metadata)))
    (is (= '([]
             [template-params])
           (:arglists metadata)))))

(deftest navigation-stub-metadata-stringifies-symbol-doc
  (let [metadata (define/navigation-stub-metadata
                  {:meta {:doc 'foo}
                   :project-relative-path "src/sql/example.sql"
                   :source-line 1
                   :sql-template "SELECT 1"}
                  '([datasource] [datasource template-params]))]
    (is (str/includes? (:doc metadata) "foo\nThis function is generated from SQL: src/sql/example.sql:1"))))

(deftest defrender-defines-one-function-per-query
  (let [by-id ((query-fn 'sql.core 'find-user-by-id) {:id 42})
        by-email ((query-fn 'sql.core 'find-user-by-email) {:email "user@example.com"})]
    (is (= "SELECT * FROM users WHERE id = ?" (:sql by-id)))
    (is (= [42] (:params by-id)))
    (is (= "SELECT * FROM users WHERE email = ?" (:sql by-email)))
    (is (= ["user@example.com"] (:params by-email)))))

(deftest defrender-attaches-query-declarations-to-var-metadata
  (let [metadata (query-var-meta 'sql.core 'find-user-by-email)]
    (is (= "core.find-user-by-email" (:query-name metadata)))
    (is (= 'find-user-by-email (:name metadata)))))

(deftest defrender-can-replace-generated-navigation-stub
  (create-ns 'sql.postgresql.public.users.core)
  (let [template (bisql/analyze-template
                  (bisql/load-query "postgresql/public/users/get-by-id.sql" {:base-path "sql"}))
        stub-metadata (define/navigation-stub-metadata template '([]
                                                                  [template-params]))]
    (binding [*ns* (the-ns 'sql.postgresql.public.users.core)]
      (intern *ns* (with-meta 'get-by-id stub-metadata))
      (eval '(bisql.core/defrender "/sql/postgresql/public/users/get-by-id.sql")))
    (is (fn? (query-fn 'sql.postgresql.public.users.core 'get-by-id)))))

(deftest defrender-loads-all-sql-files-under-a-directory-recursively
  (let [expanded (macroexpand '(bisql.core/defrender "/sql/directory-success"))
        expanded-str (pr-str expanded)]
    (is (= 'do (first expanded)))
    (is (not (str/includes? expanded-str "compile-renderer")))
    (is (str/includes? expanded-str "StringBuilder"))
    (is (str/includes? expanded-str "sql.directory-success.core"))
    (is (str/includes? expanded-str "sql.directory-success.nested.core"))))

(deftest defrender-loads-current-namespace-path-recursively
  (create-ns 'sql.directory_success)
  (let [expanded (binding [*ns* (the-ns 'sql.directory_success)]
                   (macroexpand '(bisql.core/defrender)))
        expanded-str (pr-str expanded)]
    (is (= 'do (first expanded)))
    (is (str/includes? expanded-str "sql.directory-success.core"))
    (is (str/includes? expanded-str "sql.directory-success.nested.core"))))

(deftest defrender-loads-relative-path-under-current-namespace
  (create-ns 'sql)
  (let [expanded (binding [*ns* (the-ns 'sql)]
                   (macroexpand '(bisql.core/defrender "directory-success")))
        expanded-str (pr-str expanded)]
    (is (= 'do (first expanded)))
    (is (str/includes? expanded-str "sql.directory-success.core"))
    (is (str/includes? expanded-str "sql.directory-success.nested.core"))))

(deftest defrender-rejects-var-name-collisions-when-loading-a-directory
  (let [error (try
                (let [_ (macroexpand '(bisql.core/defrender "/sql/directory-collision-same-namespace"))]
                  nil)
                (catch clojure.lang.ExceptionInfo ex
                  ex)
                (catch clojure.lang.Compiler$CompilerException ex
                  (ex-cause ex)))
        data (ex-data error)]
    (is (= "Multiple queries resolve to the same var name."
           (ex-message error)))
    (is (= "sql.directory-collision-same-namespace.core" (:namespace data)))
    (is (= 'get-by-id (:var-name data)))
    (is (some #{"sql/directory-collision-same-namespace/first.sql"} (:resource-paths data)))
    (is (some #{"sql/directory-collision-same-namespace/second.sql"} (:resource-paths data)))))

(deftest defrender-rejects-var-name-collisions
  (let [error (try
                (binding [*ns* (the-ns 'sql.postgresql.public.users.core)]
                  (eval '(bisql.core/defrender "/sql/postgresql/public/users/get-by-id.sql")))
                nil
                (catch clojure.lang.Compiler$CompilerException ex
                  ex))
        cause (ex-cause error)]
    (is (= "Query function var name already exists."
           (ex-message cause)))
    (is (= 'get-by-id (:var-name (ex-data cause))))))

(deftest render-query-supports-scalar-bind
  (let [result (bisql/render-query
                {:sql-template "SELECT * FROM users WHERE id = /*$id*/1"}
                {:id 42})]
    (is (= "SELECT * FROM users WHERE id = ?" (:sql result)))
    (is (= [42] (:params result)))))

(deftest evaluate-renderer-matches-render-query-for-scalar-bind
  (let [sql-template "SELECT * FROM users WHERE id = /*$id*/1"
        parsed-template (bisql/parse-template sql-template)
        rendered (bisql/evaluate-renderer parsed-template {:id 42})
        result (bisql/render-query {:sql-template sql-template} {:id 42})]
    (is (= :template (:op parsed-template)))
    (is (= (:sql result) (str/trim (:sql rendered))))
    (is (= (:params result) (:bind-params rendered)))))

(deftest evaluate-renderer-plan-matches-render-query-for-scalar-bind
  (let [sql-template "SELECT * FROM users WHERE id = /*$id*/1"
        plan (bisql/renderer-plan (bisql/parse-template sql-template))
        rendered (bisql/evaluate-renderer-plan plan {:id 42})
        result (bisql/render-query {:sql-template sql-template} {:id 42})]
    (is (= (:sql result) (str/trim (:sql rendered))))
    (is (= (:params result) (:bind-params rendered)))))

(deftest compile-renderer-matches-render-query-for-scalar-bind
  (let [sql-template "SELECT * FROM users WHERE id = /*$id*/1"
        parsed-template (bisql/parse-template sql-template)
        renderer (bisql/compile-renderer parsed-template)
        rendered (renderer {:id 42})
        result (bisql/render-query {:sql-template sql-template} {:id 42})]
    (is (fn? renderer))
    (is (= (:sql result) (str/trim (:sql rendered))))
    (is (= (:params result) (:bind-params rendered)))))

(deftest emit-renderer-form-matches-render-query-for-scalar-bind
  (let [sql-template "SELECT * FROM users WHERE id = /*$id*/1"
        parsed-template (bisql/parse-template sql-template)
        renderer (eval (bisql/emit-renderer-form parsed-template))
        rendered (renderer {:id 42})
        result (bisql/render-query {:sql-template sql-template} {:id 42})]
    (is (fn? renderer))
    (is (= (:sql result) (str/trim (:sql rendered))))
    (is (= (:params result) (:bind-params rendered)))))

(deftest emit-renderer-form-linearizes-top-level-renderer-shape
  (let [sql-template "SELECT * FROM users WHERE id = /*$id*/1"
        emitted-form (bisql/emit-renderer-form (bisql/parse-template sql-template))
        emitted-str (pr-str emitted-form)]
    (is (str/includes? emitted-str "clojure.core/fn"))
    (is (str/includes? emitted-str "StringBuilder"))
    (is (not (str/includes? emitted-str "compiled-nodes#")))
    (is (not (str/includes? emitted-str "loop")))))

(deftest renderer-plan-builds-execution-oriented-steps
  (let [plan (bisql/renderer-plan
              (bisql/parse-template "SELECT * FROM users WHERE id = /*$id*/1"))]
    (is (= :renderer-plan (:op plan)))
    (is (= :select (:statement-kind plan)))
    (is (= [:append-text :append-variable]
           (mapv :op (:steps plan))))
    (is (= "id"
           (-> plan :steps second :parameter-name)))))

(deftest renderer-plan-represents-conditional-branches
  (let [plan (bisql/renderer-plan
              (bisql/parse-template
               (str/join "\n"
                         ["SELECT *"
                          "FROM users"
                          "WHERE 1 = 1"
                          "/*%if active */"
                          "  AND status = /*$status*/'active'"
                          "/*%elseif archived => AND archived_at IS NOT NULL */"
                          "/*%else => AND archived_at IS NULL */"
                          "/*%end */"])))
        branch-step (second (:steps plan))]
    (is (= :branch (:op branch-step)))
    (is (= :where (:context branch-step)))
    (is (= ["active" "archived" nil]
           (mapv :expr (:branches branch-step))))
    (is (= [:append-text :append-variable :append-text]
           (mapv :op (get-in branch-step [:branches 0 :steps]))))
    (is (= [:append-text]
           (mapv :op (get-in branch-step [:branches 1 :steps]))))
    (is (= [:append-text]
           (mapv :op (get-in branch-step [:branches 2 :steps]))))))

(deftest renderer-plan-represents-for-each-steps
  (let [plan (bisql/renderer-plan
              (bisql/parse-template
               (str/join "\n"
                         ["UPDATE users"
                          "SET"
                          "/*%for item in items separating , */"
                          "  /*!item.name*/ = /*$item.value*/'sample'"
                          "/*%end */"
                          "WHERE id = /*$id*/1"])))
        for-step (second (:steps plan))]
    (is (= :for-each (:op for-step)))
    (is (= "item" (:item-name for-step)))
    (is (= "items" (:collection-name for-step)))
    (is (= "," (:separator for-step)))
    (is (= :set (:context for-step)))
    (is (= [:append-text :append-variable :append-text :append-variable :append-text]
           (mapv :op (:steps for-step))))))

(deftest parse-template-annotates-variable-contexts
  (let [parsed-template (bisql/parse-template
            (str/join "\n"
                      ["SELECT *"
                       "FROM users"
                       "WHERE id = /*$id*/1"
                       "LIMIT /*$limit*/100"
                       "OFFSET /*$offset*/0"]))
        variable-nodes (->> (:nodes parsed-template)
                            (filter #(= :variable (:op %))))]
    (is (= :select (:statement-kind parsed-template)))
    (is (= [{:parameter-name "id" :context :where}
            {:parameter-name "limit" :context :limit}
            {:parameter-name "offset" :context :offset}]
           (mapv #(select-keys % [:parameter-name :context]) variable-nodes)))))

(deftest parse-template-annotates-branch-bodies-with-clause-context
  (let [parsed-template (bisql/parse-template
            (str/join "\n"
                      ["SELECT *"
                       "FROM users"
                       "WHERE"
                       "/*%if active */"
                       "  active = /*$active*/true"
                       "/*%end */"]))
        if-node (some #(when (= :if (:op %)) %) (:nodes parsed-template))
        branch-body (get-in if-node [:branches 0 :body])
        variable-node (some #(when (= :variable (:op %)) %) branch-body)]
    (is (= :where (:context if-node)))
    (is (= :select (:statement-kind if-node)))
    (is (= :where (:context variable-node)))))

(deftest parse-template-annotates-statement-kind
  (is (= :select (:statement-kind (bisql/parse-template "SELECT * FROM users"))))
  (is (= :insert (:statement-kind (bisql/parse-template "INSERT INTO users (email) VALUES (/*$email*/'a')"))))
  (is (= :update (:statement-kind (bisql/parse-template "UPDATE users SET email = /*$email*/'a'"))))
  (is (= :delete (:statement-kind (bisql/parse-template "DELETE FROM users WHERE id = /*$id*/1")))))

(deftest render-compiled-query-matches-render-query
  (let [template (bisql/analyze-template
                  {:sql-template "SELECT * FROM users WHERE id = /*$id*/1"})
        renderer (bisql/compile-renderer (bisql/parse-template (:sql-template template)))
        rendered (bisql/render-compiled-query template renderer {:id 42})
        result (bisql/render-query {:sql-template "SELECT * FROM users WHERE id = /*$id*/1"}
                                   {:id 42})]
    (is (= (:sql result) (:sql rendered)))
    (is (= (:params result) (:params rendered)))))

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
                 :status bisql/DEFAULT})]
    (is (= "INSERT INTO users (email, status) VALUES (?, DEFAULT)" (:sql result)))
    (is (= ["alice@example.com"] (:params result)))))

(deftest render-query-supports-all-bind-value
  (let [result (bisql/render-query
                {:sql-template "SELECT * FROM users LIMIT /*$limit*/100"}
                {:limit bisql/ALL})]
    (is (= "SELECT * FROM users LIMIT ALL" (:sql result)))
    (is (= [] (:params result)))))

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
                 {:ids [1 bisql/DEFAULT 3]})
                nil
                (catch clojure.lang.ExceptionInfo ex
                  ex))]
    (is (= "DEFAULT is not allowed inside collection binding."
           (ex-message error)))
    (is (= :ids (:parameter (ex-data error))))
    (is (= "$" (:sigil (ex-data error))))
    (is (= true (:collection? (ex-data error))))))

(deftest render-query-rejects-all-in-collection-binding
  (let [error (try
                (bisql/render-query
                 {:sql-template "SELECT * FROM users WHERE id IN /*$ids*/(1,2,3)"}
                 {:ids [1 bisql/ALL 3]})
                nil
                (catch clojure.lang.ExceptionInfo ex
                  ex))]
    (is (= "ALL is not allowed inside collection binding."
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
    (is (= "core.get-by-id" (:query-name result)))
    (is (= "get-by-id" (:function-name result)))
    (is (= ["core"] (:namespace-suffix result)))
    (is (= "sql/postgresql/public/users/get-by-id.sql"
           (:resource-path result)))
    (is (= "SELECT * FROM users WHERE id = /*$id*/ 1"
           (str/trim (:sql-template result))))))

(deftest load-query-keeps-declarations-in-sql-template
  (let [result (bisql/load-query "example-declarations-valid.sql"
                                 {:base-path "sql"})]
    (is (= (str/join "\n"
                     ["/*:doc"
                      "\"Loads a user by id.\""
                      "*/"
                      "/*:tags"
                      "[:example :user]"
                      "*/"
                      "/*:returns"
                      ":one"
                      "*/"
                      "/*:cardinality"
                      ":one"
                      "*/"
                      "SELECT * FROM users WHERE id = /*$id*/1"])
           (str/trim (:sql-template result))))))

(deftest load-queries-supports-multiple-templates-in-a-single-file
  (let [queries (bisql/load-queries "example-multi-queries.sql"
                                    {:base-path "sql"})]
    (is (= #{"core.find-user-by-id" "core.find-user-by-email"}
           (set (keys queries))))
    (is (= "core.find-user-by-id"
           (:query-name (get queries "core.find-user-by-id"))))
    (is (= "sql/example-multi-queries.sql"
           (:resource-path (get queries "core.find-user-by-id"))))))

(deftest load-queries-can-select-a-template-by-query-name
  (let [result (get (bisql/load-queries "example-multi-queries.sql"
                                        {:base-path "sql"})
                    "core.find-user-by-email")]
    (is (= "core.find-user-by-email" (:query-name result)))
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
    (is (= ["core.find-user-by-email" "core.find-user-by-id"]
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

(deftest render-query-returns-declarations-in-meta
  (let [result (bisql/render-query
                (bisql/load-query "example-declarations-valid.sql"
                                  {:base-path "sql"})
                {:id 42})]
    (is (= {:doc "Loads a user by id."
            :cardinality :one
            :tags [:example :user]
            :returns :one}
           (:meta result)))
    (is (= "SELECT * FROM users WHERE id = ?"
           (:sql result)))
    (is (= [42] (:params result)))
    (is (= "core.example-declarations-valid" (:query-name result)))
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
    (is (= "Invalid declaration value."
           (ex-message error)))
    (is (= "core.example-declarations-invalid-meta"
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
    (is (= "core.example-declarations-duplicate"
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
    (is (= "Declaration blocks must appear at the beginning of the SQL template block."
           (ex-message error)))
    (is (= "core.example-declarations-trailing"
           (:query-name (ex-data error))))
    (is (= "sql/example-declarations-trailing.sql"
           (:resource-path (ex-data error))))))

(deftest render-query-rejects-invalid-declaration-values
  (let [error (try
                (bisql/render-query
                 (bisql/load-query "example-declarations-invalid-meta.sql"
                                   {:base-path "sql"})
                 {})
                nil
                (catch clojure.lang.ExceptionInfo ex
                  ex))]
    (is (= "Invalid declaration value."
           (ex-message error)))
    (is (= :tags (:directive (ex-data error))))))

(deftest render-query-supports-generic-declarations
  (let [result (bisql/render-query
                (bisql/load-query "example-declarations-unknown.sql"
                                  {:base-path "sql"})
                {})]
    (is (= {:foo :bar}
           (:meta result)))
    (is (= "SELECT 1"
           (:sql result)))))

(deftest render-query-rejects-declarations-with-space-before-name
  (let [error (try
                (bisql/render-query
                 (bisql/load-query "example-declarations-space-before-name.sql"
                                   {:base-path "sql"})
                 {})
                nil
                (catch clojure.lang.ExceptionInfo ex
                  ex))]
    (is (= "Invalid declaration block."
           (ex-message error)))
    (is (= "core.example-declarations-space-before-name"
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

(deftest evaluate-renderer-matches-render-query-for-if-blocks
  (let [sql-template (str/join "\n"
                               ["SELECT *"
                                "FROM users"
                                "WHERE 1 = 1"
                                "/*%if name */"
                                "  AND name = /*$name*/'foo'"
                                "/*%end */"])
        parsed-template (bisql/parse-template sql-template)
        rendered (bisql/evaluate-renderer parsed-template {:name "Alice"})
        result (bisql/render-query {:sql-template sql-template} {:name "Alice"})]
    (is (= (:sql result) (str/trim (:sql rendered))))
    (is (= (:params result) (:bind-params rendered)))))

(deftest evaluate-renderer-plan-matches-render-query-for-if-blocks
  (let [sql-template (str/join "\n"
                               ["SELECT *"
                                "FROM users"
                                "WHERE 1 = 1"
                                "/*%if name */"
                                "  AND name = /*$name*/'foo'"
                                "/*%end */"])
        plan (bisql/renderer-plan (bisql/parse-template sql-template))
        rendered (bisql/evaluate-renderer-plan plan {:name "Alice"})
        result (bisql/render-query {:sql-template sql-template} {:name "Alice"})]
    (is (= (:sql result) (str/trim (:sql rendered))))
    (is (= (:params result) (:bind-params rendered)))))

(deftest compile-renderer-matches-render-query-for-if-blocks
  (let [sql-template (str/join "\n"
                               ["SELECT *"
                                "FROM users"
                                "WHERE 1 = 1"
                                "/*%if name */"
                                "  AND name = /*$name*/'foo'"
                                "/*%end */"])
        parsed-template (bisql/parse-template sql-template)
        renderer (bisql/compile-renderer parsed-template)
        rendered (renderer {:name "Alice"})
        result (bisql/render-query {:sql-template sql-template} {:name "Alice"})]
    (is (= (:sql result) (str/trim (:sql rendered))))
    (is (= (:params result) (:bind-params rendered)))))

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

(deftest render-query-removes-where-before-falsy-if-block
  (let [result (bisql/render-query
                {:sql-template (str/join "\n"
                                         ["SELECT *"
                                          "FROM users"
                                          "WHERE"
                                          "/*%if active */"
                                          "  active = true"
                                          "/*%end */"])}
                {:active false})]
    (is (= (str/join "\n"
                     ["SELECT *"
                      "FROM users"])
           (:sql result)))
    (is (= [] (:params result)))))

(deftest render-query-removes-following-and-after-falsy-if-block
  (let [result (bisql/render-query
                {:sql-template (str/join "\n"
                                         ["SELECT *"
                                          "FROM users"
                                          "WHERE"
                                          "/*%if active */"
                                          "  active = true"
                                          "/*%end */"
                                          "AND status = /*$status*/'active'"])}
                {:active false
                 :status "active"})]
    (is (= (str/join "\n"
                     ["SELECT *"
                      "FROM users"
                      "WHERE"
                      "status = ?"])
           (:sql result)))
    (is (= ["active"] (:params result)))))

(deftest render-query-removes-following-or-after-falsy-if-block
  (let [result (bisql/render-query
                {:sql-template (str/join "\n"
                                         ["SELECT *"
                                          "FROM users"
                                          "WHERE"
                                          "/*%if active */"
                                          "  active = true"
                                          "/*%end */"
                                          "OR status = /*$status*/'active'"])}
                {:active false
                 :status "active"})]
    (is (= (str/join "\n"
                     ["SELECT *"
                      "FROM users"
                      "WHERE"
                      "status = ?"])
           (:sql result)))
    (is (= ["active"] (:params result)))))

(deftest render-query-removes-having-before-falsy-if-block
  (let [result (bisql/render-query
                {:sql-template (str/join "\n"
                                         ["SELECT status, count(*)"
                                          "FROM users"
                                          "GROUP BY status"
                                          "HAVING"
                                          "/*%if min-count */"
                                          "  count(*) >= /*$min-count*/1"
                                          "/*%end */"])}
                {:min-count nil})]
    (is (= (str/join "\n"
                     ["SELECT status, count(*)"
                      "FROM users"
                      "GROUP BY status"])
           (:sql result)))
    (is (= [] (:params result)))))

(deftest render-query-removes-where-after-multiple-falsy-or-blocks
  (let [result (bisql/render-query
                {:sql-template (str/join "\n"
                                         ["SELECT *"
                                          "FROM users"
                                          "WHERE"
                                          "/*%if active */"
                                          "  active = true"
                                          "/*%end */"
                                          "OR"
                                          "/*%if status */"
                                          "  status = /*$status*/'active'"
                                          "/*%end */"])}
                {:active false
                 :status nil})]
    (is (= (str/join "\n"
                     ["SELECT *"
                      "FROM users"])
           (:sql result)))
    (is (= [] (:params result)))))

(deftest render-query-removes-having-after-multiple-falsy-or-blocks
  (let [result (bisql/render-query
                {:sql-template (str/join "\n"
                                         ["SELECT status, count(*)"
                                          "FROM users"
                                          "GROUP BY status"
                                          "HAVING"
                                          "/*%if min-count */"
                                          "  count(*) >= /*$min-count*/1"
                                          "/*%end */"
                                          "OR"
                                          "/*%if max-count */"
                                          "  count(*) <= /*$max-count*/10"
                                          "/*%end */"])}
                {:min-count nil
                 :max-count nil})]
    (is (= (str/join "\n"
                     ["SELECT status, count(*)"
                      "FROM users"
                      "GROUP BY status"])
           (:sql result)))
    (is (= [] (:params result)))))

(deftest render-query-keeps-parenthesized-or-condition-after-falsy-if-block
  (let [result (bisql/render-query
                {:sql-template (str/join "\n"
                                         ["SELECT *"
                                          "FROM users"
                                          "WHERE ("
                                          "/*%if active */"
                                          "  active = true"
                                          "/*%end */"
                                          "OR status = /*$status*/'active'"
                                          ")"])}
                {:active false
                 :status "active"})]
    (is (= (str/join "\n"
                     ["SELECT *"
                      "FROM users"
                      "WHERE ("
                      "status = ?"
                      ")"])
           (:sql result)))
    (is (= ["active"] (:params result)))))

(deftest render-query-supports-else-branch
  (let [result (bisql/render-query
                {:sql-template (str/join "\n"
                                         ["SELECT *"
                                          "FROM users"
                                          "WHERE"
                                          "/*%if active */"
                                          "  active = true"
                                          "/*%else */"
                                          "  active = false"
                                          "/*%end */"])}
                {:active false})]
    (is (= (str/join "\n"
                     ["SELECT *"
                      "FROM users"
                      "WHERE"
                      "  active = false"])
           (:sql result)))
    (is (= [] (:params result)))))

(deftest render-query-supports-inline-else-fragment
  (let [result (bisql/render-query
                {:sql-template (str/join "\n"
                                         ["SELECT *"
                                          "FROM users"
                                          "WHERE"
                                          "/*%if active */"
                                          "  active = true"
                                          "/*%else => status = 'inactive' */"
                                          "/*%end */"])}
                {:active false})]
    (is (= (str/join "\n"
                     ["SELECT *"
                      "FROM users"
                      "WHERE"
                      "status = 'inactive'"])
           (:sql result)))
    (is (= [] (:params result)))))

(deftest render-query-supports-else-branch-with-operator-trimming
  (let [result (bisql/render-query
                {:sql-template (str/join "\n"
                                         ["SELECT *"
                                          "FROM users"
                                          "WHERE"
                                          "/*%if active */"
                                          "  active = true"
                                          "/*%end */"
                                          "AND status = /*$status*/'active'"])}
                {:active false
                 :status "active"})]
    (is (= (str/join "\n"
                     ["SELECT *"
                      "FROM users"
                      "WHERE"
                      "status = ?"])
           (:sql result)))
    (is (= ["active"] (:params result)))))

(deftest render-query-rejects-elseif-after-else
  (let [error (try
                (bisql/render-query
                 {:sql-template (str/join "\n"
                                          ["SELECT *"
                                           "FROM users"
                                           "/*%if active */"
                                           "  active = true"
                                           "/*%else */"
                                           "  active = false"
                                           "/*%elseif pending */"
                                           "  pending = true"
                                           "/*%end */"])}
                 {:active false
                  :pending true})
                nil
                (catch clojure.lang.ExceptionInfo ex
                  ex))]
    (is (= "Conditional block cannot contain elseif after else."
           (ex-message error)))))

(deftest render-query-rejects-multiple-else-blocks
  (let [error (try
                (bisql/render-query
                 {:sql-template (str/join "\n"
                                          ["SELECT *"
                                           "FROM users"
                                           "/*%if active */"
                                           "  active = true"
                                           "/*%else */"
                                           "  active = false"
                                           "/*%else */"
                                           "  pending = true"
                                           "/*%end */"])}
                 {:active false})
                nil
                (catch clojure.lang.ExceptionInfo ex
                  ex))]
    (is (= "Conditional block cannot contain multiple else blocks."
           (ex-message error)))))

(deftest render-query-rejects-mixed-inline-and-block-else
  (let [error (try
                (bisql/render-query
                 {:sql-template (str/join "\n"
                                          ["SELECT *"
                                           "FROM users"
                                           "WHERE"
                                           "/*%if active */"
                                           "  active = true"
                                           "/*%else => status = 'inactive' */"
                                           "  status = 'pending'"
                                           "/*%end */"])}
                {:active false})
                nil
                (catch clojure.lang.ExceptionInfo ex
                  ex))]
    (is (= "Conditional branch cannot mix inline fragments with block body content."
           (ex-message error)))))

(deftest render-query-rejects-mixed-inline-and-block-elseif
  (let [error (try
                (bisql/render-query
                 {:sql-template (str/join "\n"
                                          ["SELECT *"
                                           "FROM users"
                                           "WHERE"
                                           "/*%if active */"
                                           "  active = true"
                                           "/*%elseif pending => status = 'pending' */"
                                           "  status = 'inactive'"
                                           "/*%end */"])}
                 {:active false
                  :pending true})
                nil
                (catch clojure.lang.ExceptionInfo ex
                  ex))]
    (is (= "Conditional branch cannot mix inline fragments with block body content."
           (ex-message error)))))

(deftest render-query-supports-dot-path-bind-lookup
  (let [result (bisql/render-query
                {:sql-template "SELECT * FROM users WHERE status = /*$user.profile.status*/'active'"}
                {:user {:profile {"status" "active"}}})]
    (is (= "SELECT * FROM users WHERE status = ?" (:sql result)))
    (is (= ["active"] (:params result)))))

(deftest render-query-supports-for-blocks
  (let [result (bisql/render-query
                {:sql-template (str/join "\n"
                                         ["UPDATE users"
                                          "SET"
                                          "/*%for item in items separating , */"
                                          "  /*!item.name*/ = /*$item.value*/'sample'"
                                          "/*%end */"
                                          "WHERE id = /*$id*/1"])}
                {:id 42
                 :items [{:name "display_name" :value "Alice"}
                         {:name "status" :value "active"}]})]
    (is (= (str/join "\n"
                     ["UPDATE users"
                      "SET"
                      "  display_name = ?,"
                      "  status = ?"
                      "WHERE id = ?"])
           (:sql result)))
    (is (= ["Alice" "active" 42] (:params result)))))

(deftest render-query-does-not-trim-trailing-comma-from-for-body
  (let [result (bisql/render-query
                {:sql-template (str/join "\n"
                                         ["UPDATE users"
                                          "SET"
                                          "/*%for item in items */"
                                          "  /*!item.name*/ = /*$item.value*/'sample',"
                                          "/*%end */"
                                          "WHERE id = /*$id*/1"])}
                {:id 42
                 :items [{:name "display_name" :value "Alice"}]})]
    (is (= (str/join "\n"
                     ["UPDATE users"
                      "SET"
                      "  display_name = ?,"
                      ""
                      "WHERE id = ?"])
           (:sql result)))
    (is (= ["Alice" 42] (:params result)))))

(deftest render-query-does-not-trim-trailing-and-from-for-body
  (let [result (bisql/render-query
                {:sql-template (str/join "\n"
                                         ["SELECT *"
                                          "FROM users"
                                          "WHERE"
                                          "/*%for item in items */"
                                          "  /*!item.name*/ = /*$item.value*/'sample' AND"
                                          "/*%end */"
                                          "status = /*$status*/'active'"])}
                {:items [{:name "display_name" :value "Alice"}]
                 :status "active"})]
    (is (= (str/join "\n"
                     ["SELECT *"
                      "FROM users"
                      "WHERE"
                      "  display_name = ? AND"
                      ""
                      "status = ?"])
           (:sql result)))
    (is (= ["Alice" "active"] (:params result)))))

(deftest evaluate-renderer-matches-render-query-for-for-blocks
  (let [sql-template (str/join "\n"
                               ["UPDATE users"
                                "SET"
                                "/*%for item in items separating , */"
                                "  /*!item.name*/ = /*$item.value*/'sample'"
                                "/*%end */"
                                "WHERE id = /*$id*/1"])
        parsed-template (bisql/parse-template sql-template)
        rendered (bisql/evaluate-renderer parsed-template {:id 42
                                        :items [{:name "display_name" :value "Alice"}
                                                {:name "status" :value "active"}]})
        result (bisql/render-query {:sql-template sql-template}
                                   {:id 42
                                    :items [{:name "display_name" :value "Alice"}
                                            {:name "status" :value "active"}]})]
    (is (= (:sql result) (str/trim (:sql rendered))))
    (is (= (:params result) (:bind-params rendered)))))

(deftest evaluate-renderer-plan-matches-render-query-for-for-blocks
  (let [sql-template (str/join "\n"
                               ["UPDATE users"
                                "SET"
                                "/*%for item in items separating , */"
                                "  /*!item.name*/ = /*$item.value*/'sample'"
                                "/*%end */"
                                "WHERE id = /*$id*/1"])
        plan (bisql/renderer-plan (bisql/parse-template sql-template))
        rendered (bisql/evaluate-renderer-plan plan {:id 42
                                                     :items [{:name "display_name" :value "Alice"}
                                                             {:name "status" :value "active"}]})
        result (bisql/render-query {:sql-template sql-template}
                                   {:id 42
                                    :items [{:name "display_name" :value "Alice"}
                                            {:name "status" :value "active"}]})]
    (is (= (:sql result) (str/trim (:sql rendered))))
    (is (= (:params result) (:bind-params rendered)))))

(deftest compile-renderer-matches-render-query-for-for-blocks
  (let [sql-template (str/join "\n"
                               ["UPDATE users"
                                "SET"
                                "/*%for item in items separating , */"
                                "  /*!item.name*/ = /*$item.value*/'sample'"
                                "/*%end */"
                                "WHERE id = /*$id*/1"])
        parsed-template (bisql/parse-template sql-template)
        renderer (bisql/compile-renderer parsed-template)
        rendered (renderer {:id 42
                            :items [{:name "display_name" :value "Alice"}
                                    {:name "status" :value "active"}]})
        result (bisql/render-query {:sql-template sql-template}
                                   {:id 42
                                    :items [{:name "display_name" :value "Alice"}
                                            {:name "status" :value "active"}]})]
    (is (= (:sql result) (str/trim (:sql rendered))))
    (is (= (:params result) (:bind-params rendered)))))

(deftest render-query-removes-where-for-empty-for-block
  (let [result (bisql/render-query
                {:sql-template (str/join "\n"
                                         ["SELECT *"
                                          "FROM users"
                                          "WHERE"
                                          "/*%for item in items */"
                                          "  /*!item.name*/ = /*$item.value*/'sample'"
                                          "/*%end */"])}
                {:items []})]
    (is (= (str/join "\n"
                     ["SELECT *"
                      "FROM users"])
           (:sql result)))
    (is (= [] (:params result)))))

(deftest render-query-removes-following-and-for-empty-for-block
  (let [result (bisql/render-query
                {:sql-template (str/join "\n"
                                         ["SELECT *"
                                          "FROM users"
                                          "WHERE"
                                          "/*%for item in items */"
                                          "  /*!item.name*/ = /*$item.value*/'sample'"
                                          "/*%end */"
                                          "AND status = /*$status*/'active'"])}
                {:items []
                 :status "active"})]
    (is (= (str/join "\n"
                     ["SELECT *"
                      "FROM users"
                      "WHERE"
                      "status = ?"])
           (:sql result)))
    (is (= ["active"] (:params result)))))

(deftest render-query-rejects-nested-for-blocks
  (let [error (try
                (bisql/render-query
                 {:sql-template (str/join "\n"
                                          ["UPDATE users"
                                           "SET"
                                           "/*%for item in items */"
                                           "  /*%for sub in item.values */"
                                           "    /*!sub.name*/ = /*$sub.value*/'sample',"
                                           "  /*%end */"
                                           "/*%end */"])}
                 {:items [{:values [{:name "status" :value "active"}]}]})
                nil
                (catch clojure.lang.ExceptionInfo ex
                  ex))]
    (is (= "Nested for blocks are not supported."
           (ex-message error)))))

(deftest render-query-rejects-empty-for-block-in-set-clause
  (let [error (try
                (bisql/render-query
                 {:sql-template (str/join "\n"
                                         ["UPDATE users"
                                          "SET"
                                          "/*%for item in items */"
                                           "  /*!item.name*/ = /*$item.value*/'sample'"
                                           "/*%end */"
                                           "WHERE id = /*$id*/1"])}
                 {:id 42
                  :items []})
                nil
                (catch clojure.lang.ExceptionInfo ex
                  ex))]
    (is (= "Empty for block is not allowed in SET clause."
           (ex-message error)))
    (is (= :items (:parameter (ex-data error))))
    (is (= :item (:item (ex-data error))))))

(deftest render-query-rejects-empty-for-block-in-values-clause
  (let [error (try
                (bisql/render-query
                 {:sql-template (str/join "\n"
                                          ["INSERT INTO users (email, status)"
                                           "VALUES"
                                           "/*%for row in rows separating , */"
                                           "("
                                           "  /*$row.email*/'a@example.com',"
                                           "  /*$row.status*/'active'"
                                           ")"
                                           "/*%end */"
                                           "RETURNING *"])}
                 {:rows []})
                nil
                (catch clojure.lang.ExceptionInfo ex
                  ex))]
    (is (= "Empty for block is not allowed in VALUES clause."
           (ex-message error)))
    (is (= :rows (:parameter (ex-data error))))
    (is (= :row (:item (ex-data error))))))
(deftest render-query-supports-elseif-branch
  (let [result (bisql/render-query
                {:sql-template (str/join "\n"
                                         ["SELECT *"
                                          "FROM users"
                                          "WHERE"
                                          "/*%if active */"
                                          "  active = true"
                                          "/*%elseif pending */"
                                          "  status = 'pending'"
                                          "/*%else */"
                                          "  status = 'inactive'"
                                          "/*%end */"])}
                {:active false
                 :pending true})]
    (is (= (str/join "\n"
                     ["SELECT *"
                      "FROM users"
                      "WHERE"
                      "  status = 'pending'"])
           (:sql result)))
    (is (= [] (:params result)))))

(deftest render-query-supports-inline-elseif-fragment
  (let [result (bisql/render-query
                {:sql-template (str/join "\n"
                                         ["SELECT *"
                                          "FROM users"
                                          "WHERE"
                                          "/*%if active */"
                                          "  active = true"
                                          "/*%elseif pending => status = 'pending' */"
                                          "/*%else => status = 'inactive' */"
                                          "/*%end */"])}
                {:active false
                 :pending true})]
    (is (= (str/join "\n"
                     ["SELECT *"
                      "FROM users"
                      "WHERE"
                      "status = 'pending'"])
           (:sql result)))
    (is (= [] (:params result)))))
