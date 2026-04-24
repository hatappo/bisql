(ns bisql.test-build-runner
  (:require [clojure.test :as t]
            [bisql.build-test]))

(defn -main
  [& _args]
  (let [{:keys [fail error]} (t/run-tests 'bisql.build-test)]
    (when (pos? (+ fail error))
      (throw (ex-info "Build tests failed."
                      {:fail fail
                       :error error})))))
