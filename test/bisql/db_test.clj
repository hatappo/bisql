(ns bisql.db-test
  (:require [bisql.adapter.next-jdbc :as adapter]
            [bisql.core :as bisql]
            [bisql.crud :as crud]
            [bisql.query :as query]
            [bisql.validation :as validation]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [next.jdbc :as jdbc]))

(adapter/defquery "/sql/adapter/postgresql/public/users/crud.sql")

(defn- datasource
  []
  (jdbc/get-datasource {:dbtype "postgresql"
                        :host "localhost"
                        :port 5432
                        :dbname "bisql_dev"
                        :user "bisql"
                        :password "bisql"}))

(deftest generate-crud-reads-postgresql-schema
  (let [result (crud/generate-crud (datasource) {:schema "public"})
        templates (:templates result)
        names (set (map :name templates))]
    (is (= "postgresql" (:dialect result)))
    (is (= "public" (:schema result)))
    (is (contains? names "crud.insert"))
    (is (contains? names "crud.upsert-by-id"))
    (is (contains? names "crud.upsert-by-email"))
    (is (contains? names "crud.update-by-id"))
    (is (contains? names "crud.delete-by-id"))
    (is (contains? names "crud.get-by-id"))
    (is (contains? names "crud.get-by-email"))
    (is (contains? names "crud.get-by-user-id-and-device-identifier"))
    (is (contains? names "crud.get-by-user-id-and-role-code"))
    (is (contains? names "crud.get-by-order-number"))
    (is (contains? names "crud.upsert-by-user-id-and-device-identifier"))
    (is (contains? names "crud.upsert-by-user-id-and-role-code"))
    (is (contains? names "crud.count"))
    (is (contains? names "crud.count-by-status"))
    (is (contains? names "crud.count-by-status-starting-with"))
    (is (contains? names "crud.count-by-state"))
    (is (contains? names "crud.count-by-state-and-created-at"))
    (is (contains? names "crud.count-by-user-id"))
    (is (contains? names "crud.count-by-user-id-and-device-identifier-starting-with"))
    (is (contains? names "crud.list-by-user-id-order-by-device-identifier"))
    (is (contains? names "crud.list-by-user-id-and-device-identifier-starting-with"))
    (is (contains? names "crud.list-by-user-id-order-by-last-seen-at"))
    (is (contains? names "crud.list-by-user-id-and-last-seen-at"))
    (is (contains? names "crud.list-by-status-order-by-last-seen-at"))
    (is (contains? names "crud.list-by-status-order-by-device-type-and-last-seen-at"))
    (is (contains? names "crud.list-by-status-and-device-type"))
    (is (contains? names "crud.list-by-status"))
    (is (contains? names "crud.list-by-status-and-last-seen-at"))
    (is (contains? names "crud.list-by-user-id"))
    (is (contains? names "crud.list-by-state"))
    (is (contains? names "crud.list-by-state-and-created-at"))
    (is (contains? names "crud.update-by-user-id-and-device-identifier"))
    (is (contains? names "crud.update-by-user-id-and-role-code"))
    (is (contains? names "crud.delete-by-user-id-and-device-identifier"))
    (is (contains? names "crud.delete-by-user-id-and-role-code"))
    (is (= 0 (count (:warnings result))))
    (is (= 1
           (count (filter #(and (= "user_devices" (:table %))
                                (= "crud.count" (:name %)))
                          templates))))
    (is (= 1
           (count (filter #(and (= "user_devices" (:table %))
                                (= "crud.count-by-user-id" (:name %)))
                          templates))))))

(deftest render-crud-files-deduplicates-count-queries-for-user-devices
  (let [generated (crud/generate-crud (datasource) {:schema "public"})
        rendered (crud/render-crud-files generated)
        user-devices-file (some #(when (= "postgresql/public/user_devices/crud.sql" (:path %))
                                   %)
                                (:files rendered))
        content (:content user-devices-file)]
    (is (some? user-devices-file))
    (is (= 1 (count (re-seq #"/\*:name crud\.count \*/" content))))
    (is (= 1 (count (re-seq #"/\*:name crud\.count-by-user-id \*/" content))))
    (is (str/includes? content "SELECT COUNT(*) AS count FROM user_devices"))))

(deftest adapter-results-use-kebab-case-keys
  (let [row ((ns-resolve 'sql.adapter.postgresql.public.users.crud 'get-by-id)
             (datasource)
             {:id 1})]
    (is (= 1 (:id row)))
    (is (contains? row :display-name))
    (is (contains? row :created-at))
    (is (not (contains? row :display_name)))
    (is (not (contains? row :created_at)))))

(deftest adapter-supports-insert-many-with-separating-for-block
  (let [rows [{:id bisql/DEFAULT
               :email "separating-1@example.com"
               :display-name "Separating One"
               :status "active"
               :created-at (java.time.OffsetDateTime/parse "2025-01-01T00:00:00Z")}
              {:id bisql/DEFAULT
               :email "separating-2@example.com"
               :display-name "Separating Two"
               :status "inactive"
               :created-at (java.time.OffsetDateTime/parse "2025-01-02T00:00:00Z")}]
        inserted ((ns-resolve 'sql.adapter.postgresql.public.users.crud 'insert-many)
                  (datasource)
                  {:rows rows})]
    (is (= 2 (count inserted)))
    (is (= #{"separating-1@example.com" "separating-2@example.com"}
           (set (map :email inserted))))
    (is (= #{"Separating One" "Separating Two"}
           (set (map :display-name inserted))))
    (is (= #{"active" "inactive"}
           (set (map :status inserted))))))

(deftest adapter-supports-validating-write-queries-against-real-db
  (let [insert (ns-resolve 'sql.adapter.postgresql.public.users.crud 'insert)
        update-by-id (ns-resolve 'sql.adapter.postgresql.public.users.crud 'update-by-id)
        ds (datasource)]
    (binding [validation/*bisql-malli-validation-mode* {:in :when-present
                                                        :out :when-present}]
      (let [inserted (insert ds {:id bisql/DEFAULT
                                 :email "malli-write@example.com"
                                 :display-name "Malli Inserted"
                                 :status "active"
                                 :created-at (java.time.OffsetDateTime/parse "2025-02-01T00:00:00Z")})
            updated (update-by-id ds {:where {:id (:id inserted)}
                                      :updates {:status "inactive"
                                                :display-name "Malli Updated"}})]
        (is (pos-int? (:id inserted)))
        (is (= "malli-write@example.com" (:email inserted)))
        (is (= "inactive" (:status updated)))
        (is (= "Malli Updated" (:display-name updated)))))))

(deftest generated-upsert-supports-validation-against-real-db
  (let [ds (datasource)
        upsert-by-email (get (query/load-queries-from-file "postgresql/public/users/crud.sql"
                                                           "src/sql/postgresql/public/users/crud.sql"
                                                           {:base-path "sql"})
                             "crud.upsert-by-email")]
    (is (some? upsert-by-email))
    (binding [validation/*bisql-malli-validation-mode* {:in :when-present
                                                        :out :when-present}]
      (let [_inserted (adapter/exec!
                       ds
                       upsert-by-email
                       {:inserts {:id bisql/DEFAULT
                                  :email "malli-upsert@example.com"
                                  :display-name "Malli Upsert Insert"
                                  :status "active"
                                  :created-at (java.time.OffsetDateTime/parse "2025-02-03T00:00:00Z")}
                        :updates {:status "inactive"
                                  :display-name "Malli Upsert Updated"}})
            upserted (adapter/exec!
                      ds
                      upsert-by-email
                      {:inserts {:id bisql/DEFAULT
                                 :email "malli-upsert@example.com"
                                 :display-name "Malli Upsert Insert"
                                 :status "active"
                                 :created-at (java.time.OffsetDateTime/parse "2025-02-03T00:00:00Z")}
                       :updates {:status "inactive"
                                 :display-name "Malli Upsert Updated"}})
            fetched ((ns-resolve 'sql.adapter.postgresql.public.users.crud 'get-by-id)
                     ds
                     {:id (:id upserted)})]
        (is (pos-int? (:id upserted)))
        (is (= "malli-upsert@example.com" (:email upserted)))
        (is (= "inactive" (:status upserted)))
        (is (= "Malli Upsert Updated" (:display-name upserted)))
        (is (= (:id upserted) (:id fetched)))
        (is (= "inactive" (:status fetched)))))))
