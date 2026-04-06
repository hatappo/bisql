# SQL Template & Generated CRUD Specification

A SQL-first, safe-by-default data access approach for Clojure.

---

# 1. Overview

This library provides:

- SQL-first query definitions (`.sql` files are the source of truth)
- Minimal 2-way SQL syntax (comment-based templating)
- Safe parameter binding (prepared statements by default)
- Declarative metadata inside SQL files
- Automatically generated CRUD functions based on database schema
- Initial implementation targets PostgreSQL only
- `next.jdbc` as the execution backend

---

# 2. Design Principles

## 2.1 SQL is the Source of Truth

All queries are defined in SQL files.  
No DSL or query builder replaces SQL.

**Reasoning:**
- SQL is already expressive and widely understood
- Keeps queries transparent and reviewable
- Avoids abstraction leakage

---

## 2.2 Minimal Syntax

Only a small set of comment-based constructs are supported.

**Reasoning:**
- Avoids full templating languages
- Keeps SQL readable
- Reduces cognitive overhead

---

## 2.3 Safe by Default

- Bind variables are always used by default
- Dangerous operations require explicit constructs

**Reasoning:**
- Prevent SQL injection
- Encourage predictable execution plans
- Avoid accidental full scans

---

## 2.4 Index-Aware Access

Generated queries follow database index structures.

**Reasoning:**
- Encourages efficient queries
- Aligns application code with DB design
- Prevents inefficient access patterns

---

## 2.5 Thin Integration Layer

This library is not intended to replace the JDBC access layer.

Initial implementation assumes `next.jdbc` as the execution backend.

**Reasoning:**
- Reuses the de facto modern JDBC layer in the Clojure ecosystem
- Keeps this library focused on SQL loading, rendering, and CRUD generation
- Avoids duplicating lower-level database concerns

---

# 3. SQL File Layout

## 3.1 Recommended Path Convention

SQL files are placed under `resources/sql/`.

Recommended layout:

```text
resources/sql/<database>/<schema>/<table>/<function-name>.sql
```

Example:

```text
resources/sql/postgresql/public/users/get-by-id.sql
```

### Rules

- Initial implementation uses `postgresql` as the default database segment
- `public` is the default schema
- Subdirectories correspond to namespace-like grouping
- File name determines the function name

**Reasoning:**
- Keeps SQL organization aligned with database structure
- Makes generated and handwritten queries easy to locate
- Avoids introducing a separate naming registry

---

# 4. SQL Template Syntax

## 4.1 Bind Variables — `/*$name*/`

### Description

Prepared statement parameter.

- Replaced with `?`
- Value is passed separately

### Example

```sql
SELECT * FROM users WHERE id = /*$id*/1
```

→

```sql
SELECT * FROM users WHERE id = ?
```

binds:

```clojure
[123]
```

---

## 4.2 Collection Binding (`IN`)

```sql
WHERE id IN /*$ids*/(1,2,3)
```

```clojure
{:ids [10 20 30]}
```

→

```sql
WHERE id IN (?, ?, ?)
```

binds:

```clojure
[10 20 30]
```

### Constraints

- Only valid in `IN (...)`
- Empty collections are not allowed

**Reasoning:**
- Avoid generating invalid SQL
- Avoid ambiguous semantics

---

## 4.3 Conditional Blocks — `/*%if*/`

```sql
WHERE 1 = 1
/*%if name */
  AND name = /*$name*/'foo'
/*%end */
```

### Supported Form

- `x`

### Evaluation Rules

- Truthiness follows Clojure semantics
- Only `nil` and `false` are treated as false
- Missing parameters are treated as `nil`
- Empty strings and empty collections are treated as true

### Initial Implementation Constraint

- Only a single variable name is supported
- Expression syntax is intentionally not supported in the initial implementation

---

## 4.4 LIKE Handling

LIKE is handled via typed values.

```sql
WHERE name LIKE /*$name*/'foo%'
```

```clojure
{:name (sql/like-prefix "smith")}
```

→

```sql
WHERE name LIKE ?
```

binds:

```clojure
["smith%"]
```

---

## 4.5 Literal Variables — `/*^name*/` (Optional)

Directly embeds a SQL literal.

```sql
WHERE type = /*^type*/'A'
```

→

```sql
WHERE type = 'BOOK'
```

### Initial Implementation Rules

- Supported database: PostgreSQL only
- `String` values are embedded as single-quoted SQL string literals
- `String` values must not contain `'`
- Numeric values are embedded as literals without quotes
- Unsupported value types result in an error

**Reasoning:**
- Required for rare SQL cases
- Not default due to safety concerns
- PostgreSQL-specific behavior is acceptable in the initial implementation
- Keeping the accepted types small avoids ambiguous rendering rules

---

## 4.6 Embedded Variables — `/*!name*/` (Advanced)

Injects raw SQL fragments as an explicit escape hatch.

```sql
ORDER BY /*!order-by*/id DESC
```

### Policy

- Safety is intentionally delegated to the developer using it
- `/*!name*/` is not safe by default
- Prefer `/*$name*/` or `/*^name*/` unless raw SQL injection is truly required

**Reasoning:**
- Some SQL constructs are difficult to express otherwise
- Unsafe behavior should remain explicit and opt-in

---

# 5. Declaration Comments

Declaration comments provide metadata and documentation.

## 5.1 Syntax

```sql
/*:doc
...
*/

/*:meta
{...}
*/
```

### Rules

- Only recognized at the top of the file
- Must appear before SQL content
- Later occurrences are ignored

---

## 5.2 `/*:doc */`

Defines function docstring.

```sql
/*:doc
Find orders by customer ID.
*/
```

---

## 5.3 `/*:meta */`

Defines metadata (EDN).

```sql
/*:meta
{:tags [:orders :list]
 :since "0.1.0"}
*/
```

---

## 5.4 Naming

- Function name is derived from the SQL file name
- No `/*:name */` directive is used

**Reasoning:**
- Avoid duplication of source of truth
- Prevent inconsistencies
- Keep file structure simple

---

# 6. Public API Shape

Initial implementation should expose a small public API.

## 6.1 Loading

```clojure
(load-query "postgresql/public/users/get-by-id.sql")
```

Loads a SQL file from `resources/sql/...` and returns its parsed representation.

## 6.2 Rendering

```clojure
(render-query query {:id 1})
```

Renders template SQL into:

```clojure
{:sql "SELECT * FROM users WHERE id = ?"
 :params [1]}
```

## 6.3 Execution

```clojure
(execute-one! datasource query params)
(execute! datasource query params)
```

These functions delegate execution to `next.jdbc`.

## 6.4 CRUD Generation

```clojure
(generate-crud datasource {:schema "public"})
```

Generates query definitions and callable functions from PostgreSQL schema metadata.

**Reasoning:**
- Keeps the API surface small
- Separates loading, rendering, execution, and generation concerns
- Makes it easier to test each layer independently

---

# 7. Generated CRUD Functions

## 7.1 Overview

CRUD functions are generated from database schema:

- primary keys
- unique constraints
- indexes

### Scope

- Supported database: PostgreSQL only
- Default schema: `public`
- Schema may be specified explicitly
- Partial indexes are excluded in the initial implementation
- Expression indexes are excluded in the initial implementation

---

## 7.2 Insert

```clojure
(insert! db row)
```

---

## 7.3 Update

Generated only for:

- primary key
- unique constraints (full match)

```clojure
(update-by-id! db {:id ...
                   :set {...}})
```

Composite key example:

```clojure
(update-by-account-id-and-user-id! db {:account-id ...
                                       :user-id ...
                                       :set {...}})
```

---

## 7.4 Delete

Generated only for:

- primary key
- unique constraints (full match)

```clojure
(delete-by-id! db {:id ...})
```

Composite key example:

```clojure
(delete-by-account-id-and-user-id! db {:account-id ...
                                       :user-id ...})
```

---

## 7.5 Select

### 7.5.1 `get-by-*`

Generated for:

- primary key (full match)
- unique constraint (full match)

```clojure
(get-by-id db {:id ...})
```

Composite key example:

```clojure
(get-by-account-id-and-user-id db {:account-id ...
                                   :user-id ...})
```

---

### 7.5.2 `list-by-*`

Generated for:

- all index left-prefix patterns

---

## 7.6 Index Prefix Rule

For index `(a, b, c)`:

Generated prefixes:

- `(a)`
- `(a, b)`
- `(a, b, c)`

Not generated:

- `(b)`
- `(b, c)`
- `(a, c)`

**Reasoning:**
- Only left-prefix uses indexes efficiently
- Avoid misleading or inefficient queries

---

## 7.7 ORDER BY (Natural Index Order)

Remaining index columns are used as `ORDER BY`.

Example index:

```
(customer_id, created_at, id)
```

### prefix: `customer_id`

```sql
WHERE customer_id = ?
ORDER BY created_at, id
```

### prefix: `customer_id, created_at`

```sql
WHERE customer_id = ?
  AND created_at = ?
ORDER BY id
```

### full match

```sql
WHERE customer_id = ?
  AND created_at = ?
  AND id = ?
```

(no ORDER BY)

---

### Constraints

- All columns are ordered ascending
- DESC handling is not supported in initial implementation

**Reasoning:**
- Keeps implementation simple
- Covers majority of real-world use cases

---

## 7.8 LIMIT (Required)

All `list-by-*` functions require a `limit`.

```clojure
(list-by-customer-id db {:customer-id 10
                         :limit 100})
```

### Rules

- Must be a positive integer
- No "unbounded" option in initial implementation

**Reasoning:**
- Prevent accidental full scans
- Enforce awareness of result size
- Encourage efficient access patterns

---

## 7.9 Naming

Generated names:

```clojure
list-by-customer-id-and-created-at
```

Rules:

- Based on column names
- Converted to kebab-case
- Connected with `and`
- Composite primary keys and composite unique constraints follow the same rule
- Constraint names are not used for generated function names

**Reasoning:**
- Explicit and readable
- No ambiguity
- Easy to map to DB schema

---

## 7.10 Not Generated

The following are intentionally excluded:

- count functions (planned for future)
- range queries (`>=`, `<=`)
- OR conditions
- JOIN queries
- aggregation queries
- dynamic ordering
- partial indexes
- expression indexes

**Reasoning:**
- Avoid API explosion
- Keep generated functions predictable
- Encourage explicit SQL for complex cases

---

# 8. Testing Strategy

Testing should be separated into layers:

- parser unit tests
- SQL rendering tests
- PostgreSQL integration tests

**Reasoning:**
- Parser behavior should be validated independently from JDBC
- Rendering behavior should be validated independently from database state
- Integration tests should validate actual PostgreSQL execution and schema introspection

---

# 9. Summary

This system provides:

- SQL-first design
- Minimal templating
- Safe parameter handling
- Index-aware query generation
- Predictable and constrained CRUD APIs

---

# 10. Future Extensions

- count functions
- range-based queries
- cursor-based pagination
- richer metadata annotations
- schema validation integration (Malli)
