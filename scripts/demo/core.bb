#!/usr/bin/env bb

(require '[bisql.query :as bisql :refer [load-query load-queries render-query]]
         '[demo.lib :refer [error-example example]])

(defn render
  ([template-or-resource params]
   (render-query (if (string? template-or-resource)
                   (load-query template-or-resource)
                   template-or-resource)
                 params))
  ([resource load-options params]
   (render-query (load-query resource load-options) params)))

(example
 "Variables 1. bind values"
 (render "demo-variables-bind.sql" {:id 42}))

(example
 "Variables 2. literal values"
 (render "demo-variables-literal.sql" {:type "BOOK"}))

(example
 "Variables 3. raw values"
 (render "demo-variables-raw.sql" {:order-by "created_at DESC"}))

(example
 "Variables 4. default bind value"
 (render "demo-variables-default.sql"
         {:email "alice@example.com"
          :status bisql/default}))

(example
 "Variables 5. ALL bind value"
 (render "demo-variables-all.sql"
         {:set-quantifier bisql/ALL}))

(example
 "Control flow 1. if"
 (render "demo-if.sql" {:id 42}))

(example
 "Control flow 2. remove WHERE for falsy if"
 (render "demo-if-where.sql" {:active false}))

(example
 "Control flow 3. remove following AND for falsy if"
 (render "demo-if-and.sql"
         {:active false
          :status "active"}))

(example
 "Control flow 4. remove HAVING for falsy if"
 (render "demo-if-having.sql" {:min-count nil}))

(example
 "Control flow 5. inline else"
 (render "demo-if-inline-else.sql" {:active false}))

(example
 "Control flow 6. elseif and else"
 (render "demo-if-elseif-else.sql"
         {:active false
          :pending true}))

(example
 "Control flow 7. inline elseif and else"
 (render "demo-if-inline-elseif-else.sql"
         {:active false
          :pending true}))

(example
 "Control flow 8. for"
 (render "demo-for.sql"
         {:id 42
          :items [{:name "display_name" :value "Alice"}
                  {:name "status" :value "active"}]}))

(example
 "Declarations"
 (render "demo-declarations.sql" {:id 42}))

(example
 "Declarations 2. multiple templates 1"
 (render (get (load-queries "demo-multi-queries.sql") "core.find-user-by-id")
         {:id 42}))

(example
 "Declarations 3. multiple templates 2"
 (render (get (load-queries "demo-multi-queries.sql") "core.find-user-by-email")
         {:email "user@example.com"}))

(example
 "Load query from custom path"
 (render "demo-custom-path.sql" {:base-path "demo"} {:id 42}))

(example
 "Render query by passing SQL template directly"
 (render {:sql-template "SELECT * FROM users WHERE id = /*$id*/1"} {:id 42}))

(error-example
 "Errors 1. missing bind parameter"
 (render "demo-variables-bind.sql" {:ic 42}))
