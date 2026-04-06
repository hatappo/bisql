#!/usr/bin/env bb

(require '[bisql.query :refer [load-query load-queries render-query]]
         '[demo.lib :refer [error-example example]])

(example
 "Variables 1. bind values"
 (render-query (load-query "demo-variables-bind.sql") {:id 42}))

(example
 "Variables 2. literal values"
 (render-query (load-query "demo-variables-literal.sql") {:type "BOOK"}))

(example
 "Variables 3. raw values"
 (render-query (load-query "demo-variables-raw.sql") {:order-by "created_at DESC"}))

(example
 "Control flow 1. if"
 (render-query (load-query "demo-if.sql") {:id 42}))

(example
 "Declarations"
 (render-query (load-query "demo-declarations.sql") {:id 42}))

(example
 "Declarations 2. multiple templates 1"
 (render-query (get (load-queries "demo-multi-queries.sql") "find-user-by-id")
               {:id 42}))

(example
 "Declarations 3. multiple templates 2"
 (render-query (get (load-queries "demo-multi-queries.sql") "find-user-by-email")
               {:email "user@example.com"}))

(example
 "Load query from custom path"
 (render-query (load-query "demo-custom-path.sql" {:base-path "demo"}) {:id 42}))

(example
 "Render query by passing SQL template directly"
 (render-query {:sql-template "SELECT * FROM users WHERE id = /*$id*/1"} {:id 42}))

(error-example
 "Errors 1. missing bind parameter"
 (render-query (load-query "demo-variables-bind.sql") {:ic 42}))
