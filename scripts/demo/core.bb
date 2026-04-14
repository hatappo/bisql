#!/usr/bin/env bb

(require '[clojure.edn :as edn]
         '[demo.lib :as demo])

(def examples-doc
  (-> "docs/data/rendering-examples.edn"
      slurp
      edn/read-string))

(println (str "# " (:title examples-doc)))

(doseq [{:keys [title examples]} (:groups examples-doc)]
  (println)
  (println (str "## " title))
  (doseq [example examples]
    (if (:error? example)
      (demo/show-error-example example)
      (demo/show-example example))))

(when-let [notes (seq (:notes examples-doc))]
  (println "## Notes")
  (println)
  (doseq [note notes]
    (println (str "- " note))))
