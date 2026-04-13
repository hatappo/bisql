#!/usr/bin/env bb

(require '[bisql.query :as bisql]
         '[demo.lib :refer [error-example example]])

(defn render
  ([template-or-resource params]
   (bisql/render-query (if (string? template-or-resource)
                         (bisql/load-query template-or-resource)
                         template-or-resource)
                       params))
  ([resource load-options params]
   (bisql/render-query (bisql/load-query resource load-options) params)))

(println "# Rendering Examples")
(println "")
(println "## 1. Variables")

(example
 "1-1: bind values"
 (render "demo-variables-bind.sql" {:id 42})
 "`/*$ */` comments become bind variables. If an adjacent sample value is present, as in `1` here, it is removed from the rendered SQL.")

(example
 "1-2: literal values"
 (render "demo-variables-literal.sql" {:type "BOOK"})
 "`^` is rendered as a SQL literal. Strings are quoted with `'`.")

(example
 "1-3: raw values"
 (render "demo-variables-raw.sql" {:order-by "created_at DESC"})
 "`!` is inserted into SQL as-is. It is useful for cases like `ORDER BY`, but it must not be fed directly from user input.")

(example
 "1-4: default bind value"
 (render "demo-variables-default.sql"
         {:email "alice@example.com"
          :status bisql/default})
 "`bisql/default` is rendered as the SQL keyword `DEFAULT` instead of a bind parameter. This is useful in `VALUES` clauses.")

(example
 "1-5: ALL bind value"
 (render "demo-variables-all.sql"
         {:limit bisql/ALL})
 "`bisql/ALL` is rendered as the SQL keyword `ALL` instead of a bind parameter. This is useful in clauses such as `LIMIT ALL`.")

(println "")
(println "## 2. Control flow")

(example
 "2-1: if"
 (render "demo-if.sql" {:id 42}))

(example
 "2-2: remove WHERE for falsy if"
 (render "demo-if-where.sql" {:active false})
 "When the condition is false, the preceding `WHERE` or `HAVING` keyword is removed automatically.")

(example
 "2-3: remove following AND for falsy if"
 (render "demo-if-and.sql"
         {:active false
          :status "active"})
 "When the condition is false, a following `AND` is removed automatically. In this case, the preceding `WHERE` or `HAVING` remains in place.")

(example
 "2-4: elseif and else"
 (render "demo-if-elseif-else.sql"
         {:active false
          :pending true}))

(example
 "2-5: inline elseif and else"
 (render "demo-if-inline-elseif-else.sql"
         {:active false
          :pending true})
 "`else` and `elseif` bodies can also be written inline inside the directive comment by using `=>`. This keeps the template closer to executable SQL.")

(example
 "2-6: for"
 (render "demo-for.sql"
         {:id 42
          :items [{:name "display_name" :value "Alice"}
                  {:name "status" :value "active"}]})
 "`for` loops can declare a separator with `separating`. This is useful when joining an arbitrary number of fragments with `,`, `AND`, or `OR`.")

(println "")
(println "## 3. Declarations")

(example
 "3-1: docstring and metadata"
 (render "demo-declarations.sql" {:id 42}))

(example
 "3-2: multiple SQL templates in one file"
 (render (get (bisql/load-queries "demo-multi-queries.sql") "core.find-user-by-id")
         {:id 42})
 "A single SQL file can contain multiple templates. Each template starts with a `/*:name */` directive that defines the generated function name.")

(println "")
(println "## 4. Others")

(example
 "4-1: render query by passing a SQL template directly"
 (render {:sql-template "SELECT * FROM users WHERE id = /*$id*/1"} {:id 42})
 "You can also pass a SQL template directly from Clojure code. This is useful for debugging or learning, but application queries should generally live in SQL files.")

(error-example
 "4-2: Errors: missing bind parameter"
 (render "demo-variables-bind.sql" {:ic 42}))

(println "## Notes

- The sample code assumes `(require '[bisql.query :as bisql])`.")
