#!/usr/bin/env bb

(require '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[clojure.pprint :as pprint])

(def doc-pages
  [{:slug "introduction"
    :title "Introduction"
    :file "docs/introduction.md"}
   {:slug "installation"
    :title "Installation"
    :file "docs/installation.md"}
   {:slug "getting-started"
    :title "Getting Started"
    :file "docs/getting-started.md"}
   {:slug "what-is-2-way-sql"
    :title "What is 2-way-SQL"
    :file "docs/what-is-2-way-sql.md"}
   {:slug "sql-file-layout"
    :title "SQL File Layout"
    :file "docs/sql-file-layout.md"}
   {:slug "rendering"
    :title "Rendering"
    :file "docs/rendering.md"}
   {:slug "rendering-examples"
    :title "Rendering Examples"
    :file "docs/rendering-examples.md"}
   {:slug "crud-generation"
    :title "CRUD Generation"
    :file "docs/crud-generation.md"}
   {:slug "generate-clojure-functions"
    :title "Generate Clojure Functions"
    :file "docs/generate-clojure-functions.md"}
   {:slug "declarations-and-metadata"
    :title "Declarations and Metadata"
    :file "docs/declarations-and-metadata.md"}
   {:slug "bisql-adapters"
    :title "Bisql Adapters"
    :file "docs/bisql-adapters.md"}])

(def catalog
  {:title "Docs"
   :pages
   (mapv (fn [{:keys [file] :as page}]
           (let [from-route (str "docs/" (:slug page) "/")
                 relative-path
                 (fn [to-route]
                   (let [from-parts (->> (str/split from-route #"/")
                                         (remove str/blank?)
                                         vec)
                         to-parts (->> (str/split to-route #"/")
                                       (remove str/blank?)
                                       vec)
                         common-count (count (take-while true?
                                                         (map = from-parts to-parts)))
                         up-count (- (count from-parts) common-count)
                         down-parts (subvec to-parts common-count)]
                     (str
                      (apply str (repeat up-count "../"))
                      (when (seq down-parts)
                        (str (str/join "/" down-parts) "/")))))
                 slug->href
                 (into {}
                       (map (fn [{:keys [slug file]}]
                              [(str "(" (-> file io/file .getName) ")")
                               (str "(" (relative-path (str "docs/" slug "/")) ")")])
                            doc-pages))
                 markdown
                 (-> (reduce-kv (fn [s from to]
                                  (str/replace s from to))
                                (slurp file)
                                slug->href)
                     (str/replace "(#/playground)"
                                  (str "(" (relative-path "playground/") ")")))]
             (assoc page :markdown markdown)))
         doc-pages)})

(def output-file
  (io/file "pages/generated/bisql/pages/docs.cljc"))

(io/make-parents output-file)

(spit output-file
      (with-out-str
        (println "(ns bisql.pages.docs)")
        (println)
        (print "(def catalog ")
        (pprint/pprint catalog)
        (println ")")))
