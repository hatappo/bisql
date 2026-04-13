# Rendering Examples

## 1. Variables

### 1-1: bind values 

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
{:params [42]}
```

`/*$ */` comments become bind variables. If an adjacent sample value is present, as in `1` here, it is removed from the rendered SQL.

### 1-2: literal values 

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
{:params []}
```

`^` is rendered as a SQL literal. Strings are quoted with `'`.

### 1-3: raw values 

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
{:params []}
```

`!` is inserted into SQL as-is. It is useful for cases like `ORDER BY`, but it must not be fed directly from user input.

### 1-4: default bind value 

1. Input Form:
```clj
(render "demo-variables-default.sql" {:email "alice@example.com", :status bisql/DEFAULT})
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
{:params ["alice@example.com"]}
```

`bisql/DEFAULT` is rendered as the SQL keyword `DEFAULT` instead of a bind parameter. This is useful in `VALUES` clauses.

### 1-5: ALL bind value 

1. Input Form:
```clj
(render "demo-variables-all.sql" {:limit bisql/ALL})
```

2. Input SQL:
```sql
SELECT *
FROM users
LIMIT /*$limit*/10
```

3. Output SQL and Params:
```sql
SELECT *
FROM users
LIMIT ALL
```

```clj
{:params []}
```

`bisql/ALL` is rendered as the SQL keyword `ALL` instead of a bind parameter. This is useful in clauses such as `LIMIT ALL`.

## 2. Control flow

### 2-1: if 

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
{:params [42]}
```


### 2-2: remove WHERE for falsy if 

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
{:params []}
```

When the condition is false, the preceding `WHERE` or `HAVING` keyword is removed automatically.

### 2-3: remove following AND for falsy if 

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
{:params ["active"]}
```

When the condition is false, a following `AND` is removed automatically. In this case, the preceding `WHERE` or `HAVING` remains in place.

### 2-4: elseif and else 

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
{:params []}
```


### 2-5: inline elseif and else 

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
{:params []}
```

`else` and `elseif` bodies can also be written inline inside the directive comment by using `=>`. This keeps the template closer to executable SQL.

### 2-6: for 

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
{:params ["Alice" "active" 42]}
```

`for` loops can declare a separator with `separating`. This is useful when joining an arbitrary number of fragments with `,`, `AND`, or `OR`.

## 3. Declarations

### 3-1: docstring and metadata 

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
/*:category :lookup */
/* This is a normal comment */
SELECT * FROM users WHERE id = /*$id*/1
```

3. Output SQL and Params:
```sql
/* This is a normal comment */
SELECT * FROM users WHERE id = ?
```

```clj
{:params [42],
 :meta
 {:doc "Loads a user by id.",
  :tags [:example :user],
  :category :lookup}}
```


### 3-2: multiple SQL templates in one file 

1. Input Form:
```clj
(render (get (bisql/load-queries "demo-multi-queries.sql") "core.find-user-by-id") {:id 42})
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
{:params [42], :meta {:name find-user-by-id}}
```

A single SQL file can contain multiple templates. Each template starts with a `/*:name */` directive that defines the generated function name.

## 4. Others

### 4-1: render query by passing a SQL template directly 

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
{:params [42]}
```

You can also pass a SQL template directly from Clojure code. This is useful for debugging or learning, but application queries should generally live in SQL files.

### 4-2: Errors: missing bind parameter 

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

## Notes

- The sample code assumes `(require '[bisql.query :as bisql])`.
