# Bisql

Bisql (pronunce `bάɪsɪkl` 🚲️ ) is a 2-way SQL toolkit for Clojure.

## Getting Started

### 1. Create a Minimal Table

Start with a simple table:

```sql
CREATE TABLE users (
  id BIGSERIAL PRIMARY KEY,
  email TEXT NOT NULL UNIQUE,
  status TEXT NOT NULL
);

CREATE TABLE orders (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL,
  state TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX orders_state_created_at_idx
  ON orders (state, created_at);
```

### 2. Write One Custom Query

Place a SQL template under a classpath `sql/...` directory.

For example:

- `src/sql/postgresql/public/users/find-active.sql`
- `src/sql/postgresql/public/users.clj`

```sql
SELECT *
FROM users
WHERE status = /*$status*/'active'
ORDER BY id
LIMIT /*$limit*/100
```

```clj
(ns sql.postgresql.public.users
  (:require [bisql.core :as bisql]))

(bisql/defquery)
```

Then use the generated query function:

```clj
(ns app.user-service
  (:require [next.jdbc :as jdbc]
            [sql.postgresql.public.users :as users]))

(def datasource
  (jdbc/get-datasource {:dbtype "postgresql"
                        :host "localhost"
                        :port 5432
                        :dbname "bisql_dev"
                        :user "bisql"
                        :password "bisql"}))

(users/find-active datasource {:status "active"
                               :limit 20})
```

`bisql.core/defquery` uses the current namespace only to locate SQL files.
Each discovered SQL file still defines executable query functions into the
namespace derived from that SQL file path, so the same SQL file always maps to
the same namespace. Internally it uses the default adapter `:next-jdbc`.

### 3. Generate Typical CRUD Queries

Generate a config template first:

```sh
clojure -M -m bisql.cli gen-config
```

This writes a `bisql.edn` template:

```clojure
{:db {
      ;; :dbtype "postgresql"
      ;; :host "localhost"
      ;; :port 5432
      ;; :dbname "bisql_dev"
      ;; :user "bisql"
      ;; :password "bisql"
      }
 :generate {
            ;; :schema "public"
            ;; :base-dir "src/sql"
            }}
```

Comments show the default values. Commands still work without editing this file.

Then generate CRUD SQL:

```sh
clojure -M -m bisql.cli gen-crud
```

Depending on the tables present in the target database, this writes files such as:

- `src/sql/postgresql/public/users/users-crud.sql`
- `src/sql/postgresql/public/orders/orders-crud.sql`

Generated CRUD SQL includes templates such as `insert`, `insert-many`, `get-by-*`,
`update-by-*`, `delete-by-*`, and `list-by-*`.

These generated queries are meant to cover the typical index-friendly SQL patterns
you would usually write by hand. In practice, that often means you do not need to
write much custom SQL at all. When you do need a custom query, the generated SQL
templates are also a convenient base to copy and adapt.

For the sample tables above, this typically includes:

- `users/insert`
- `users/insert-many`
- `users/get-by-id`
- `users/get-by-email`
- `users/update-by-id`
- `users/update-by-email`
- `users/delete-by-id`
- `users/delete-by-email`
- `orders/insert`
- `orders/insert-many`
- `orders/get-by-id`
- `orders/update-by-id`
- `orders/delete-by-id`
- `orders/list-by-state`
- `orders/list-by-state-and-created-at`

Create one small loader namespace:

```clj
(ns sql
  (:require [bisql.core :as bisql]))

(bisql/defquery)
```

Then execute one of the generated functions:

```clj
(ns app.order-service
  (:require [next.jdbc :as jdbc]
            [sql]
            [sql.postgresql.public.orders :as orders]))

(def datasource
  (jdbc/get-datasource {:dbtype "postgresql"
                        :host "localhost"
                        :port 5432
                        :dbname "bisql_dev"
                        :user "bisql"
                        :password "bisql"}))

(orders/list-by-state datasource {:state "paid"
                                  :limit 20
                                  :offset 0})
```

SQL templates are resolved from the classpath under the logical `sql` base path.
That means they can live under `src/sql/`, `resources/sql/`, or any other classpath root
that exposes `sql/...`.

The same options can also be passed through environment variables such as
`BISQL_HOST`, `BISQL_PORT`, `BISQL_DBNAME`, `BISQL_USER`, `BISQL_PASSWORD`,
`BISQL_SCHEMA`, `BISQL_BASE_DIR`, `BISQL_DBTYPE`, and `BISQL_CONFIG`.

The precedence order is CLI options > environment variables > config file > defaults.

## Development

### Prerequisites

```sh
brew install clojure/tools/clojure
# If you don't use Mac, see https://clojure.org/guides/install_clojure

brew install borkdude/brew/babashka
# If you don't use Mac, see https://github.com/babashka/babashka#installation
```

### Setup

#### Setup Clojure

```sh
clj -P
```

#### Setup PostgreSQL

```sh
docker compose up -d
bb test-db
```

Connection settings:

- host: `localhost`
- port: `5432`
- database: `bisql_dev`
- user: `bisql`
- password: `bisql`

Connect with `psql`:

```sh
psql -h localhost -p 5432 -U bisql -d bisql_dev
```

Quick checks:

```sql
\dn+

\d

SELECT * FROM users;
SELECT * FROM orders;
```

### Tasks

```
 $ bb tasks
The following tasks are available:

lint         Run clj-kondo on project sources
format       Fix Clojure formatting with cljfmt
format-check Check Clojure formatting with cljfmt
test         Run tests
test-db      Reset sample schema and run integration tests against local PostgreSQL
verify       Run format check, lint, and tests
fix          Run format, lint, and tests
db-reset      Reset local PostgreSQL sample schema and seed data
gen-config    Generate a bisql.edn config template
gen-crud      Generate CRUD SQL files from local PostgreSQL
gen-ns        Generate query namespace files from local PostgreSQL
demo         Run example scripts for manual verification
demo-preview Write demo output to docs/rendering-examples.md and open it
```

### Rendering Examples

- [docs/rendering-examples.md](docs/rendering-examples.md)

This document is generated by:

```sh
bb demo-preview
```

### Query Function APIs

You can define rendering functions directly from SQL files with `defrender`.

```clj
(ns sql.postgresql.public.users
  (:require [bisql.core :as bisql]))

(bisql/defrender)
(bisql/defrender "/sql/postgresql/public/users/get-by-id.sql")

(get-by-id {:id 42})
;; => {:sql "SELECT * FROM users WHERE id = ?"
;;     :params [42]
;;     :query-name "get-by-id"
;;     :meta {}}
```

`(bisql/defrender)` loads every `.sql` file under the current namespace-derived
directory recursively. Each discovered SQL file defines its rendering functions
into the namespace derived from that SQL file path.

You can also pass a relative or absolute path.

- A relative path is resolved under the current namespace-derived directory.
- A path starting with `/` is resolved from the classpath root.

```clj
(bisql/defrender "admin")
(bisql/defrender "/sql/postgresql/public/users")

(get-by-id {:id 42})
(list-by-status {:status "active" :limit 100 :offset 0})
```

The same `defrender` also works for multi-query files:

```clj
(bisql/defrender "/sql/example-multi-queries.sql")

(find-user-by-id {:id 42})
(find-user-by-email {:email "user@example.com"})
```

`defrender` resolves the function name from `query-name`.
The `query-name` priority is:

1. `/*:name */`
2. the SQL file name itself

For example, these define `sql.postgresql.public.users/get-by-id`,
`sql/find-user-by-id`, and `sql/find-user-by-email`. With no arguments,
`defrender` processes all `.sql` files under the current namespace-derived
directory recursively. When a directory is passed, it processes all `.sql`
files under that directory recursively in sorted path order.

Execution adapters live under adapter namespaces such as `bisql.adapter.next-jdbc`,
so the `core` namespace can stay focused on loading, analyzing, rendering, and function generation.

```clj
(ns sql.postgresql.public.users
  (:require [bisql.core :as bisql]
            [bisql.adapter.next-jdbc :as bisql.jdbc]))

(bisql/defquery)

(bisql.jdbc/exec! datasource get-by-id {:id 42})
```

`bisql.adapter.next-jdbc/exec!` chooses `next.jdbc/execute-one!` or
`next.jdbc/execute!` from the query function metadata's `:cardinality` value.
If `:cardinality` is not specified, it defaults to `:many`.

For scalar bind variables, `bisql/default` renders SQL `DEFAULT` and `bisql/ALL`
renders SQL `ALL`. This is useful for patterns such as `LIMIT ALL`.

## TODO

- Restrict `bisql/default` to valid SQL value contexts if context-aware rendering becomes necessary.
- Detect dangerous `nil` comparisons consistently in `WHERE` / `HAVING` clauses instead of letting expressions such as `= NULL`, `LIKE NULL`, or `IN (NULL)` silently behave unexpectedly. This likely needs stricter SQL context parsing, because `= NULL` is dangerous in `WHERE` / `HAVING` but can still be valid assignment syntax in `SET`.
- Compile analyzed SQL templates into reusable renderer functions for lower runtime overhead.
- Expand CRUD generation output and integration coverage further.
