# Rendering Examples

### Variables 1. bind values 

1. Input Form:
```clj
(render-query (load-query "demo-variables-bind.sql") {:id 42})
```

2. Input SQL:
```sql
SELECT * FROM users WHERE id = /*$id*/1
```

3. Output SQL:
```sql
SELECT * FROM users WHERE id = ?
```

4. Output Data:
```clj
{:query-name "demo-variables-bind",
 :base-path "sql",
 :resource-path "sql/demo-variables-bind.sql",
 :sql "SELECT * FROM users WHERE id = ?",
 :params [42],
 :meta {}}
```


### Variables 2. literal values 

1. Input Form:
```clj
(render-query (load-query "demo-variables-literal.sql") {:type "BOOK"})
```

2. Input SQL:
```sql
SELECT * FROM users WHERE type = /*^type*/'A'
```

3. Output SQL:
```sql
SELECT * FROM users WHERE type = 'BOOK'
```

4. Output Data:
```clj
{:query-name "demo-variables-literal",
 :base-path "sql",
 :resource-path "sql/demo-variables-literal.sql",
 :sql "SELECT * FROM users WHERE type = 'BOOK'",
 :params [],
 :meta {}}
```


### Variables 3. raw values 

1. Input Form:
```clj
(render-query (load-query "demo-variables-raw.sql") {:order-by "created_at DESC"})
```

2. Input SQL:
```sql
SELECT * FROM users ORDER BY /*!order-by*/id
```

3. Output SQL:
```sql
SELECT * FROM users ORDER BY created_at DESC
```

4. Output Data:
```clj
{:query-name "demo-variables-raw",
 :base-path "sql",
 :resource-path "sql/demo-variables-raw.sql",
 :sql "SELECT * FROM users ORDER BY created_at DESC",
 :params [],
 :meta {}}
```


### Variables 4. default bind value 

1. Input Form:
```clj
(render-query (load-query "demo-variables-default.sql") {:email "alice@example.com", :status bisql/default})
```

2. Input SQL:
```sql
INSERT INTO users (email, status)
VALUES (/*$email*/'user@example.com', /*$status*/'active')
```

3. Output SQL:
```sql
INSERT INTO users (email, status)
VALUES (?, DEFAULT)
```

4. Output Data:
```clj
{:query-name "demo-variables-default",
 :base-path "sql",
 :resource-path "sql/demo-variables-default.sql",
 :sql "INSERT INTO users (email, status)\nVALUES (?, DEFAULT)",
 :params ["alice@example.com"],
 :meta {}}
```


### Control flow 1. if 

1. Input Form:
```clj
(render-query (load-query "demo-if.sql") {:id 42})
```

2. Input SQL:
```sql
SELECT * FROM users /*%if id */ WHERE id = /*$id*/1 /*%end*/
```

3. Output SQL:
```sql
SELECT * FROM users  WHERE id = ?
```

4. Output Data:
```clj
{:query-name "demo-if",
 :base-path "sql",
 :resource-path "sql/demo-if.sql",
 :sql "SELECT * FROM users  WHERE id = ?",
 :params [42],
 :meta {}}
```


### Control flow 2. remove WHERE for falsy if 

1. Input Form:
```clj
(render-query (load-query "demo-if-where.sql") {:active false})
```

2. Input SQL:
```sql
SELECT *
FROM users
WHERE
/*%if active */
  active = true
/*%end */
```

3. Output SQL:
```sql
SELECT *
FROM users
```

4. Output Data:
```clj
{:query-name "demo-if-where",
 :base-path "sql",
 :resource-path "sql/demo-if-where.sql",
 :sql "SELECT *\nFROM users",
 :params [],
 :meta {}}
```


### Control flow 3. remove following AND for falsy if 

1. Input Form:
```clj
(render-query (load-query "demo-if-and.sql") {:active false, :status "active"})
```

2. Input SQL:
```sql
SELECT *
FROM users
WHERE
/*%if active */
  active = true
/*%end */
AND status = /*$status*/'active'
```

3. Output SQL:
```sql
SELECT *
FROM users
WHERE
status = ?
```

4. Output Data:
```clj
{:query-name "demo-if-and",
 :base-path "sql",
 :resource-path "sql/demo-if-and.sql",
 :sql "SELECT *\nFROM users\nWHERE\nstatus = ?",
 :params ["active"],
 :meta {}}
```


### Control flow 4. remove HAVING for falsy if 

1. Input Form:
```clj
(render-query (load-query "demo-if-having.sql") {:min-count nil})
```

2. Input SQL:
```sql
SELECT status, count(*)
FROM users
GROUP BY status
HAVING
/*%if min-count */
  count(*) >= /*$min-count*/1
/*%end */
```

3. Output SQL:
```sql
SELECT status, count(*)
FROM users
GROUP BY status
```

4. Output Data:
```clj
{:query-name "demo-if-having",
 :base-path "sql",
 :resource-path "sql/demo-if-having.sql",
 :sql "SELECT status, count(*)\nFROM users\nGROUP BY status",
 :params [],
 :meta {}}
```


### Control flow 5. elseif and else 

1. Input Form:
```clj
(render-query (load-query "demo-if-elseif-else.sql") {:active false, :pending true})
```

2. Input SQL:
```sql
SELECT *
FROM users
WHERE
/*%if active */
  active = true
/*%elseif pending */
  status = 'pending'
/*%else */
  status = 'inactive'
/*%end */
```

3. Output SQL:
```sql
SELECT *
FROM users
WHERE
  status = 'pending'
```

4. Output Data:
```clj
{:query-name "demo-if-elseif-else",
 :base-path "sql",
 :resource-path "sql/demo-if-elseif-else.sql",
 :sql "SELECT *\nFROM users\nWHERE\n  status = 'pending'",
 :params [],
 :meta {}}
```


### Control flow 6. for 

1. Input Form:
```clj
(render-query (load-query "demo-for.sql") {:id 42, :items [{:name "display_name", :value "Alice"} {:name "status", :value "active"}]})
```

2. Input SQL:
```sql
UPDATE users
SET
/*%for item in items separating , */
  /*!item.name*/ = /*$item.value*/'sample'
/*%end */
WHERE id = /*$id*/1
```

3. Output SQL:
```sql
UPDATE users
SET
  display_name = ?,
  status = ?
WHERE id = ?
```

4. Output Data:
```clj
{:query-name "demo-for",
 :base-path "sql",
 :resource-path "sql/demo-for.sql",
 :sql
 "UPDATE users\nSET\n  display_name = ?,\n  status = ?\nWHERE id = ?",
 :params ["Alice" "active" 42],
 :meta {}}
```


### Declarations 

1. Input Form:
```clj
(render-query (load-query "demo-declarations.sql") {:id 42})
```

2. Input SQL:
```sql
/*:doc
Loads a user by id.
*/
/*:tags [:example :user] */
/*:returns :one */
/* This is a normal comment */
SELECT * FROM users WHERE id = /*$id*/1
```

3. Output SQL:
```sql
/* This is a normal comment */
SELECT * FROM users WHERE id = ?
```

4. Output Data:
```clj
{:query-name "demo-declarations",
 :base-path "sql",
 :resource-path "sql/demo-declarations.sql",
 :sql
 "/* This is a normal comment */\nSELECT * FROM users WHERE id = ?",
 :params [42],
 :meta
 {:doc "Loads a user by id.", :tags [:example :user], :returns :one}}
```


### Declarations 2. multiple templates 1 

1. Input Form:
```clj
(render-query (get (load-queries "demo-multi-queries.sql") "find-user-by-id") {:id 42})
```

2. Input SQL:
```sql
/*:name find-user-by-id */
SELECT * FROM users WHERE id = /*$id*/1
```

3. Output SQL:
```sql
SELECT * FROM users WHERE id = ?
```

4. Output Data:
```clj
{:query-name "find-user-by-id",
 :base-path "sql",
 :resource-path "sql/demo-multi-queries.sql",
 :sql "SELECT * FROM users WHERE id = ?",
 :params [42],
 :meta {:name find-user-by-id}}
```


### Declarations 3. multiple templates 2 

1. Input Form:
```clj
(render-query (get (load-queries "demo-multi-queries.sql") "find-user-by-email") {:email "user@example.com"})
```

2. Input SQL:
```sql
/*:name find-user-by-email */
SELECT * FROM users WHERE email = /*$email*/'user@example.com'
```

3. Output SQL:
```sql
SELECT * FROM users WHERE email = ?
```

4. Output Data:
```clj
{:query-name "find-user-by-email",
 :base-path "sql",
 :resource-path "sql/demo-multi-queries.sql",
 :sql "SELECT * FROM users WHERE email = ?",
 :params ["user@example.com"],
 :meta {:name find-user-by-email}}
```


### Load query from custom path 

1. Input Form:
```clj
(render-query (load-query "demo-custom-path.sql" {:base-path "demo"}) {:id 42})
```

2. Input SQL:
```sql
SELECT * FROM users WHERE id = /*$id*/1
```

3. Output SQL:
```sql
SELECT * FROM users WHERE id = ?
```

4. Output Data:
```clj
{:query-name "demo-custom-path",
 :base-path "demo",
 :resource-path "demo/demo-custom-path.sql",
 :sql "SELECT * FROM users WHERE id = ?",
 :params [42],
 :meta {}}
```


### Render query by passing SQL template directly 

1. Input Form:
```clj
(render-query {:sql-template "SELECT * FROM users WHERE id = /*$id*/1"} {:id 42})
```

2. Input SQL:
```sql
SELECT * FROM users WHERE id = /*$id*/1
```

3. Output SQL:
```sql
SELECT * FROM users WHERE id = ?
```

4. Output Data:
```clj
{:sql "SELECT * FROM users WHERE id = ?", :params [42], :meta {}}
```


### Errors 1. missing bind parameter 

1. Input Form:
```clj
(render-query (load-query "demo-variables-bind.sql") {:ic 42})
```

2. Input SQL:
```sql
SELECT * FROM users WHERE id = /*$id*/1
```

3. Output Error:
```clj
{:message "Missing query parameter.",
 :data
 {:query-name "demo-variables-bind",
  :base-path "sql",
  :resource-path "sql/demo-variables-bind.sql",
  :parameter :id,
  :sigil "$",
  :collection? false}}
```
