#!/usr/bin/env bb

(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(def template
  (slurp "pages/static/index.html"))

(def site-url
  "https://hatappo.github.io/bisql/")

(def og-image-url
  (str site-url "img/bisql-social.png"))

(def default-description
  "SQL-obsessed 2way-sql data access toolkit for Clojure.")

(defn- prefixed-doc-file?
  [^java.io.File file]
  (boolean (re-matches #"\d{2}-.+\.md" (.getName file))))

(defn- doc-order-key
  [^java.io.File file]
  (let [[_ prefix suffix] (re-matches #"(\d{2})-(.+)\.md" (.getName file))]
    [(parse-long prefix) suffix]))

(defn- doc-slug
  [^java.io.File file]
  (second (re-matches #"\d{2}-(.+)\.md" (.getName file))))

(defn markdown->doc-meta
  [^java.io.File file]
  (let [content (slurp file)
        lines (str/split-lines content)
        title (or (some (fn [line]
                          (when-let [[_ heading] (re-matches #"^#\s+(.+)$" line)]
                            heading))
                        lines)
                  (doc-slug file))
        description (or (some (fn [line]
                                (let [line (str/trim line)]
                                  (when (and (seq line)
                                             (not (str/starts-with? line "#"))
                                             (not (str/starts-with? line "- "))
                                             (not (str/starts-with? line "```"))
                                             (not (str/starts-with? line ">"))
                                             (not (str/starts-with? line "1. ")))
                                    line)))
                              lines)
                        default-description)]
    {:slug (doc-slug file)
     :title title
     :description description}))

(def doc-pages
  (->> (file-seq (io/file "docs"))
       (filter #(.isFile ^java.io.File %))
       (filter #(= (.getParentFile ^java.io.File %) (io/file "docs")))
       (filter prefixed-doc-file?)
       (sort-by doc-order-key)
       (mapv markdown->doc-meta)))

(def example-ids
  (-> "docs/data/rendering-examples.edn"
      slurp
      edn/read-string
      :groups
      (->> (mapcat :examples)
           (map (fn [{:keys [id title description]}]
                  {:id id
                   :title title
                   :description (or description default-description)})))))

(defn absolute-url
  [path]
  (str site-url path))

(defn render-page-meta
  [{:keys [title description path]}]
  {:page-title title
   :og-title title
   :og-description description
   :og-url (absolute-url path)
   :og-image og-image-url})

(defn render-page
  [{:keys [output-file asset-prefix root-prefix page-title og-title og-description og-url og-image]}]
  (let [html (-> template
                 (str/replace "__PAGE_TITLE__" page-title)
                 (str/replace "__OG_TITLE__" og-title)
                 (str/replace "__OG_DESCRIPTION__" og-description)
                 (str/replace "__OG_URL__" og-url)
                 (str/replace "__OG_IMAGE__" og-image)
                 (str/replace "__ASSET_PREFIX__" asset-prefix)
                 (str/replace "__ROOT_PREFIX__" root-prefix))]
    (io/make-parents output-file)
    (spit output-file html)))

(render-page (merge {:output-file "pages/dist/index.html"
                     :asset-prefix "./"
                     :root-prefix "./"}
                    (render-page-meta {:title "bisql Docs"
                                       :description default-description
                                       :path ""})))

(render-page (merge {:output-file "pages/dist/docs/index.html"
                     :asset-prefix "../"
                     :root-prefix "../"}
                    (render-page-meta {:title "bisql Docs"
                                       :description default-description
                                       :path "docs/"})))

(doseq [{:keys [slug title description]} doc-pages]
  (render-page (merge {:output-file (str "pages/dist/docs/" slug "/index.html")
                       :asset-prefix "../../"
                       :root-prefix "../../"}
                      (render-page-meta {:title (str title " · bisql Docs")
                                         :description description
                                         :path (str "docs/" slug "/")}))))

(render-page (merge {:output-file "pages/dist/playground/index.html"
                     :asset-prefix "../"
                     :root-prefix "../"}
                    (render-page-meta {:title "bisql Playground"
                                       :description "Try Bisql rendering examples in the browser."
                                       :path "playground/"})))

(doseq [{:keys [id title description]} example-ids]
  (render-page (merge {:output-file (str "pages/dist/playground/" id "/index.html")
                       :asset-prefix "../../"
                       :root-prefix "../../"}
                      (render-page-meta {:title (str title " · bisql Playground")
                                         :description description
                                         :path (str "playground/" id "/")}))))
