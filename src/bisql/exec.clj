(ns bisql.exec
  (:require [bisql.query :as query]
            [next.jdbc :as jdbc]))

(defn execute!
  "Renders and executes a query via next.jdbc/execute!."
  [datasource loaded-query params]
  (let [{:keys [sql params]} (query/render-query loaded-query params)]
    (jdbc/execute! datasource (into [sql] params))))

(defn execute-one!
  "Renders and executes a query via next.jdbc/execute-one!."
  [datasource loaded-query params]
  (let [{:keys [sql params]} (query/render-query loaded-query params)]
    (jdbc/execute-one! datasource (into [sql] params))))
