(ns bisql.test-db-runner
  (:require [clojure.test :as t]
            [bisql.db-test]))

(defn -main
  [& _args]
  (let [{:keys [fail error]} (t/run-tests 'bisql.db-test)]
    (when (pos? (+ fail error))
      (throw (ex-info "DB tests failed."
                      {:fail fail
                       :error error})))))
