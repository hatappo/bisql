(ns pages.dev
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def poll-interval-ms 700)

(defn- run-sync!
  []
  (let [proc (process/process ["bb" "scripts/pages/sync.bb"]
                              {:out :inherit
                               :err :inherit})
        result @proc]
    (when-not (zero? (:exit result))
      (throw (ex-info "pages sync failed." {:exit (:exit result)})))))

(defn- watched-files
  []
  (->> ["docs"
        "pages/static"
        "pages/scripts"]
       (map fs/path)
       (filter fs/exists?)
       (mapcat (fn [dir]
                 (->> (file-seq (fs/file dir))
                      (filter #(.isFile %)))))
       (map fs/path)
       (filter (fn [path]
                 (let [s (str path)]
                   (or (str/ends-with? s ".md")
                       (str/ends-with? s ".edn")
                       (str/ends-with? s ".bb")
                       (str/ends-with? s ".css")
                       (str/ends-with? s ".html")
                       (str/ends-with? s ".svg")
                       (str/ends-with? s ".png")
                       (str/ends-with? s ".webp")
                       (str/ends-with? s ".jpeg")
                       (str/ends-with? s ".jpg")))))
       (remove #(re-find #"/dist/" (str %)))
       sort))

(defn- snapshot
  []
  (into (sorted-map)
        (map (fn [path]
               [(str path)
                (.toMillis (fs/last-modified-time path))]))
        (watched-files)))

(defn- watch-assets!
  [stop?]
  (loop [state (snapshot)]
    (when-not @stop?
      (Thread/sleep poll-interval-ms)
      (let [next-state (snapshot)]
        (if (= state next-state)
          (recur state)
          (do
            (println "[pages-dev] Detected asset/docs change. Syncing Pages files...")
            (run-sync!)
            (println "[pages-dev] Sync completed.")
            (recur next-state)))))))

(defn -main
  [& _args]
  (run-sync!)
  (println "[pages-dev] Initial sync completed.")
  (let [stop? (atom false)
        watcher (future (watch-assets! stop?))
        proc (process/process
              ["sh" "-lc"
               "cd pages && clojure -J--enable-native-access=ALL-UNNAMED -J--sun-misc-unsafe-memory-access=allow -M -m shadow.cljs.devtools.cli watch playground"]
              {:out :inherit
               :err :inherit})
        result @proc]
    (reset! stop? true)
    @watcher
    (when-not (zero? (:exit result))
      (throw (ex-info "pages-dev failed." {:exit (:exit result)})))))

(apply -main *command-line-args*)
