#!/usr/bin/env bb

(require '[bisql.query :as bisql :refer [load-query load-queries render-query]]
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
 "Variables 4. default bind value"
 (render-query (load-query "demo-variables-default.sql")
               {:email "alice@example.com"
                :status bisql/default}))

(example
 "Control flow 1. if"
 (render-query (load-query "demo-if.sql") {:id 42}))

(example
 "Control flow 2. remove WHERE for falsy if"
 (render-query (load-query "demo-if-where.sql") {:active false}))

(example
 "Control flow 3. remove following AND for falsy if"
 (render-query (load-query "demo-if-and.sql")
               {:active false
                :status "active"}))

(example
 "Control flow 4. remove HAVING for falsy if"
 (render-query (load-query "demo-if-having.sql") {:min-count nil}))

(example
 "Control flow 5. elseif and else"
 (render-query (load-query "demo-if-elseif-else.sql")
               {:active false
                :pending true}))

(example
 "Control flow 6. for"
 (render-query (load-query "demo-for.sql")
               {:id 42
                :items [{:name "display_name" :value "Alice"}
                        {:name "status" :value "active"}]}))

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
