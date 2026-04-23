(ns bisql.test-runner
  (:require [clojure.test :as t]
            [bisql.adapter-next-jdbc-test]
            [bisql.cli-test]
            [bisql.schema-test]
            [bisql.validation-test]
            [bisql.crud-test]
            [bisql.core-test]))

(defn -main
  [& _args]
  (let [{:keys [fail error]} (t/run-tests 'bisql.adapter-next-jdbc-test
                                          'bisql.cli-test
                                          'bisql.schema-test
                                          'bisql.validation-test
                                          'bisql.core-test
                                          'bisql.crud-test)]
    (when (pos? (+ fail error))
      (throw (ex-info "Tests failed."
                      {:fail fail
                       :error error})))))
