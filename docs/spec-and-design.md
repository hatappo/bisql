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

SQL files are placed under the logical `sql/` base path on the classpath.
In practice, that usually means `src/sql/` or `resources/sql/`.

Recommended layout:

```text
sql/<database>/<schema>/<table>/<function-name>.sql
```

Example:

```text
src/sql/postgresql/public/users/get-by-id.sql
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
- Parameter names may use multi-level dot-paths such as `user.profile.status`
- Each path segment is resolved in this order: `keyword`, `string`, `symbol`

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

### `DEFAULT`

When a scalar bind variable receives `bisql/DEFAULT`, it is rendered as SQL `DEFAULT` instead of `?`.

```clojure
{:status bisql/DEFAULT}
```

```sql
INSERT INTO users (status) VALUES (/*$status*/'active')
```

→

```sql
INSERT INTO users (status) VALUES (DEFAULT)
```

### `ALL`

When a scalar bind variable receives `bisql/ALL`, it is rendered as SQL `ALL`
instead of `?`.

```clojure
{:limit bisql/ALL}
```

Example:

```sql
SELECT * FROM users LIMIT ALL
```

Initial implementation notes:

- Supported only for scalar `$` bindings
- Not allowed in collection bindings such as `IN /*$ids*/(...)`

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
/*%else */
  AND status = 'inactive'
/*%end */
```

### Supported Form

- `x`
- `if / elseif / else / end`
- Optional inline elseif fragment: `/*%elseif condition => <fragment> */`
- Optional inline else fragment: `/*%else => <fragment> */`

### Evaluation Rules

- Truthiness follows Clojure semantics
- Only `nil` and `false` are treated as false
- Missing parameters are treated as `nil`
- Empty strings and empty collections are treated as true
- `elseif` uses the first truthy branch after the initial `if`
- If `elseif` uses `=> <fragment>`, that inline fragment becomes the elseif body
- If `else` uses `=> <fragment>`, that inline fragment becomes the else body
- If an inline `elseif => <fragment>` or `else => <fragment>` also has block body content before the next directive, the template is rejected as invalid
- `elseif` is not allowed after `else`
- If a falsy conditional block is immediately followed by `AND` or `OR`, that trailing operator is also removed
- If a falsy conditional block is not followed by `AND` or `OR` and is immediately preceded by `WHERE` or `HAVING`, that clause keyword is also removed

### Initial Implementation Constraint

- Only a single variable name is supported in `if` and `elseif`
- Expression syntax is intentionally not supported in the initial implementation
- `else` does not take an expression

### `/*%for*/`

```sql
UPDATE users
SET
/*%for item in items separating , */
  /*!item.name*/ = /*$item.value*/'sample'
/*%end */
WHERE id = /*$id*/1
```

### Rules

- Syntax: `/*%for item in items */ ... /*%end */`
- Optional separator syntax: `/*%for item in items separating , */ ... /*%end */`
- `item` is a loop-local variable name
- Dot-path references such as `item.name`, `item.value`, or `user.profile.name` are supported
- Dot-path lookup checks keys in this order: `keyword`, `string`, `symbol`
- Empty collections are not treated as errors
- If an empty `for` block is immediately followed by `AND` or `OR`, that trailing operator should also be removed
- If an empty `for` block is not followed by `AND` or `OR` and is immediately preceded by `WHERE` or `HAVING`, that clause keyword should also be removed
- With `separating`, the separator is emitted before the second and subsequent iterations
- Trailing `,`, `AND`, or `OR` are not trimmed from the repeated body anymore; use `separating` instead when separators are needed

### Initial Implementation Constraint

- Nested `for` blocks are not supported in the initial implementation

---

## 4.4 Typical Use Cases

`if` and `for` are intended for local SQL assembly, not for turning SQL files into a general-purpose programming language.

### Typical Use Cases for `if`

#### Conditional `WHERE` / `HAVING`

```sql
SELECT *
FROM users
WHERE
/*%if active */
  active = true
/*%end */
```

#### Conditional `SET`

```sql
UPDATE users
SET
/*%if display-name */
  display_name = /*$display-name*/'Alice'
/*%else */
  display_name = display_name
/*%end */
WHERE id = /*$id*/1
```

#### Inline `else` Fragment

```sql
SELECT *
FROM users
WHERE
/*%if active */
  active = true
/*%else => status = 'inactive' */
/*%end */
```

#### `elseif` Branch

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

#### Inline `elseif` Fragment

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

#### Conditional `ORDER BY` / `LIMIT`

```sql
SELECT *
FROM users
/*%if sort-by-created-at */
ORDER BY created_at DESC
/*%end */
/*%if limit */
LIMIT /*$limit*/100
/*%end */
```

### Typical Use Cases for `for`

#### Repeated `WHERE` Conditions

```sql
SELECT *
FROM users
WHERE
/*%for item in filters separating AND */
  /*!item.column*/ = /*$item.value*/'sample'
/*%end */
```

#### Repeated `SET` Items

```sql
UPDATE users
SET
/*%for item in items separating , */
  /*!item.name*/ = /*$item.value*/'sample'
/*%end */
WHERE id = /*$id*/1
```

#### Repeated `INSERT` Columns and Values

```sql
INSERT INTO users (
/*%for column in columns separating , */
  /*!column.name*/
/*%end */
) VALUES (
/*%for column in columns separating , */
  /*$column.value*/'sample'
/*%end */
)
```

#### Repeated Multi-row `VALUES`

```sql
INSERT INTO users (email, status)
VALUES
/*%for row in rows separating , */
  (/*$row.email*/'user@example.com', /*$row.status*/'active')
/*%end */
```

---

## 4.5 LIKE Handling

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

## 4.6 Literal Variables — `/*^name*/` (Optional)

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

## 4.7 Embedded Variables — `/*!name*/` (Advanced)

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

Declaration comments provide template metadata.

## 5.1 Syntax

```sql
/*:<name>
<edn>
*/
```

### Rules

- Any declaration name is allowed
- Declaration blocks are recognized only at the beginning of a template block
- Duplicate declaration blocks are errors
- Declaration bodies are parsed as EDN by default
- `/*:doc */` falls back to a trimmed plain string when EDN parsing fails
- In a file with multiple templates, each template block starts with its own declarations
- Parsed declarations are returned under `:meta`

Example:

```sql
/*:doc
Find orders by customer ID.
*/

/*:tags
[:orders :list]
*/
```

→

```clojure
{:meta {:doc "Find orders by customer ID."
        :tags [:orders :list]}}
```

---

## 5.2 `/*:name */`

Defines a template-local query name.

```sql
/*:name find-user-by-email */
SELECT * FROM users WHERE email = /*$email*/'user@example.com'
```

### Naming Rules

- Files with a single template may omit `/*:name */`
- Files with multiple templates require `/*:name */` for each template
- `load-query` only supports single-template files
- `load-queries` returns templates keyed by `query-name`
- `:name` is used to resolve `query-name` and is also kept inside returned `:meta`
- Dots in either the SQL filename (without `.sql`) or `/*:name */` are treated as namespace separators
- The last segment becomes the function name
- Earlier segments become a namespace suffix appended under the SQL file's parent directory
- If no namespace suffix is provided, `core` is used by default

---

# 6. Public API Shape

Initial implementation should expose a small public API.

## 6.1 Loading

```clojure
(load-query "postgresql/public/users/get-by-id.sql")
```

Loads a SQL file from `sql/...` on the classpath and returns its parsed representation.

## 6.2 Rendering

```clojure
(render-query query {:id 1})
```

Renders template SQL into:

```clojure
{:sql "SELECT * FROM users WHERE id = ?"
 :params [1]}
```

The initial compiler foundation also exposes:

```clojure
(def parsed-template
  (parse-template "SELECT * FROM users WHERE id = /*$id*/1"))
(renderer-plan parsed-template)
(emit-renderer-form parsed-template)
(compile-renderer parsed-template)
(evaluate-renderer parsed-template {:id 1})
```

`parse-template` converts a declaration-free SQL template string into an
parsed template. `renderer-plan` converts that parsed template into an
execution-oriented plan. `emit-renderer-form` emits a reusable renderer
function form from that plan. `compile-renderer` compiles the parsed template
into a reusable renderer function at runtime. `evaluate-renderer` evaluates the
parsed template directly and returns the same rendered SQL shape as the
internal renderer step:

```clojure
{:sql "SELECT * FROM users WHERE id = ?"
 :bind-params [1]}
```

The parsed-template layer is the parser output. It annotates nodes with
statement kind (`:select`, `:insert`, `:update`, `:delete`) and clause-level
context such as `:where`, `:having`, `:set`, `:values`, `:limit`, and
`:offset`. The renderer-plan layer is the execution-oriented intermediate form
used by later code generation and future interpreter work.

`renderer-plan` currently has this stable shape:

```clojure
{:op :renderer-plan
 :statement-kind :select
 :steps [...]}
```

The top-level `:steps` vector contains execution-oriented step maps. The
current step kinds are:

- `:append-text`
- `:append-variable`
- `:branch`
- `:for-each`

`:append-text` carries `:sql`, `:context`, and `:statement-kind`.
`:append-variable` carries `:sigil`, `:parameter-name`, `:collection?`,
`:context`, and `:statement-kind`.

`:branch` carries `:branches`, where each branch has:

```clojure
{:expr "active" ;; or nil for else
 :steps [...]}
```

`:for-each` carries the loop contract:

```clojure
{:op :for-each
 :item-name "item"
 :collection-name "items"
 :separator ","
 :context :set
 :statement-kind :update
 :steps [...]}
```

The exact emitted Clojure form is an implementation detail, but the
`parsed-template -> renderer-plan -> renderer-form` layering is now the
intended compiler boundary.

The current primary path is `emit-renderer-form`: `defrender` and `defquery`
embed the emitted renderer form at macro expansion time, while
`compile-renderer` remains as a thin runtime convenience wrapper around `eval`.

## 6.3 Function Definition

```clojure
(defrender)
(defrender "admin")
(defrender "/sql/postgresql/public/users/get-by-id.sql")
(defrender "/sql/postgresql/public/users")
```

`defrender` defines one rendering function per query found in a SQL file.
With no arguments, it resolves the current namespace to a classpath directory
and loads every `.sql` file under that directory recursively. When a relative
path is passed, it is resolved under that namespace-derived directory. When a
path starts with `/`, it is resolved from the classpath root. If a directory is
passed instead of a file, it loads every `.sql` file under that directory
recursively and defines all queries found there. The current namespace is only
used to discover files. Each discovered SQL file defines its functions into the
namespace derived from that SQL file path.

`defrender` resolves `query-name` with this priority:

1. `/*:name */`
2. the SQL file name itself

If `/*:name */` does not provide a namespace suffix, the file name can still
provide one. The generated var name is always the last segment of the resolved
`query-name`, while the preceding segments become a namespace suffix under the
SQL file's parent path.

Examples:

- `sql/postgresql/public/users/get-by-id.sql`
  -> `sql.postgresql.public.users.core/get-by-id`
- `sql/postgresql/public/users/hoge.list-order-by-created-at.sql`
  -> `sql.postgresql.public.users.hoge/list-order-by-created-at`
- `sql/postgresql/public/users/crud.sql` with `/*:name crud.get-by-id */`
  -> `sql.postgresql.public.users.crud/get-by-id`

When loading a directory, files are processed recursively in sorted path order.
Var name collisions are errors.

`defquery` is the higher-level facade that defines executable query functions.
By default it delegates to the `:next-jdbc` adapter implementation.

## 6.4 Execution Adapters

Execution lives behind adapter namespaces.

Example:

```clojure
(ns sql.postgresql.public.users.core
  (:require [bisql.core :as bisql]
            [bisql.adapter.next-jdbc :as bisql.jdbc]))

(bisql/defquery "/sql/postgresql/public/users/get-by-id.sql")

(bisql.jdbc/exec! datasource get-by-id {:id 42})
```

`bisql.adapter.next-jdbc/exec!` chooses `next.jdbc/execute-one!` or `next.jdbc/execute!`
based on the query function metadata's `:cardinality` value. When `:cardinality` is not
specified, it defaults to `:many`.

This keeps `bisql.core` focused on loading, analyzing, rendering, and function generation.

## 6.5 CRUD Generation

```clojure
(generate-crud datasource {:schema "public"})
```

Generates query definitions, SQL template files, and per-table query namespace files
from PostgreSQL schema metadata.

Example:

```clojure
(-> (generate-crud datasource {:schema "public"})
    (write-crud-files! {:output-root "src/sql"}))

(-> (generate-crud datasource {:schema "public"})
    (write-declaration-files! {:output-root "src/sql"}))
```

Each generated namespace file declares the generated query vars with docstrings
derived from the SQL templates:

```clojure
(ns sql.postgresql.public.users.crud

(declare ^{:arglists '([datasource] [datasource template-params])
           :doc "..."}
 get-by-id)
```

The same generation flow can be exposed as a CLI:

```sh
clojure -M -m bisql.cli gen-config
clojure -M -m bisql.cli gen-crud --config bisql.edn
clojure -M -m bisql.cli gen-declarations --config bisql.edn
```

The config file is an EDN map with `:db` and `:generate` sections. Generated templates show default values as commented examples. Commands still work without a config file because the precedence order is CLI options > environment variables > config file > defaults.

`gen-declarations` is an optional helper. It is useful for projects that prefer explicit
namespace files and want IDE/REPL navigation stubs with docstrings, without
letting a shallow `(defquery)` define functions into namespaces that were not
declared in source ahead of time. By default those docstrings include the
project-relative SQL file path and line number; `--include-sql-template` can be
used when the SQL template body should also be embedded.

**Reasoning:**
- Keeps the API surface small
- Separates loading, rendering, execution adapters, and generation concerns
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

## 7.3 Upsert

Generated for:

- primary key
- unique constraints

Uses PostgreSQL `INSERT ... ON CONFLICT ON CONSTRAINT ... DO UPDATE RETURNING *`.

```clojure
(upsert-by-id! db row)
```

Composite key example:

```clojure
(upsert-by-user-id-and-device-identifier! db row)
```

Generated upsert queries expect insertion values under `:inserting`. They may also
accept `:non-updating-cols` to preserve selected columns from the conflicting row:

```clojure
(users.crud/upsert-by-id
  datasource
  {:inserting {:email "alice@example.com"
               :display-name "Alice"
               :status "active"
               :created-at #inst "2026-04-12T00:00:00Z"}
   :non-updating-cols {:created-at true}})
```

This renders `INSERT INTO ... AS t` and uses `t.<column>` instead of
`EXCLUDED.<column>` for columns whose `:non-updating-cols.<column>` value is truthy,
so the existing value is kept unchanged for those columns.

---

## 7.4 Update

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

## 7.5 Delete

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

## 7.8 LIMIT and OFFSET (Required)

All `list-by-*` functions require a `limit` and an `offset`.

```clojure
(list-by-customer-id db {:customer-id 10
                         :limit 100
                         :offset 0})
```

### Rules

- Must be a positive integer
- `offset` must be zero or a positive integer
- No "unbounded" option in initial implementation

**Reasoning:**
- Prevent accidental full scans
- Enforce awareness of result size
- Make pagination shape consistent across generated queries
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

- range-based queries
- cursor-based pagination
- richer metadata annotations
- schema validation integration (Malli)
