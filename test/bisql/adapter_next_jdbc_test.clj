(ns bisql.adapter-next-jdbc-test
  (:require [bisql.adapter.next-jdbc :as adapter]
            [bisql.core :as bisql]
            [clojure.test :refer [deftest is]]
            [next.jdbc :as jdbc]))

(adapter/defquery "/sql/adapter/postgresql/public/users/users-crud.sql")
(bisql/defquery "/sql/adapter/example-declarations-valid.sql")

(def example-exec-one
  (with-meta
    (fn [template-params]
      (bisql/render-query
       {:sql-template "SELECT * FROM users WHERE id = /*$id*/1"}
       template-params))
    {:cardinality :one}))

(def example-exec-many
  (with-meta
    (fn [template-params]
      (bisql/render-query
       {:sql-template "SELECT * FROM users WHERE id = /*$id*/1"}
       template-params))
    {:cardinality :many}))

(defn- query-fn
  [ns-sym sym]
  (var-get (ns-resolve ns-sym sym)))

(deftest exec-uses-execute-one-when-query-metadata-says-cardinality-one
  (let [captured (atom nil)]
    (with-redefs [jdbc/execute-one! (fn [_ds statement]
                                      (reset! captured statement)
                                      {:id 42})
                  jdbc/execute! (fn [_ds _statement]
                                  (throw (ex-info "should not call execute!" {})))]
      (is (= {:id 42}
             (adapter/exec! ::datasource (query-fn 'bisql.adapter-next-jdbc-test 'example-exec-one) {:id 42})))
      (is (= ["SELECT * FROM users WHERE id = ?" 42]
             @captured)))))

(deftest exec-uses-execute-many-when-query-metadata-says-cardinality-many
  (let [captured (atom nil)]
    (with-redefs [jdbc/execute! (fn [_ds statement]
                                  (reset! captured statement)
                                  [{:id 42}])
                  jdbc/execute-one! (fn [_ds _statement]
                                      (throw (ex-info "should not call execute-one!" {})))]
      (is (= [{:id 42}]
             (adapter/exec! ::datasource (query-fn 'bisql.adapter-next-jdbc-test 'example-exec-many) {:id 42})))
      (is (= ["SELECT * FROM users WHERE id = ?" 42]
             @captured)))))

(deftest exec-rejects-missing-execute-mode
  (let [captured (atom nil)]
    (with-redefs [jdbc/execute! (fn [_ds statement]
                                  (reset! captured statement)
                                  [{:value 1}])
                  jdbc/execute-one! (fn [_ds _statement]
                                      (throw (ex-info "should not call execute-one!" {})))]
      (is (= [{:value 1}]
             (adapter/exec! ::datasource {:sql-template "SELECT 1"} {})))
      (is (= ["SELECT 1"]
             @captured)))))

(deftest adapter-defquery-defines-executable-functions
  (let [captured (atom nil)]
    (with-redefs [jdbc/execute-one! (fn [_ds statement]
                                      (reset! captured statement)
                                      {:id 42})
                  jdbc/execute! (fn [_ds _statement]
                                  (throw (ex-info "should not call execute!" {})))]
      (is (= {:id 42}
             ((query-fn 'sql.adapter.postgresql.public.users 'get-by-id) ::datasource {:id 42})))
      (is (= ["SELECT * FROM users\nWHERE id = ?" 42]
             @captured)))))

(deftest core-defquery-facade-defines-executable-functions
  (let [captured (atom nil)]
    (with-redefs [jdbc/execute-one! (fn [_ds statement]
                                      (reset! captured statement)
                                      {:id 42})
                  jdbc/execute! (fn [_ds _statement]
                                  (throw (ex-info "should not call execute!" {})))]
      (is (= {:id 42}
             ((query-fn 'sql.adapter 'example-declarations-valid) ::datasource {:id 42})))
      (is (= ["SELECT * FROM users WHERE id = ?" 42]
             @captured)))))
