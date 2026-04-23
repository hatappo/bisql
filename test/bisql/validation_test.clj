(ns bisql.validation-test
  (:require [bisql.validation :as validation]
            [clojure.test :refer [deftest is testing]]))

(deftest set-malli-validation-mode-bang-updates-root-mode
  (let [original-mode (validation/validation-mode)]
    (try
      (testing "keyword mode updates both phases"
        (is (= {:in :when-present
                :out :when-present}
               (validation/set-malli-validation-mode! :when-present)))
        (is (= {:in :when-present
                :out :when-present}
               (validation/validation-mode))))
      (testing "map mode can control phases independently"
        (is (= {:in :strict
                :out :off}
               (validation/set-malli-validation-mode! {:in :strict
                                                       :out :off})))
        (is (= :strict
               (validation/phase-mode :in)))
        (is (= :off
               (validation/phase-mode :out))))
      (finally
        (validation/set-malli-validation-mode! original-mode)))))
