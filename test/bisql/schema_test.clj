(ns bisql.schema-test
  (:require [bisql.schema :as schema]
            [clojure.test :refer [deftest is testing]]))

(deftest malli-map-all-entries-optional-adds-optional-flags
  (testing "two-element map entries gain an options map"
    (is (= [:map
            [:id {:optional true} int?]
            [:email {:optional true} string?]]
           (schema/malli-map-all-entries-optional
            [:map
             [:id int?]
             [:email string?]]))))
  (testing "existing options maps are preserved and forced optional"
    (is (= [:map
            [:id {:title "ID" :optional true} int?]
            [:email {:optional true} string?]]
           (schema/malli-map-all-entries-optional
            [:map
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
    (is (= [:map
            [:id int?]
            [:email string?]]
           (schema/malli-map-all-entries-strip-default-sentinel
            [:map
             [:id [:or int? schema/malli-default-sentinel]]
             [:email string?]]))))
  (testing "existing options maps are preserved"
    (is (= [:map
            [:id {:title "ID"} int?]]
           (schema/malli-map-all-entries-strip-default-sentinel
            [:map
             [:id {:title "ID"} [:or int? schema/malli-default-sentinel]]]))))
  (testing "non-map schemas are rejected"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"requires a :map schema form"
         (schema/malli-map-all-entries-strip-default-sentinel
          [:vector int?])))))
