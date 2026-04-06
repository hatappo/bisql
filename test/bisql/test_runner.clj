(ns bisql.test-runner
  (:require [clojure.test :as t]
            [bisql.crud-test]
            [bisql.core-test]))

(defn -main
  [& _args]
  (let [{:keys [fail error]} (t/run-tests 'bisql.core-test
                                          'bisql.crud-test)]
    (when (pos? (+ fail error))
      (throw (ex-info "Tests failed."
                      {:fail fail
                       :error error})))))
