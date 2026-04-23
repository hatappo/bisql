(ns bisql.adapter-next-jdbc-test
  (:require [bisql.adapter.next-jdbc :as adapter]
            [bisql.validation :as validation]
            [bisql.core :as bisql]
            [clojure.test :refer [deftest is]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [java.sql Time Timestamp]
           [java.time Instant LocalDate LocalTime OffsetDateTime]
           [java.util Date]))

(adapter/defquery "/sql/adapter/postgresql/public/users/crud.sql")
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

(def example-exec-one-with-malli
  (with-meta
    (fn [template-params]
      (bisql/render-query
       {:sql-template "SELECT * FROM users WHERE id = /*$id*/1"}
       template-params))
    {:cardinality :one
     :query-name "example.get-by-id"
     :malli/in '[:map [:id int?]]
     :malli/out '[:map [:id int?]]}))

(def example-exec-one-without-malli
  (with-meta
    (fn [template-params]
      (bisql/render-query
       {:sql-template "SELECT * FROM users WHERE id = /*$id*/1"}
       template-params))
    {:cardinality :one
     :query-name "example.get-by-id"}))

(defn- query-fn
  [ns-sym sym]
  (var-get (ns-resolve ns-sym sym)))

(deftest exec-uses-execute-one-when-query-metadata-says-cardinality-one
  (let [captured (atom nil)]
    (with-redefs [jdbc/execute-one! (fn [_ds statement options]
                                      (reset! captured {:statement statement
                                                        :options options})
                                      {:id 42})
                  jdbc/execute! (fn [_ds _statement _options]
                                  (throw (ex-info "should not call execute!" {})))]
      (is (= {:id 42}
             (adapter/exec! ::datasource (query-fn 'bisql.adapter-next-jdbc-test 'example-exec-one) {:id 42})))
      (is (= {:statement ["SELECT * FROM users WHERE id = ?" 42]
              :options {:builder-fn rs/as-unqualified-kebab-maps}}
             @captured)))))

(deftest exec-uses-execute-many-when-query-metadata-says-cardinality-many
  (let [captured (atom nil)]
    (with-redefs [jdbc/execute! (fn [_ds statement options]
                                  (reset! captured {:statement statement
                                                    :options options})
                                  [{:id 42}])
                  jdbc/execute-one! (fn [_ds _statement _options]
                                      (throw (ex-info "should not call execute-one!" {})))]
      (is (= [{:id 42}]
             (adapter/exec! ::datasource (query-fn 'bisql.adapter-next-jdbc-test 'example-exec-many) {:id 42})))
      (is (= {:statement ["SELECT * FROM users WHERE id = ?" 42]
              :options {:builder-fn rs/as-unqualified-kebab-maps}}
             @captured)))))

(deftest exec-rejects-missing-execute-mode
  (let [captured (atom nil)]
    (with-redefs [jdbc/execute! (fn [_ds statement options]
                                  (reset! captured {:statement statement
                                                    :options options})
                                  [{:value 1}])
                  jdbc/execute-one! (fn [_ds _statement _options]
                                      (throw (ex-info "should not call execute-one!" {})))]
      (is (= [{:value 1}]
             (adapter/exec! ::datasource {:sql-template "SELECT 1"} {})))
      (is (= {:statement ["SELECT 1"]
              :options {:builder-fn rs/as-unqualified-kebab-maps}}
             @captured)))))

(deftest adapter-defquery-defines-executable-functions
  (let [captured (atom nil)]
    (with-redefs [jdbc/execute-one! (fn [_ds statement options]
                                      (reset! captured {:statement statement
                                                        :options options})
                                      {:id 42})
                  jdbc/execute! (fn [_ds _statement _options]
                                  (throw (ex-info "should not call execute!" {})))]
      (is (= {:id 42}
             ((query-fn 'sql.adapter.postgresql.public.users.crud 'get-by-id) ::datasource {:id 42})))
      (is (= {:statement ["SELECT * FROM users\nWHERE id = ?" 42]
              :options {:builder-fn rs/as-unqualified-kebab-maps}}
             @captured)))))

(deftest exec-validates-input-when-present
  (with-redefs [jdbc/execute-one! (fn [_ds _statement _options]
                                    {:id 42})]
    (binding [validation/*bisql-malli-validation-mode* :when-present]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Malli validation failed"
           (adapter/exec! ::datasource
                          (query-fn 'bisql.adapter-next-jdbc-test 'example-exec-one-with-malli)
                          {:id "42"}))))))

(deftest exec-validates-output-when-present
  (with-redefs [jdbc/execute-one! (fn [_ds _statement _options]
                                    {:id "42"})]
    (binding [validation/*bisql-malli-validation-mode* :when-present]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Malli validation failed"
           (adapter/exec! ::datasource
                          (query-fn 'bisql.adapter-next-jdbc-test 'example-exec-one-with-malli)
                          {:id 42}))))))

(deftest exec-skips-validation-when-schema-is-missing-in-when-present-mode
  (with-redefs [jdbc/execute-one! (fn [_ds _statement _options]
                                    {:id 42})]
    (binding [validation/*bisql-malli-validation-mode* :when-present]
      (is (= {:id 42}
             (adapter/exec! ::datasource
                            (query-fn 'bisql.adapter-next-jdbc-test 'example-exec-one-without-malli)
                            {:id 42}))))))

(deftest exec-requires-schema-in-strict-mode
  (binding [validation/*bisql-malli-validation-mode* :strict]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Required Malli schema metadata is missing"
         (adapter/exec! ::datasource
                        (query-fn 'bisql.adapter-next-jdbc-test 'example-exec-one-without-malli)
                        {:id 42})))))

(deftest core-defquery-facade-defines-executable-functions
  (let [captured (atom nil)]
    (with-redefs [jdbc/execute-one! (fn [_ds statement options]
                                      (reset! captured {:statement statement
                                                        :options options})
                                      {:id 42})
                  jdbc/execute! (fn [_ds _statement _options]
                                  (throw (ex-info "should not call execute!" {})))]
      (is (= {:id 42}
             ((query-fn 'sql.adapter.core 'example-declarations-valid) ::datasource {:id 42})))
      (is (= {:statement ["SELECT * FROM users WHERE id = ?" 42]
              :options {:builder-fn rs/as-unqualified-kebab-maps}}
             @captured)))))

(deftest timestamp-conversion-helper-supports-common-jvm-types
  (let [legacy-date (Date. 1713052800000)
        instant (Instant/parse "2026-04-14T00:00:00Z")
        offset-datetime (OffsetDateTime/parse "2026-04-14T00:00:00Z")]
    (is (instance? Timestamp (adapter/->timestamp legacy-date)))
    (is (instance? Timestamp (adapter/->timestamp instant)))
    (is (instance? Timestamp (adapter/->timestamp offset-datetime)))))

(deftest date-conversion-helper-supports-common-jvm-types
  (let [legacy-date (Date. 1713052800000)
        local-date (LocalDate/parse "2026-04-14")]
    (is (instance? java.sql.Date (adapter/->date legacy-date)))
    (is (instance? java.sql.Date (adapter/->date local-date)))))

(deftest time-conversion-helper-supports-common-jvm-types
  (let [legacy-date (Date. 1713052800000)
        local-time (LocalTime/parse "12:34:56")]
    (is (instance? Time (adapter/->time legacy-date)))
    (is (instance? Time (adapter/->time local-time)))))
