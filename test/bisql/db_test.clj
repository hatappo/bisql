(ns bisql.db-test
  (:require [bisql.crud :as crud]
            [clojure.test :refer [deftest is]]
            [next.jdbc :as jdbc]))

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
    (is (contains? names "insert"))
    (is (contains? names "update-by-id"))
    (is (contains? names "delete-by-id"))
    (is (contains? names "get-by-id"))
    (is (contains? names "get-by-email"))
    (is (contains? names "get-by-user-id-and-device-identifier"))
    (is (contains? names "get-by-user-id-and-role-code"))
    (is (contains? names "get-by-order-number"))
    (is (contains? names "list-by-user-id-order-by-device-identifier"))
    (is (contains? names "list-by-user-id-order-by-last-seen-at"))
    (is (contains? names "list-by-user-id-and-last-seen-at"))
    (is (contains? names "list-by-status-order-by-last-seen-at"))
    (is (contains? names "list-by-status-order-by-device-type-and-last-seen-at"))
    (is (contains? names "list-by-status-and-device-type"))
    (is (contains? names "list-by-status"))
    (is (contains? names "list-by-status-and-last-seen-at"))
    (is (contains? names "list-by-user-id"))
    (is (contains? names "list-by-state"))
    (is (contains? names "list-by-state-and-created-at"))
    (is (contains? names "update-by-user-id-and-device-identifier"))
    (is (contains? names "update-by-user-id-and-role-code"))
    (is (contains? names "delete-by-user-id-and-device-identifier"))
    (is (contains? names "delete-by-user-id-and-role-code"))))
