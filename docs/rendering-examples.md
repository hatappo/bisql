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
 :doc nil,
 :meta nil}
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
 :doc nil,
 :meta nil}
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
 :doc nil,
 :meta nil}
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
 :doc nil,
 :meta nil}
```


### Declarations 

1. Input Form:
```clj
(render-query (load-query "demo-declarations.sql") {:id 42})
```

2. Input SQL:
```sql
/*:doc Loads a user by id. */
/*:meta {:tags [:example :user] :returns :one} */
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
 :doc "Loads a user by id.",
 :meta {:tags [:example :user], :returns :one}}
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
 :doc nil,
 :meta nil}
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
 :doc nil,
 :meta nil}
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
 :doc nil,
 :meta nil}
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
{:sql "SELECT * FROM users WHERE id = ?",
 :params [42],
 :doc nil,
 :meta nil}
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

