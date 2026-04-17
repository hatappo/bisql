#!/usr/bin/env bb

(require '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[clojure.pprint :as pprint])

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

(defn- doc-title
  [file]
  (or (some->> (line-seq (java.io.BufferedReader. (io/reader file)))
               (some (fn [line]
                       (when-let [[_ title] (re-matches #"^#\s+(.+)$" line)]
                         title))))
      (-> file doc-slug (str/replace #"-" " "))))

(def doc-pages
  (->> (file-seq (io/file "docs"))
       (filter #(.isFile ^java.io.File %))
       (filter #(= (.getParentFile ^java.io.File %) (io/file "docs")))
       (filter prefixed-doc-file?)
       (sort-by doc-order-key)
       (mapv (fn [file]
               {:slug (doc-slug file)
                :title (doc-title file)
                :file (.getPath ^java.io.File file)}))))

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
