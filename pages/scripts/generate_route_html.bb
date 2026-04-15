#!/usr/bin/env bb

(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(def template
  (slurp "pages/static/index.html"))

(def doc-slugs
  (->> (.listFiles (io/file "docs"))
       (filter #(.isFile ^java.io.File %))
       (map #(.getName ^java.io.File %))
       (filter #(str/ends-with? % ".md"))
       (map #(str/replace % #"\.md$" ""))
       sort))

(def example-ids
  (-> "docs/data/rendering-examples.edn"
      slurp
      edn/read-string
      :groups
      (->> (mapcat :examples)
           (map :id))))

(defn render-page
  [{:keys [output-file asset-prefix root-prefix]}]
  (let [html (-> template
                 (str/replace "__ASSET_PREFIX__" asset-prefix)
                 (str/replace "__ROOT_PREFIX__" root-prefix))]
    (io/make-parents output-file)
    (spit output-file html)))

(render-page {:output-file "pages/dist/index.html"
              :asset-prefix "./"
              :root-prefix "./"})

(render-page {:output-file "pages/dist/docs/index.html"
              :asset-prefix "../"
              :root-prefix "../"})

(doseq [slug doc-slugs]
  (render-page {:output-file (str "pages/dist/docs/" slug "/index.html")
                :asset-prefix "../../"
                :root-prefix "../../"}))

(render-page {:output-file "pages/dist/playground/index.html"
              :asset-prefix "../"
              :root-prefix "../"})

(doseq [example-id example-ids]
  (render-page {:output-file (str "pages/dist/playground/" example-id "/index.html")
                :asset-prefix "../../"
                :root-prefix "../../"}))
