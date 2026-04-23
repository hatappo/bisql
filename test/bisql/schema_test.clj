(ns bisql.schema-test
  (:require [bisql.schema :as schema]
            [clojure.test :refer [deftest is testing]])
  (:import [java.sql Time Timestamp]
           [java.time LocalDate LocalDateTime LocalTime OffsetDateTime OffsetTime]
           [java.util Date]))

(deftest malli-map-all-entries-optional-adds-optional-flags
  (testing "two-element map entries gain an options map"
    (is (= [:map {:closed true}
            [:id {:optional true} int?]
            [:email {:optional true} string?]]
           (schema/malli-map-all-entries-optional
            [:map {:closed true}
             [:id int?]
             [:email string?]]))))
  (testing "existing options maps are preserved and forced optional"
    (is (= [:map {:closed true}
            [:id {:title "ID" :optional true} int?]
            [:email {:optional true} string?]]
           (schema/malli-map-all-entries-optional
            [:map {:closed true}
             [:id {:title "ID"} int?]
             [:email {:optional false} string?]]))))
  (testing "non-map schemas are rejected"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"requires a :map schema form"
         (schema/malli-map-all-entries-optional
          [:vector int?])))))

(deftest malli-map-all-entries-strip-default-sentinel-removes-default-sentinel-branches
  (testing "two-element map entries drop default sentinel from :or"
    (is (= [:map {:closed true}
            [:id int?]
            [:email string?]]
           (schema/malli-map-all-entries-strip-default-sentinel
            [:map {:closed true}
             [:id [:or int? schema/malli-default-sentinel]]
             [:email string?]]))))
  (testing "existing options maps are preserved"
    (is (= [:map {:closed true}
            [:id {:title "ID"} int?]]
           (schema/malli-map-all-entries-strip-default-sentinel
            [:map {:closed true}
             [:id {:title "ID"} [:or int? schema/malli-default-sentinel]]]))))
  (testing "non-map schemas are rejected"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"requires a :map schema form"
         (schema/malli-map-all-entries-strip-default-sentinel
          [:vector int?])))))

(deftest temporal-predicates-accept-common-jdbc-values
  (let [legacy-date (Date. 1713052800000)
        sql-date (java.sql.Date/valueOf (LocalDate/parse "2026-04-14"))
        sql-time (Time/valueOf (LocalTime/parse "12:34:56"))
        sql-timestamp (Timestamp/from (.toInstant (OffsetDateTime/parse "2026-04-14T12:34:56Z")))]
    (is (schema/local-date? (LocalDate/parse "2026-04-14")))
    (is (schema/local-date? sql-date))
    (is (schema/local-time? (LocalTime/parse "12:34:56")))
    (is (schema/local-time? sql-time))
    (is (schema/offset-time? (OffsetTime/parse "12:34:56+09:00")))
    (is (schema/offset-time? sql-time))
    (is (schema/local-date-time? (LocalDateTime/parse "2026-04-14T12:34:56")))
    (is (schema/local-date-time? sql-timestamp))
    (is (schema/local-date-time? legacy-date))
    (is (schema/offset-date-time? (OffsetDateTime/parse "2026-04-14T12:34:56Z")))
    (is (schema/offset-date-time? sql-timestamp))
    (is (schema/offset-date-time? legacy-date))))
