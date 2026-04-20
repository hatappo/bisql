(ns pages.sync
  (:require [babashka.process :as process]))

(defn- run-cmd!
  [cmd]
  (let [proc (process/process cmd {:out :inherit
                                   :err :inherit})
        result @proc]
    (when-not (zero? (:exit result))
      (throw (ex-info "Command failed." {:cmd cmd
                                         :exit (:exit result)})))))

(defn sync-pages!
  []
  (run-cmd! ["mkdir" "-p" "pages/dist"])
  (run-cmd! ["bb" "pages/scripts/generate_examples_catalog.bb"])
  (run-cmd! ["bb" "pages/scripts/generate_docs_catalog.bb"])
  (run-cmd! ["bb" "pages/scripts/generate_route_html.bb"])
  (run-cmd! ["cp" "pages/static/playground.css" "pages/dist/playground.css"])
  (run-cmd! ["mkdir" "-p" "pages/dist/img"])
  (run-cmd! ["cp" "docs/img/bicycle.svg" "pages/dist/img/bicycle.svg"])
  (run-cmd! ["cp" "-R" "pages/static/img/." "pages/dist/img"]))

(defn -main
  [& _args]
  (sync-pages!))

(apply -main *command-line-args*)
