(ns bisql.crud-test
  (:require [bisql.crud :as crud]
            [bisql.define :as define]
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
                   {"users" [{:constraint_name "users_pkey"
                              :constraint_type "PRIMARY KEY"
                              :column_names ["id"]}
                             {:constraint_name "users_email_key"
                              :constraint_type "UNIQUE"
                              :column_names ["email"]}]
                    "orders" [{:constraint_name "orders_pkey"
                               :constraint_type "PRIMARY KEY"
                               :column_names ["id"]}]
                    "user_devices" [{:constraint_name "user_devices_pkey"
                                     :constraint_type "PRIMARY KEY"
                                     :column_names ["id"]}
                                    {:constraint_name "user_devices_user_id_device_identifier_key"
                                     :constraint_type "UNIQUE"
                                     :column_names ["user_id" "device_identifier"]}]
                    "user_roles" [{:constraint_name "user_roles_pkey"
                                   :constraint_type "PRIMARY KEY"
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
          warnings (:warnings result)
          names (set (map :name templates))]
      (is (= "postgresql" (:dialect result)))
      (is (= "public" (:schema result)))
      (is (contains? names "crud.insert"))
      (is (contains? names "crud.insert-many"))
      (is (contains? names "crud.upsert-by-id"))
      (is (contains? names "crud.upsert-by-email"))
      (is (contains? names "crud.update-by-id"))
      (is (contains? names "crud.update-by-email"))
      (is (contains? names "crud.delete-by-id"))
      (is (contains? names "crud.delete-by-email"))
      (is (contains? names "crud.get-by-id"))
      (is (contains? names "crud.get-by-email"))
      (is (contains? names "crud.get-by-user-id-and-device-identifier"))
      (is (contains? names "crud.get-by-user-id-and-role-code"))
      (is (contains? names "crud.upsert-by-user-id-and-device-identifier"))
      (is (contains? names "crud.upsert-by-user-id-and-role-code"))
      (is (contains? names "crud.count"))
      (is (contains? names "crud.count-by-status"))
      (is (contains? names "crud.count-by-status-starting-with"))
      (is (contains? names "crud.count-by-state"))
      (is (contains? names "crud.count-by-state-and-created-at"))
      (is (contains? names "crud.count-by-user-id"))
      (is (contains? names "crud.count-by-user-id-and-device-identifier-starting-with"))
      (is (= 0 (count warnings)))
      (is (contains? names "crud.list-by-user-id-order-by-device-identifier"))
      (is (contains? names "crud.list-by-user-id-and-device-identifier-starting-with"))
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
      (testing "insert template includes primary key columns"
        (let [template (some #(when (and (= "orders" (:table %))
                                         (= "crud.insert" (:name %)))
                                %)
                             templates)]
          (is (= "crud.insert" (:query-name template)))
          (is (= ["id" "user_id" "order_number" "state" "total_amount" "created_at"]
                 (:columns template)))
          (is (= (str "INSERT INTO orders (\n"
                      "  id,\n"
                      "  user_id,\n"
                      "  order_number,\n"
                      "  state,\n"
                      "  total_amount,\n"
                      "  created_at\n"
                      ")\n"
                      "VALUES (\n"
                      "  /*$id*/1,\n"
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
          (is (= :many (get-in template [:meta :cardinality])))
          (is (= [:map [:rows [:sequential 'sql.postgresql.public.orders.schema/insert]]]
                 (get-in template [:meta :malli/in])))
          (is (= [:sequential 'sql.postgresql.public.orders.schema/row]
                 (get-in template [:meta :malli/out])))
          (is (= ["id" "user_id" "order_number" "state" "total_amount" "created_at"]
                 (:columns template)))
          (is (= (str "INSERT INTO orders (\n"
                      "  id,\n"
                      "  user_id,\n"
                      "  order_number,\n"
                      "  state,\n"
                      "  total_amount,\n"
                      "  created_at\n"
                      ")\n"
                      "VALUES\n"
                      "/*%for row in rows separating , */\n"
                      "(\n"
                      "  /*$row.id*/1,\n"
                      "  /*$row.user-id*/1,\n"
                      "  /*$row.order-number*/'sample',\n"
                      "  /*$row.state*/'sample',\n"
                      "  /*$row.total-amount*/1,\n"
                      "  /*$row.created-at*/CURRENT_TIMESTAMP\n"
                      ")\n"
                      "/*%end */\n"
                      "RETURNING *")
                 (:sql-template template)))))
      (testing "upsert template uses inserts and updates maps"
        (let [template (some #(when (and (= "users" (:table %))
                                         (= "crud.upsert-by-id" (:name %)))
                                %)
                             templates)]
          (is (= :one (get-in template [:meta :cardinality])))
          (is (= [:map
                  [:inserts 'sql.postgresql.public.users.schema/insert]
                  [:updates [:maybe 'sql.postgresql.public.users.schema/update]]]
                 (get-in template [:meta :malli/in])))
          (is (= 'sql.postgresql.public.users.schema/row
                 (get-in template [:meta :malli/out])))
          (is (= ["id" "email" "display_name" "status"] (:set-columns template)))
          (is (= (str "INSERT INTO users (\n"
                      "  id,\n"
                      "  email,\n"
                      "  display_name,\n"
                      "  status\n"
                      ")\n"
                      "VALUES (\n"
                      "  /*$inserts.id*/1,\n"
                      "  /*$inserts.email*/'user@example.com',\n"
                      "  /*$inserts.display-name*/'sample',\n"
                      "  /*$inserts.status*/'sample'\n"
                      ")\n"
                      "ON CONFLICT ON CONSTRAINT users_pkey\n"
                      "/*%if updates */\n"
                      "DO UPDATE\n"
                      "SET\n"
                      "/*%for item in updates separating , */\n"
                      "  /*!item.name*/status = /*$item.value*/'sample'\n"
                      "/*%end */\n"
                      "/*%else => DO NOTHING */\n"
                      "/*%end */\n"
                      "RETURNING *")
                 (:sql-template template)))))
      (testing "upsert uses the same inserts and updates shape for composite keys"
        (let [template (some #(when (and (= "user_roles" (:table %))
                                         (= "crud.upsert-by-user-id-and-role-code" (:name %)))
                                %)
                             templates)]
          (is (= ["user_id" "role_code" "granted_at" "granted_by"]
                 (:set-columns template)))
          (is (= (str "INSERT INTO user_roles (\n"
                      "  user_id,\n"
                      "  role_code,\n"
                      "  granted_at,\n"
                      "  granted_by\n"
                      ")\n"
                      "VALUES (\n"
                      "  /*$inserts.user-id*/1,\n"
                      "  /*$inserts.role-code*/'sample',\n"
                      "  /*$inserts.granted-at*/CURRENT_TIMESTAMP,\n"
                      "  /*$inserts.granted-by*/1\n"
                      ")\n"
                      "ON CONFLICT ON CONSTRAINT user_roles_pkey\n"
                      "/*%if updates */\n"
                      "DO UPDATE\n"
                      "SET\n"
                      "/*%for item in updates separating , */\n"
                      "  /*!item.name*/granted_by = /*$item.value*/1\n"
                      "/*%end */\n"
                      "/*%else => DO NOTHING */\n"
                      "/*%end */\n"
                      "RETURNING *")
                 (:sql-template template)))))
      (testing "update template uses where and updates maps"
        (let [template (some #(when (and (= "users" (:table %))
                                         (= "crud.update-by-id" (:name %)))
                                %)
                             templates)]
          (is (= ["id" "email" "display_name" "status"] (:set-columns template)))
          (is (= [:map
                  [:where [:map [:id 'int?]]]
                  [:updates 'sql.postgresql.public.users.schema/update]]
                 (get-in template [:meta :malli/in])))
          (is (= (str "UPDATE users\n"
                      "SET\n"
                      "/*%for item in updates separating , */\n"
                      "  /*!item.name*/status = /*$item.value*/'sample'\n"
                      "/*%end */\n"
                      "WHERE id = /*$where.id*/1\n"
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
      (testing "count queries are generated without order-by or pagination"
        (let [count-template (some #(when (and (= "user_roles" (:table %))
                                               (= "crud.count" (:name %)))
                                      %)
                                   templates)
          count-by-state-template (some #(when (and (= "orders" (:table %))
                                                        (= "crud.count-by-state" (:name %)))
                                               %)
                                            templates)
              count-by-state-and-created-at-template (some #(when (and (= "orders" (:table %))
                                                                       (= "crud.count-by-state-and-created-at" (:name %)))
                                                              %)
                                                           templates)]
          (is (= "crud.count" (:query-name count-template)))
          (is (= [] (:columns count-template)))
          (is (= :one (get-in count-template [:meta :cardinality])))
          (is (= [:map]
                 (get-in count-template [:meta :malli/in])))
          (is (= [:map [:count 'int?]]
                 (get-in count-template [:meta :malli/out])))
          (is (= "SELECT COUNT(*) AS count FROM user_roles"
                 (:sql-template count-template)))
          (is (= (str "SELECT COUNT(*) AS count FROM orders\n"
                      "WHERE state = /*$state*/'sample'")
                 (:sql-template count-by-state-template)))
          (is (= (str "SELECT COUNT(*) AS count FROM orders\n"
                      "WHERE state = /*$state*/'sample'\n"
                      "  AND created_at = /*$created-at*/CURRENT_TIMESTAMP")
                 (:sql-template count-by-state-and-created-at-template)))))
      (testing "starts-with count queries are generated only for trailing text columns"
        (let [count-by-status-starting-with-template
              (some #(when (and (= "users" (:table %))
                                (= "crud.count-by-status-starting-with" (:name %)))
                       %)
                    templates)
              count-by-user-id-and-device-identifier-starting-with-template
              (some #(when (and (= "user_devices" (:table %))
                                (= "crud.count-by-user-id-and-device-identifier-starting-with" (:name %)))
                       %)
                    templates)]
          (is (= (str "SELECT COUNT(*) AS count FROM users\n"
                      "WHERE status LIKE /*$status*/'sample%' ESCAPE '\\'")
                 (:sql-template count-by-status-starting-with-template)))
          (is (= (str "SELECT COUNT(*) AS count FROM user_devices\n"
                      "WHERE user_id = /*$user-id*/1\n"
                      "  AND device_identifier LIKE /*$device-identifier*/'sample%' ESCAPE '\\'")
                 (:sql-template count-by-user-id-and-device-identifier-starting-with-template)))))
      (testing "count query duplicates are deduplicated by name with warnings"
        (let [user-device-count-templates (filter #(and (= "user_devices" (:table %))
                                                        (= :count (:kind %)))
                                                  templates)
              count-template-names (->> user-device-count-templates
                                        (map :name)
                                        frequencies)]
          (is (= 1 (get count-template-names "crud.count")))
          (is (= 1 (get count-template-names "crud.count-by-user-id")))))
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
              list-by-user-id-and-device-identifier-starting-with-template
              (some #(when (= "crud.list-by-user-id-and-device-identifier-starting-with" (:name %))
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
          (is (= ["id" "user_id" "device_type" "device_identifier" "status" "last_seen_at"]
                 (:set-columns update-template)))
          (is (= (str "UPDATE user_devices\n"
                      "SET\n"
                      "/*%for item in updates separating , */\n"
                      "  /*!item.name*/last_seen_at = /*$item.value*/CURRENT_TIMESTAMP\n"
                      "/*%end */\n"
                      "WHERE user_id = /*$where.user-id*/1\n"
                      "  AND device_identifier = /*$where.device-identifier*/'sample'\n"
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
                      "  AND device_identifier LIKE /*$device-identifier*/'sample%' ESCAPE '\\'\n"
                      "ORDER BY device_identifier\n"
                      "LIMIT /*$limit*/100\n"
                      "OFFSET /*$offset*/0")
                 (:sql-template list-by-user-id-and-device-identifier-starting-with-template)))
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
          (is (= ["user_id" "role_code" "granted_at" "granted_by"]
                 (:set-columns update-template)))
          (is (= (str "UPDATE user_roles\n"
                      "SET\n"
                      "/*%for item in updates separating , */\n"
                      "  /*!item.name*/granted_by = /*$item.value*/1\n"
                      "/*%end */\n"
                      "WHERE user_id = /*$where.user-id*/1\n"
                      "  AND role_code = /*$where.role-code*/'sample'\n"
                      "RETURNING *")
                 (:sql-template update-template)))
          (is (= (str "DELETE FROM user_roles\n"
                      "WHERE user_id = /*$user-id*/1\n"
                      "  AND role_code = /*$role-code*/'sample'\n"
                      "RETURNING *")
                 (:sql-template delete-template))))))))

(deftest dedupe-templates-by-name-warns-when-same-name-has-different-sql
  (let [result (#'crud/dedupe-templates-by-name
                [{:table "users"
                  :kind :count
                  :name "crud.count"
                  :meta {:cardinality :one}
                  :sql-template "SELECT COUNT(*) AS count FROM users"}
                 {:table "users"
                  :kind :count
                  :name "crud.count"
                  :meta {:cardinality :one}
                  :sql-template "SELECT COUNT(*) AS count FROM users WHERE status = /*$status*/'sample'"}])]
    (is (= 1 (count (:templates result))))
    (is (= "SELECT COUNT(*) AS count FROM users"
           (:sql-template (first (:templates result)))))
    (is (= 1 (count (:warnings result))))
    (is (str/includes? (first (:warnings result))
                       "https://github.com/hatappo/bisql/issues"))
    (is (str/includes? (first (:warnings result))
                       "`crud.count`"))
    (is (str/includes? (first (:warnings result))
                       "`users`"))))

(deftest render-crud-files-groups-templates-by-table
  (let [crud-result {:dialect "postgresql"
                     :schema "public"
                     :templates [{:table "users"
                                  :kind :insert
                                  :name "crud.insert"
                                  :meta {:cardinality :one}
                                  :sql-template "INSERT INTO users (...) VALUES (...) RETURNING *"}
                                 {:table "users"
                                  :kind :upsert
                                  :name "crud.upsert-by-id"
                                  :meta {:cardinality :one}
                                  :sql-template "INSERT INTO users (...) ON CONFLICT ON CONSTRAINT users_pkey DO UPDATE RETURNING *"}
                                 {:table "users"
                                  :kind :get
                                  :name "crud.get-by-id"
                                  :meta {:cardinality :one}
                                  :sql-template "SELECT * FROM users WHERE id = /*$id*/1"}
                                 {:table "users"
                                  :kind :count
                                  :name "crud.count"
                                  :meta {:cardinality :one}
                                  :sql-template "SELECT COUNT(*) AS count FROM users"}
                                 {:table "users"
                                  :kind :insert
                                  :name "crud.insert-many"
                                  :meta {:cardinality :many}
                                  :sql-template "INSERT INTO users (...) VALUES /*%for row in rows separating , */(...)/*%end */ RETURNING *"}
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
                "INSERT INTO users (...) VALUES /*%for row in rows separating , */(...)/*%end */ RETURNING *\n\n"
                "/*:name crud.upsert-by-id */\n"
                "/*:cardinality :one */\n"
                "INSERT INTO users (...) ON CONFLICT ON CONSTRAINT users_pkey DO UPDATE RETURNING *\n\n"
                "/*:name crud.get-by-id */\n"
                "/*:cardinality :one */\n"
                "SELECT * FROM users WHERE id = /*$id*/1\n\n"
                "/*:name crud.count */\n"
                "/*:cardinality :one */\n"
                "SELECT COUNT(*) AS count FROM users")
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
                     :columns-by-table {"users" [{:column_name "id"
                                                  :data_type "bigint"
                                                  :is_identity "YES"
                                                  :is_nullable "NO"
                                                  :column_default nil}
                                                 {:column_name "email"
                                                  :data_type "text"
                                                  :is_identity "NO"
                                                  :is_nullable "NO"
                                                  :column_default nil}
                                                 {:column_name "created_at"
                                                  :data_type "timestamp with time zone"
                                                  :is_identity "NO"
                                                  :is_nullable "NO"
                                                  :column_default "CURRENT_TIMESTAMP"}]}
                     :templates [{:table "users"
                                  :name "crud.get-by-id"
                                  :kind :get
                                  :columns ["id"]
                                  :meta {:cardinality :one
                                         :malli/in [:map [:id 'int?]]
                                         :malli/out [:maybe 'sql.postgresql.public.users.schema/row]}
                                  :sql-template "SELECT * FROM users WHERE id = /*$id*/1"}]}
        result (crud/write-crud-files! crud-result {:output-root temp-root})
        output-file (io/file temp-root "postgresql/public/users/crud.sql")
        schema-file (io/file temp-root "postgresql/public/users/schema.clj")]
    (is (.exists output-file))
    (is (.exists schema-file))
    (is (= (str "/*:name crud.get-by-id */\n"
                "/*:cardinality :one */\n"
                "/*:malli/in [:map [:id int?]] */\n"
                "/*:malli/out [:maybe sql.postgresql.public.users.schema/row] */\n"
                "SELECT * FROM users WHERE id = /*$id*/1")
           (slurp output-file)))
    (is (str/includes? (slurp schema-file) "(ns sql.postgresql.public.users.schema"))
    (is (str/includes? (slurp schema-file)
                       "(bisql.schema/malli-map-all-entries-strip-default-sentinel insert)"))
    (is (str/includes? (slurp schema-file) "(def row"))
    (is (str/includes? (slurp schema-file) "(def insert"))
    (is (str/includes? (slurp schema-file) "(def update"))
    (is (str/includes? (slurp schema-file)
                       "(bisql.schema/malli-map-all-entries-optional insert)"))
    (is (not (str/includes? (slurp schema-file) "(def get-by-id-in")))
    (is (= ["postgresql/public/users/crud.sql"
            "postgresql/public/users/schema.clj"]
           (mapv :path (:files result))))))

(deftest write-crud-files-can-materialize-derived-schemas
  (let [temp-root (str (System/getProperty "java.io.tmpdir")
                       "/bisql-crud-test-no-derive-"
                       (System/nanoTime))
        crud-result {:dialect "postgresql"
                     :schema "public"
                     :columns-by-table {"users" [{:column_name "id"
                                                  :data_type "bigint"
                                                  :is_identity "YES"
                                                  :is_nullable "NO"
                                                  :column_default nil}
                                                 {:column_name "email"
                                                  :data_type "text"
                                                  :is_identity "NO"
                                                  :is_nullable "NO"
                                                  :column_default nil}
                                                 {:column_name "created_at"
                                                  :data_type "timestamp with time zone"
                                                  :is_identity "NO"
                                                  :is_nullable "NO"
                                                  :column_default "CURRENT_TIMESTAMP"}]}
                     :templates [{:table "users"
                                  :name "crud.get-by-id"
                                  :kind :get
                                  :columns ["id"]
                                  :meta {:cardinality :one
                                         :malli/in [:map [:id 'int?]]
                                         :malli/out [:maybe 'sql.postgresql.public.users.schema/row]}
                                  :sql-template "SELECT * FROM users WHERE id = /*$id*/1"}]}
        _result (crud/write-crud-files! crud-result {:output-root temp-root
                                                     :derive-schemas? false})
        schema-file (io/file temp-root "postgresql/public/users/schema.clj")
        schema-text (slurp schema-file)]
    (is (not (str/includes? schema-text
                            "malli-map-all-entries-strip-default-sentinel")))
    (is (not (str/includes? schema-text
                            "malli-map-all-entries-optional insert")))
    (is (str/includes? schema-text "(def row"))
    (is (str/includes? schema-text "(def insert"))
    (is (str/includes? schema-text "(def update"))))

(deftest write-crud-files-can-suppress-unused-public-var-in-generated-schemas
  (let [temp-root (str (System/getProperty "java.io.tmpdir")
                       "/bisql-crud-test-suppress-"
                       (System/nanoTime))
        crud-result {:dialect "postgresql"
                     :schema "public"
                     :columns-by-table {"users" [{:column_name "id"
                                                  :data_type "bigint"
                                                  :is_identity "YES"
                                                  :is_nullable "NO"
                                                  :column_default nil}
                                                 {:column_name "email"
                                                  :data_type "text"
                                                  :is_identity "NO"
                                                  :is_nullable "NO"
                                                  :column_default nil}]}
                     :templates []}
        _result (crud/write-crud-files! crud-result {:output-root temp-root
                                                     :suppress-unused-public-var? true})
        schema-file (io/file temp-root "postgresql/public/users/schema.clj")
        schema-text (slurp schema-file)]
    (is (str/includes? schema-text
                       "#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}"))))

(deftest render-declaration-files-groups-queries-into-namespaces
  (let [rendered (define/render-declaration-files {:output-root "test/sql/postgresql/public"})
        files (:files rendered)
        users-file (some #(when (= "users/crud.clj" (:path %)) %) files)]
    (is (some #(= "users/crud.clj" (:path %)) files))
    (is (= 'sql.postgresql.public.users.crud
           (:namespace users-file)))
    (is (str/starts-with? (:content users-file) "(ns sql.postgresql.public.users.crud)\n\n"))
    (is (str/includes? (:content users-file) "(declare ^{:arglists "))
    (is (str/includes? (:content users-file) "get-by-id"))
    (is (str/includes? (:content users-file) ":arglists (quote ([datasource] [datasource template-params]))"))
    (is (str/includes? (:content users-file) ":cardinality :one"))
    (is (str/includes? (:content users-file) ":bisql.define/navigation-stub true"))
    (is (str/includes? (:content users-file) "Find one user by id.\nThis function is generated from SQL: "))
    (is (str/includes? (:content users-file) "This function is generated from SQL: test/sql/postgresql/public/users/crud.sql:1"))
    (is (str/includes? (:content users-file) "test/sql/postgresql/public/users/crud.sql:1"))
    (is (not (str/includes? (:content users-file) "SELECT * FROM users WHERE id = /*$id*/1")))
    (is (not (str/includes? (:content users-file) ":sql-template")))))

(deftest render-declaration-files-can-include-sql-template-in-docstrings
  (let [rendered (define/render-declaration-files {:output-root "test/sql/postgresql/public"
                                                   :include-sql-template? true})
        users-file (some #(when (= "users/crud.clj" (:path %)) %) (:files rendered))]
    (is (str/includes? (:content users-file) "test/sql/postgresql/public/users/crud.sql:1"))
    (is (str/includes? (:content users-file) "SELECT * FROM users\nWHERE id = /*$id*/1"))))

(deftest write-declaration-files-writes-generated-files
  (let [temp-root (str (System/getProperty "java.io.tmpdir")
                       "/bisql-declarations-test-"
                       (System/nanoTime))
        result (with-redefs [define/render-declaration-files (fn [_]
                                                               {:files [{:path "postgresql/public/users/crud.clj"
                                                                         :content "(ns sql.postgresql.public.users.crud)\n"}]})]
                 (define/write-declaration-files! {:output-root temp-root}))
        output-file (io/file temp-root "postgresql/public/users/crud.clj")]
    (is (.exists output-file))
    (is (= "(ns sql.postgresql.public.users.crud)\n"
           (slurp output-file)))
    (is (= "postgresql/public/users/crud.clj"
           (:path (first (:files result)))))))
