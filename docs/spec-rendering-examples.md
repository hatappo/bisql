# Rendering Examples

### Variables 1. bind values 

1. Input Form:
```clj
(render "demo-variables-bind.sql" {:id 42})
```

2. Input SQL:
```sql
SELECT * FROM users WHERE id = /*$id*/1
```

3. Output SQL and Params:
```sql
SELECT * FROM users WHERE id = ?
```

```clj
[42]
```


### Variables 2. literal values 

1. Input Form:
```clj
(render "demo-variables-literal.sql" {:type "BOOK"})
```

2. Input SQL:
```sql
SELECT * FROM users WHERE type = /*^type*/'A'
```

3. Output SQL and Params:
```sql
SELECT * FROM users WHERE type = 'BOOK'
```

```clj
[]
```


### Variables 3. raw values 

1. Input Form:
```clj
(render "demo-variables-raw.sql" {:order-by "created_at DESC"})
```

2. Input SQL:
```sql
SELECT * FROM users ORDER BY /*!order-by*/id
```

3. Output SQL and Params:
```sql
SELECT * FROM users ORDER BY created_at DESC
```

```clj
[]
```


### Variables 4. default bind value 

1. Input Form:
```clj
(render "demo-variables-default.sql" {:email "alice@example.com", :status bisql/default})
```

2. Input SQL:
```sql
INSERT INTO users (email, status)
VALUES (/*$email*/'user@example.com', /*$status*/'active')
```

3. Output SQL and Params:
```sql
INSERT INTO users (email, status)
VALUES (?, DEFAULT)
```

```clj
["alice@example.com"]
```


### Variables 5. ALL bind value 

1. Input Form:
```clj
(render "demo-variables-all.sql" {:set-quantifier bisql/ALL})
```

2. Input SQL:
```sql
SELECT /*$set-quantifier*/DISTINCT * FROM users
```

3. Output SQL and Params:
```sql
SELECT ALL * FROM users
```

```clj
[]
```


### Control flow 1. if 

1. Input Form:
```clj
(render "demo-if.sql" {:id 42})
```

2. Input SQL:
```sql
SELECT * FROM users /*%if id */ WHERE id = /*$id*/1 /*%end*/
```

3. Output SQL and Params:
```sql
SELECT * FROM users  WHERE id = ?
```

```clj
[42]
```


### Control flow 2. remove WHERE for falsy if 

1. Input Form:
```clj
(render "demo-if-where.sql" {:active false})
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

3. Output SQL and Params:
```sql
SELECT *
FROM users
```

```clj
[]
```


### Control flow 3. remove following AND for falsy if 

1. Input Form:
```clj
(render "demo-if-and.sql" {:active false, :status "active"})
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

3. Output SQL and Params:
```sql
SELECT *
FROM users
WHERE
status = ?
```

```clj
["active"]
```


### Control flow 4. remove HAVING for falsy if 

1. Input Form:
```clj
(render "demo-if-having.sql" {:min-count nil})
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

3. Output SQL and Params:
```sql
SELECT status, count(*)
FROM users
GROUP BY status
```

```clj
[]
```


### Control flow 5. inline else 

1. Input Form:
```clj
(render "demo-if-inline-else.sql" {:active false})
```

2. Input SQL:
```sql
SELECT *
FROM users
WHERE
/*%if active */
  active = true
/*%else => status = 'inactive' */
/*%end */
```

3. Output SQL and Params:
```sql
SELECT *
FROM users
WHERE
status = 'inactive'
```

```clj
[]
```


### Control flow 6. elseif and else 

1. Input Form:
```clj
(render "demo-if-elseif-else.sql" {:active false, :pending true})
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

3. Output SQL and Params:
```sql
SELECT *
FROM users
WHERE
  status = 'pending'
```

```clj
[]
```


### Control flow 7. inline elseif and else 

1. Input Form:
```clj
(render "demo-if-inline-elseif-else.sql" {:active false, :pending true})
```

2. Input SQL:
```sql
SELECT *
FROM users
WHERE
/*%if active */
  active = true
/*%elseif pending => status = 'pending' */
/*%else => status = 'inactive' */
/*%end */
```

3. Output SQL and Params:
```sql
SELECT *
FROM users
WHERE
status = 'pending'
```

```clj
[]
```


### Control flow 8. for 

1. Input Form:
```clj
(render "demo-for.sql" {:id 42, :items [{:name "display_name", :value "Alice"} {:name "status", :value "active"}]})
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

3. Output SQL and Params:
```sql
UPDATE users
SET
  display_name = ?,
  status = ?
WHERE id = ?
```

```clj
["Alice" "active" 42]
```


### Declarations 

1. Input Form:
```clj
(render "demo-declarations.sql" {:id 42})
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

3. Output SQL and Params:
```sql
/* This is a normal comment */
SELECT * FROM users WHERE id = ?
```

```clj
[42]
```


### Declarations 2. multiple templates 1 

1. Input Form:
```clj
(render (get (load-queries "demo-multi-queries.sql") "core.find-user-by-id") {:id 42})
```

2. Input SQL:
```sql
/*:name find-user-by-id */
SELECT * FROM users WHERE id = /*$id*/1
```

3. Output SQL and Params:
```sql
SELECT * FROM users WHERE id = ?
```

```clj
[42]
```


### Declarations 3. multiple templates 2 

1. Input Form:
```clj
(render (get (load-queries "demo-multi-queries.sql") "core.find-user-by-email") {:email "user@example.com"})
```

2. Input SQL:
```sql
/*:name find-user-by-email */
SELECT * FROM users WHERE email = /*$email*/'user@example.com'
```

3. Output SQL and Params:
```sql
SELECT * FROM users WHERE email = ?
```

```clj
["user@example.com"]
```


### Load query from custom path 

1. Input Form:
```clj
(render "demo-custom-path.sql" {:base-path "demo"} {:id 42})
```

2. Input SQL:
```sql
SELECT * FROM users WHERE id = /*$id*/1
```

3. Output SQL and Params:
```sql
SELECT * FROM users WHERE id = ?
```

```clj
[42]
```


### Render query by passing SQL template directly 

1. Input Form:
```clj
(render {:sql-template "SELECT * FROM users WHERE id = /*$id*/1"} {:id 42})
```

2. Input SQL:
```sql
SELECT * FROM users WHERE id = /*$id*/1
```

3. Output SQL and Params:
```sql
SELECT * FROM users WHERE id = ?
```

```clj
[42]
```


### Errors 1. missing bind parameter 

1. Input Form:
```clj
(render "demo-variables-bind.sql" {:ic 42})
```

2. Input SQL:
```sql
SELECT * FROM users WHERE id = /*$id*/1
```

3. Output Error:
```clj
{:message "Missing query parameter.",
 :data
 {:query-name "core.demo-variables-bind",
  :base-path "sql",
  :resource-path "sql/demo-variables-bind.sql",
  :project-relative-path "scripts/sql/demo-variables-bind.sql",
  :source-line 1,
  :parameter :id,
  :sigil "$",
  :collection? false}}
```

