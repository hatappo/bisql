(ns db.generate-crud-files
  (:require [bisql.core :as bisql]
            [next.jdbc :as jdbc]))

(defn -main
  [& args]
  (let [output-root (or (first args) "resources/sql")
        datasource (jdbc/get-datasource {:dbtype "postgresql"
                                         :host "localhost"
                                         :port 5432
                                         :dbname "bisql_dev"
                                         :user "bisql"
                                         :password "bisql"})
        crud-result (bisql/generate-crud datasource {:schema "public"})
        file-result (bisql/write-crud-files! crud-result {:output-root output-root})
        file-count (count (:files file-result))
        template-count (count (:templates crud-result))]
    (println (str "Wrote "
                  file-count
                  " CRUD SQL files ("
                  template-count
                  " SQL templates) to "
                  output-root))
    (doseq [{:keys [path]} (:files file-result)]
      (println path))))
