#!/usr/bin/env bb

(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.pprint :as pprint]
         '[bisql.query :as bisql])

(def catalog
  (-> "docs/data/rendering-examples.edn"
      slurp
      edn/read-string))

(defn embed-example-input
  [{:keys [kind resource load-options query-name sql-template] :as input}]
  (case kind
    :resource
    (assoc input :sql-template
           (:sql-template
            (if (seq load-options)
              (bisql/load-query resource load-options)
              (bisql/load-query resource))))

    :resource-query
    (let [template
          (get (if (seq load-options)
                 (bisql/load-queries resource load-options)
                 (bisql/load-queries resource))
               query-name)]
      (when-not template
        (throw (ex-info "Named SQL template not found."
                        {:resource resource
                         :query-name query-name})))
      (assoc input :sql-template (:sql-template template)))

    :direct-template
    input))

(def embedded-catalog
  (update catalog :groups
          (fn [groups]
            (mapv (fn [group]
                    (update group :examples
                            (fn [examples]
                              (mapv (fn [example]
                                      (update example :input embed-example-input))
                                    examples))))
                  groups))))

(defn quoted-special-symbols
  [x]
  (cond
    (and (symbol? x)
         (#{"bisql/DEFAULT" "bisql/ALL"} (str x)))
    (list 'quote x)

    (map? x)
    (into (empty x) (map (fn [[k v]] [k (quoted-special-symbols v)])) x)

    (vector? x)
    (mapv quoted-special-symbols x)

    (list? x)
    (apply list (map quoted-special-symbols x))

    (seq? x)
    (doall (map quoted-special-symbols x))

    :else x))

(def output-file
  (io/file "pages/generated/bisql/pages/examples.cljc"))

(io/make-parents output-file)

(spit output-file
      (with-out-str
        (println "(ns bisql.pages.examples)")
        (println)
        (print "(def catalog ")
        (pprint/pprint (quoted-special-symbols embedded-catalog))
        (println ")")))
