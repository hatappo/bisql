(ns bisql.crud-test
  (:require [bisql.crud :as crud]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest generate-crud-builds-minimal-select-templates
  (with-redefs [bisql.crud/load-schema-metadata
                (fn [_datasource _schema]
                  {:columns-by-table
                   {"users" [{:column_name "id" :data_type "bigint" :is_identity "YES"}
                             {:column_name "email" :data_type "text"}
                             {:column_name "display_name" :data_type "text"}
                             {:column_name "status" :data_type "text"}]
                    "orders" [{:column_name "id" :data_type "bigint" :is_identity "YES"}
                              {:column_name "user_id" :data_type "bigint"}
                              {:column_name "order_number" :data_type "text"}
                              {:column_name "state" :data_type "text"}
                              {:column_name "total_amount" :data_type "numeric"}
                              {:column_name "created_at" :data_type "timestamp with time zone"}]
                    "user_devices" [{:column_name "id" :data_type "bigint" :is_identity "YES"}
                                    {:column_name "user_id" :data_type "bigint"}
                                    {:column_name "device_type" :data_type "text"}
                                    {:column_name "device_identifier" :data_type "text"}
                                    {:column_name "status" :data_type "text"}
                                    {:column_name "last_seen_at" :data_type "timestamp with time zone"}]
                    "user_roles" [{:column_name "user_id" :data_type "bigint"}
                                  {:column_name "role_code" :data_type "text"}
                                  {:column_name "granted_at" :data_type "timestamp with time zone"}
                                  {:column_name "granted_by" :data_type "bigint"}]}
                   :constraints-by-table
                   {"users" [{:constraint_type "PRIMARY KEY"
                              :column_names ["id"]}
                             {:constraint_type "UNIQUE"
                              :column_names ["email"]}]
                    "orders" [{:constraint_type "PRIMARY KEY"
                               :column_names ["id"]}]
                    "user_devices" [{:constraint_type "PRIMARY KEY"
                                     :column_names ["id"]}
                                    {:constraint_type "UNIQUE"
                                     :column_names ["user_id" "device_identifier"]}]
                    "user_roles" [{:constraint_type "PRIMARY KEY"
                                   :column_names ["user_id" "role_code"]}]}
                   :indexes-by-table
                   {"users" [{:column_names ["status"]}]
                    "orders" [{:column_names ["user_id"]}
                              {:column_names ["state" "created_at"]}]
                    "user_devices" [{:column_names ["status" "last_seen_at"]}
                                    {:column_names ["user_id" "last_seen_at"]}
                                    {:column_names ["status" "device_type" "last_seen_at"]}]
                    "user_roles" []}})]
    (let [result (crud/generate-crud ::datasource {:schema "public"})
          templates (:templates result)
          names (set (map :name templates))]
      (is (= "postgresql" (:dialect result)))
      (is (= "public" (:schema result)))
      (is (contains? names "crud.insert"))
      (is (contains? names "crud.insert-many"))
      (is (contains? names "crud.update-by-id"))
      (is (contains? names "crud.update-by-email"))
      (is (contains? names "crud.delete-by-id"))
      (is (contains? names "crud.delete-by-email"))
      (is (contains? names "crud.get-by-id"))
      (is (contains? names "crud.get-by-email"))
      (is (contains? names "crud.get-by-user-id-and-device-identifier"))
      (is (contains? names "crud.get-by-user-id-and-role-code"))
      (is (contains? names "crud.list-by-user-id-order-by-device-identifier"))
      (is (contains? names "crud.list-by-user-id-order-by-last-seen-at"))
      (is (contains? names "crud.list-by-user-id-and-last-seen-at"))
      (is (contains? names "crud.list-order-by-id"))
      (is (contains? names "crud.list-order-by-email"))
      (is (contains? names "crud.list-order-by-state-and-created-at"))
      (is (contains? names "crud.list-order-by-status-and-last-seen-at"))
      (is (contains? names "crud.list-order-by-user-id-and-last-seen-at"))
      (is (contains? names "crud.list-order-by-status-and-device-type-and-last-seen-at"))
      (is (contains? names "crud.list-order-by-user-id-and-device-identifier"))
      (is (contains? names "crud.list-by-status-order-by-last-seen-at"))
      (is (contains? names "crud.list-by-status-order-by-device-type-and-last-seen-at"))
      (is (contains? names "crud.list-by-status-and-device-type"))
      (is (contains? names "crud.list-by-status"))
      (is (contains? names "crud.list"))
      (is (contains? names "crud.list-by-status-and-last-seen-at"))
      (is (contains? names "crud.list-by-user-id"))
      (is (contains? names "crud.list-by-state"))
      (is (contains? names "crud.list-by-state-and-created-at"))
      (is (contains? names "crud.update-by-user-id-and-device-identifier"))
      (is (contains? names "crud.update-by-user-id-and-role-code"))
      (is (contains? names "crud.delete-by-user-id-and-device-identifier"))
      (is (contains? names "crud.delete-by-user-id-and-role-code"))
      (testing "insert template excludes identity columns"
        (let [template (some #(when (and (= "orders" (:table %))
                                         (= "crud.insert" (:name %)))
                                %)
                             templates)]
          (is (= "crud.insert" (:query-name template)))
          (is (= ["user_id" "order_number" "state" "total_amount" "created_at"]
                 (:columns template)))
          (is (= (str "INSERT INTO orders (\n"
                      "  user_id,\n"
                      "  order_number,\n"
                      "  state,\n"
                      "  total_amount,\n"
                      "  created_at\n"
                      ")\n"
                      "VALUES (\n"
                      "  /*$user-id*/1,\n"
                      "  /*$order-number*/'sample',\n"
                      "  /*$state*/'sample',\n"
                      "  /*$total-amount*/1,\n"
                      "  /*$created-at*/CURRENT_TIMESTAMP\n"
                      ")\n"
                      "RETURNING *")
                 (:sql-template template)))))
      (testing "insert-many template renders bulk values with for rows"
        (let [template (some #(when (and (= "orders" (:table %))
                                         (= "crud.insert-many" (:name %)))
                                %)
                             templates)]
          (is (= "crud.insert-many" (:query-name template)))
          (is (= {:cardinality :many} (:meta template)))
          (is (= ["user_id" "order_number" "state" "total_amount" "created_at"]
                 (:columns template)))
          (is (= (str "INSERT INTO orders (\n"
                      "  user_id,\n"
                      "  order_number,\n"
                      "  state,\n"
                      "  total_amount,\n"
                      "  created_at\n"
                      ")\n"
                      "VALUES\n"
                      "/*%for row in rows */\n"
                      "(\n"
                      "  /*$row.user-id*/1,\n"
                      "  /*$row.order-number*/'sample',\n"
                      "  /*$row.state*/'sample',\n"
                      "  /*$row.total-amount*/1,\n"
                      "  /*$row.created-at*/CURRENT_TIMESTAMP\n"
                      "),\n"
                      "/*%end */\n"
                      "RETURNING *")
                 (:sql-template template)))))
      (testing "update template uses plain bind variables"
        (let [template (some #(when (and (= "users" (:table %))
                                         (= "crud.update-by-id" (:name %)))
                                %)
                             templates)]
          (is (= ["email" "display_name" "status"] (:set-columns template)))
          (is (= (str "UPDATE users\n"
                      "SET email = /*$email*/'user@example.com'\n"
                      "  , display_name = /*$display-name*/'sample'\n"
                      "  , status = /*$status*/'sample'\n"
                      "WHERE id = /*$id*/1\n"
                      "RETURNING *")
                 (:sql-template template)))))
      (testing "left-prefix list query keeps natural order and limit"
        (let [template (some #(when (= "crud.list-by-state" (:name %)) %) templates)]
          (is (= "crud.list-by-state" (:query-name template)))
          (is (= ["state"] (:columns template)))
          (is (= (str "SELECT * FROM orders\n"
                      "WHERE state = /*$state*/'sample'\n"
                      "ORDER BY created_at\n"
                      "LIMIT /*$limit*/100\n"
                      "OFFSET /*$offset*/0")
                 (:sql-template template)))))
      (testing "zero-prefix list query is generated for composite indexes"
        (let [template (some #(when (= "crud.list" (:name %)) %) templates)]
          (is (= "crud.list" (:query-name template)))
          (is (= [] (:columns template)))
          (is (= (str "SELECT * FROM user_roles\n"
                      "ORDER BY user_id, role_code\n"
                      "LIMIT /*$limit*/100\n"
                      "OFFSET /*$offset*/0")
                 (:sql-template template)))))
      (testing "composite unique and composite index generate expected templates"
        (let [get-template (some #(when (= "crud.get-by-user-id-and-device-identifier" (:name %)) %) templates)
              update-template (some #(when (= "crud.update-by-user-id-and-device-identifier" (:name %)) %) templates)
              delete-template (some #(when (= "crud.delete-by-user-id-and-device-identifier" (:name %)) %) templates)
              list-from-two-column-index-template (some #(when (= "crud.list-order-by-status-and-last-seen-at" (:name %))
                                                           %)
                                                        templates)
              list-from-three-column-index-template (some #(when (= "crud.list-order-by-status-and-device-type-and-last-seen-at" (:name %))
                                                             %)
                                                          templates)
              list-by-user-id-from-unique-template (some #(when (= "crud.list-by-user-id-order-by-device-identifier" (:name %))
                                                            %)
                                                         templates)
              list-by-user-id-from-index-template (some #(when (= "crud.list-by-user-id-order-by-last-seen-at" (:name %))
                                                           %)
                                                        templates)
              list-by-user-id-and-last-seen-at-template (some #(when (= "crud.list-by-user-id-and-last-seen-at" (:name %))
                                                                 %)
                                                              templates)
              list-by-status-from-two-column-index (some #(when (= "crud.list-by-status-order-by-last-seen-at" (:name %))
                                                            %)
                                                         templates)
              list-by-status-from-three-column-index (some #(when (= "crud.list-by-status-order-by-device-type-and-last-seen-at" (:name %))
                                                              %)
                                                           templates)
              list-by-status-and-device-type-template (some #(when (= "crud.list-by-status-and-device-type" (:name %))
                                                               %)
                                                            templates)
              list-with-order-template (some #(when (= "crud.list-by-status-and-last-seen-at" (:name %))
                                                %)
                                             templates)]
          (is (= (str "SELECT * FROM user_devices\n"
                      "WHERE user_id = /*$user-id*/1\n"
                      "  AND device_identifier = /*$device-identifier*/'sample'")
                 (:sql-template get-template)))
          (is (= ["device_type" "status" "last_seen_at"] (:set-columns update-template)))
          (is (= (str "UPDATE user_devices\n"
                      "SET device_type = /*$device-type*/'sample'\n"
                      "  , status = /*$status*/'sample'\n"
                      "  , last_seen_at = /*$last-seen-at*/CURRENT_TIMESTAMP\n"
                      "WHERE user_id = /*$user-id*/1\n"
                      "  AND device_identifier = /*$device-identifier*/'sample'\n"
                      "RETURNING *")
                 (:sql-template update-template)))
          (is (= (str "DELETE FROM user_devices\n"
                      "WHERE user_id = /*$user-id*/1\n"
                      "  AND device_identifier = /*$device-identifier*/'sample'\n"
                      "RETURNING *")
                 (:sql-template delete-template)))
          (is (= (str "SELECT * FROM user_devices\n"
                      "ORDER BY status, last_seen_at\n"
                      "LIMIT /*$limit*/100\n"
                      "OFFSET /*$offset*/0")
                 (:sql-template list-from-two-column-index-template)))
          (is (= (str "SELECT * FROM user_devices\n"
                      "ORDER BY status, device_type, last_seen_at\n"
                      "LIMIT /*$limit*/100\n"
                      "OFFSET /*$offset*/0")
                 (:sql-template list-from-three-column-index-template)))
          (is (= (str "SELECT * FROM user_devices\n"
                      "WHERE user_id = /*$user-id*/1\n"
                      "ORDER BY device_identifier\n"
                      "LIMIT /*$limit*/100\n"
                      "OFFSET /*$offset*/0")
                 (:sql-template list-by-user-id-from-unique-template)))
          (is (= (str "SELECT * FROM user_devices\n"
                      "WHERE user_id = /*$user-id*/1\n"
                      "ORDER BY last_seen_at\n"
                      "LIMIT /*$limit*/100\n"
                      "OFFSET /*$offset*/0")
                 (:sql-template list-by-user-id-from-index-template)))
          (is (= (str "SELECT * FROM user_devices\n"
                      "WHERE user_id = /*$user-id*/1\n"
                      "  AND last_seen_at = /*$last-seen-at*/CURRENT_TIMESTAMP\n"
                      "LIMIT /*$limit*/100\n"
                      "OFFSET /*$offset*/0")
                 (:sql-template list-by-user-id-and-last-seen-at-template)))
          (is (= (str "SELECT * FROM user_devices\n"
                      "WHERE status = /*$status*/'sample'\n"
                      "ORDER BY last_seen_at\n"
                      "LIMIT /*$limit*/100\n"
                      "OFFSET /*$offset*/0")
                 (:sql-template list-by-status-from-two-column-index)))
          (is (= (str "SELECT * FROM user_devices\n"
                      "WHERE status = /*$status*/'sample'\n"
                      "ORDER BY device_type, last_seen_at\n"
                      "LIMIT /*$limit*/100\n"
                      "OFFSET /*$offset*/0")
                 (:sql-template list-by-status-from-three-column-index)))
          (is (= (str "SELECT * FROM user_devices\n"
                      "WHERE status = /*$status*/'sample'\n"
                      "  AND device_type = /*$device-type*/'sample'\n"
                      "ORDER BY last_seen_at\n"
                      "LIMIT /*$limit*/100\n"
                      "OFFSET /*$offset*/0")
                 (:sql-template list-by-status-and-device-type-template)))
          (is (= (str "SELECT * FROM user_devices\n"
                      "WHERE status = /*$status*/'sample'\n"
                      "  AND last_seen_at = /*$last-seen-at*/CURRENT_TIMESTAMP\n"
                      "LIMIT /*$limit*/100\n"
                      "OFFSET /*$offset*/0")
                 (:sql-template list-with-order-template)))))
      (testing "composite primary key generates get, update, and delete templates"
        (let [get-template (some #(when (= "crud.get-by-user-id-and-role-code" (:name %)) %) templates)
              update-template (some #(when (= "crud.update-by-user-id-and-role-code" (:name %)) %) templates)
              delete-template (some #(when (= "crud.delete-by-user-id-and-role-code" (:name %)) %) templates)]
          (is (= (str "SELECT * FROM user_roles\n"
                      "WHERE user_id = /*$user-id*/1\n"
                      "  AND role_code = /*$role-code*/'sample'")
                 (:sql-template get-template)))
          (is (= ["granted_at" "granted_by"] (:set-columns update-template)))
          (is (= (str "UPDATE user_roles\n"
                      "SET granted_at = /*$granted-at*/CURRENT_TIMESTAMP\n"
                      "  , granted_by = /*$granted-by*/1\n"
                      "WHERE user_id = /*$user-id*/1\n"
                      "  AND role_code = /*$role-code*/'sample'\n"
                      "RETURNING *")
                 (:sql-template update-template)))
          (is (= (str "DELETE FROM user_roles\n"
                      "WHERE user_id = /*$user-id*/1\n"
                      "  AND role_code = /*$role-code*/'sample'\n"
                      "RETURNING *")
                 (:sql-template delete-template))))))))

(deftest render-crud-files-groups-templates-by-table
  (let [crud-result {:dialect "postgresql"
                     :schema "public"
                     :templates [{:table "users"
                                  :kind :get
                                  :name "crud.get-by-id"
                                  :meta {:cardinality :one}
                                  :sql-template "SELECT * FROM users WHERE id = /*$id*/1"}
                                 {:table "users"
                                  :kind :insert
                                  :name "crud.insert"
                                  :meta {:cardinality :one}
                                  :sql-template "INSERT INTO users (...) VALUES (...) RETURNING *"}
                                 {:table "users"
                                  :kind :insert
                                  :name "crud.insert-many"
                                  :meta {:cardinality :many}
                                  :sql-template "INSERT INTO users (...) VALUES /*%for row in rows */(...),/*%end */ RETURNING *"}
                                 {:table "orders"
                                  :kind :get
                                  :name "crud.get-by-id"
                                  :meta {:cardinality :one}
                                  :sql-template "SELECT * FROM orders WHERE id = /*$id*/1"}]}
        rendered (crud/render-crud-files crud-result)
        files (:files rendered)
        orders-file (first files)
        users-file (second files)]
    (is (= "postgresql" (:dialect rendered)))
    (is (= "public" (:schema rendered)))
    (is (= ["postgresql/public/orders/crud.sql"
            "postgresql/public/users/crud.sql"]
           (mapv :path files)))
    (is (= (str "/*:name crud.insert */\n"
                "/*:cardinality :one */\n"
                "INSERT INTO users (...) VALUES (...) RETURNING *\n\n"
                "/*:name crud.insert-many */\n"
                "/*:cardinality :many */\n"
                "INSERT INTO users (...) VALUES /*%for row in rows */(...),/*%end */ RETURNING *\n\n"
                "/*:name crud.get-by-id */\n"
                "/*:cardinality :one */\n"
                "SELECT * FROM users WHERE id = /*$id*/1")
           (:content users-file)))
    (is (= (str "/*:name crud.get-by-id */\n"
                "/*:cardinality :one */\n"
                "SELECT * FROM orders WHERE id = /*$id*/1")
           (:content orders-file)))))

(deftest write-crud-files-writes-table-files
  (let [temp-root (str (System/getProperty "java.io.tmpdir")
                       "/bisql-crud-test-"
                       (System/nanoTime))
        crud-result {:dialect "postgresql"
                     :schema "public"
                     :templates [{:table "users"
                                  :name "crud.get-by-id"
                                  :meta {:cardinality :one}
                                  :sql-template "SELECT * FROM users WHERE id = /*$id*/1"}]}
        result (crud/write-crud-files! crud-result {:output-root temp-root})
        output-file (io/file temp-root "postgresql/public/users/crud.sql")]
    (is (.exists output-file))
    (is (= (str "/*:name crud.get-by-id */\n"
                "/*:cardinality :one */\n"
                "SELECT * FROM users WHERE id = /*$id*/1")
           (slurp output-file)))
    (is (= "postgresql/public/users/crud.sql"
           (:path (first (:files result)))))))

(deftest render-crud-query-namespaces-groups-tables-into-namespaces
  (let [crud-result {:dialect "postgresql"
                     :schema "public"
                     :templates [{:table "users"
                                  :name "crud.get-by-id"
                                  :meta {:cardinality :one}
                                  :sql-template "SELECT * FROM users WHERE id = /*$id*/1"}
                                 {:table "users"
                                  :name "crud.insert"
                                  :meta {:cardinality :one}
                                  :sql-template "INSERT INTO users (...) VALUES (...) RETURNING *"}
                                 {:table "users"
                                  :name "crud.insert-many"
                                  :meta {:cardinality :many}
                                  :sql-template "INSERT INTO users (...) VALUES /*%for row in rows */(...),/*%end */ RETURNING *"}
                                 {:table "orders"
                                  :name "crud.get-by-id"
                                  :meta {:cardinality :one}
                                  :sql-template "SELECT * FROM orders WHERE id = /*$id*/1"}]}
        rendered (crud/render-crud-query-namespaces crud-result {:output-root "src/sql"})
        files (:files rendered)
        orders-file (first files)
        users-file (second files)]
    (is (= "postgresql" (:dialect rendered)))
    (is (= "public" (:schema rendered)))
    (is (= ["postgresql/public/orders/crud.clj"
            "postgresql/public/users/crud.clj"]
           (mapv :path files)))
    (is (= 'sql.postgresql.public.users.crud
           (:namespace users-file)))
    (is (= "postgresql/public/users/crud"
           (:query-path users-file)))
    (is (str/starts-with? (:content users-file) "(ns sql.postgresql.public.users.crud)\n\n"))
    (is (str/includes? (:content users-file) "(declare ^{:arglists "))
    (is (str/includes? (:content users-file) "get-by-id"))
    (is (str/includes? (:content users-file) ":arglists (quote ([datasource] [datasource template-params]))"))
    (is (str/includes? (:content users-file) ":cardinality :one"))
    (is (str/includes? (:content users-file) ":bisql.define/navigation-stub true"))
    (is (str/includes? (:content users-file) "Generated from SQL template:\nsrc/sql/postgresql/public/users/crud.sql:1"))
    (is (str/includes? (:content users-file) "src/sql/postgresql/public/users/crud.sql:1"))
    (is (not (str/includes? (:content users-file) "SELECT * FROM users WHERE id = /*$id*/1")))
    (is (not (str/includes? (:content users-file) ":sql-template")))
    (is (str/starts-with? (:content orders-file) "(ns sql.postgresql.public.orders.crud)\n\n"))
    (is (str/includes? (:content orders-file) "src/sql/postgresql/public/orders/crud.sql:1"))))

(deftest render-crud-query-namespaces-can-include-sql-template-in-docstrings
  (let [crud-result {:dialect "postgresql"
                     :schema "public"
                     :templates [{:table "users"
                                  :name "crud.get-by-id"
                                  :meta {:cardinality :one}
                                  :sql-template "SELECT * FROM users WHERE id = /*$id*/1"}]}
        rendered (crud/render-crud-query-namespaces crud-result {:output-root "src/sql"
                                                                 :include-sql-template? true})
        users-file (first (:files rendered))]
    (is (str/includes? (:content users-file) "src/sql/postgresql/public/users/crud.sql:1"))
    (is (str/includes? (:content users-file) "SELECT * FROM users WHERE id = /*$id*/1"))))

(deftest write-crud-query-namespaces-writes-table-namespaces
  (let [temp-root (str (System/getProperty "java.io.tmpdir")
                       "/bisql-crud-ns-test-"
                       (System/nanoTime))
        crud-result {:dialect "postgresql"
                     :schema "public"
                     :templates [{:table "users"
                                  :name "crud.get-by-id"
                                  :meta {:cardinality :one}
                                  :sql-template "SELECT * FROM users WHERE id = /*$id*/1"}]}
        output-root (str temp-root "/src/sql")
        result (crud/write-crud-query-namespaces! crud-result {:output-root output-root})
        output-file (io/file output-root "postgresql/public/users/crud.clj")]
    (is (.exists output-file))
    (let [content (slurp output-file)]
      (is (str/starts-with? content
                            "(ns sql.postgresql.public.users.crud)\n\n(declare ^{:arglists "))
      (is (str/includes? content
                         "src/sql/postgresql/public/users/crud.sql:1"))
      (is (not (str/includes? content
                              "SELECT * FROM users WHERE id = /*$id*/1"))))
    (is (= "postgresql/public/users/crud.clj"
           (:path (first (:files result)))))))
