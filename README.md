# Bisql

<p align="center">
  <img src="docs/img/bicycle.svg" alt="Bisql bicycle logo" width="96" height="96" />
</p>

Bisql (pronounced `báisikl` 🚲) is a SQL-first, SQL-only, SQL-obsessed data access toolkit for Clojure.

Write real SQL. Keep it executable. Generate the boring parts.

- Query templates remain valid, executable SQL.
- Typical index-friendly queries are generated automatically, as comprehensively as possible.

No query builder  
No data mapper  
No hidden SQL  
No boilerplate SQL  

[![Clojars Project](https://img.shields.io/clojars/v/io.github.hatappo/bisql.svg)](https://clojars.org/io.github.hatappo/bisql)

> [!NOTE]
> This project is still early and the API may change.
> Support for databases beyond PostgreSQL and Malli integration are both planned.

## Installation

Add it to `deps.edn`:

```clojure
{:deps {io.github.hatappo/bisql {:mvn/version "0.1.0"}}}
```

If you want a shorter CLI entrypoint, you can also add an alias:

```clojure
{:aliases
 {:bisql {:main-opts ["-m" "bisql.cli"]}}}
```

Then commands such as these become available:

```sh
clojure -M:bisql gen-config
clojure -M:bisql gen-crud
clojure -M:bisql gen-declarations
```

If you prefer `bb`, you can also add tasks like:

```clojure
{:tasks
 {:gen-config (clojure "-M:bisql" "gen-config")
  :gen-crud   (clojure "-M:bisql" "gen-crud")
  :gen-declarations (clojure "-M:bisql" "gen-declarations")}}
```

When passing CLI flags through `bb`, use `--` as a separator. For example:

```sh
bb gen-declarations -- --include-sql-template --suppress-unused-public-var
```

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

### 2. Write One Real SQL Query

Place a SQL template under a classpath `sql/...` directory.

For example:

- `src/sql/postgresql/public/users/find-active.sql`
- `src/sql.clj`

```sql
SELECT *
FROM users
WHERE status = /*$status*/'active'
ORDER BY id
LIMIT /*$limit*/100
```

This template is still valid SQL: you can run it directly with the embedded literals, and bisql replaces those literals with bind parameters at runtime.

```clj
(ns sql
  (:require [bisql.core :as bisql]))

(bisql/defquery)
```

Then use the generated query function:

```clj
(ns app.user-service
  (:require [next.jdbc :as jdbc]
            [sql.postgresql.public.users.core :as users]))

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

`bisql.core/defquery` uses the current namespace only to find SQL files.
Each discovered SQL file defines executable query functions in the namespace derived from its file path, so the same SQL file always maps to the same namespace.
By default, query execution uses the `:next-jdbc` adapter.

### 3. Generate Typical Index-Friendly Queries

Continuing from the previous example, you can generate a config template (`bisql.edn`) and modify it if needed:

```sh
clojure -M -m bisql.cli gen-config
```

Then generate the CRUD SQL:

```sh
clojure -M -m bisql.cli gen-crud
```

Depending on the tables present in the target database, this writes files such as:

- `src/sql/postgresql/public/users/crud.sql`
- `src/sql/postgresql/public/orders/crud.sql`

Generated CRUD SQL includes templates such as `insert`, `insert-many`, `get-by-*`,
`upsert-by-*`, `count`, `count-by-*`, `update-by-*`, `delete-by-*`, `list`, and `list-by-*`.

These generated queries are meant to cover the typical index-friendly SQL patterns
you would usually write by hand. In practice, that often means you do not need to
write much custom SQL at all. When you do need a custom query, the generated SQL
templates are also a convenient base to copy and adapt.

For the sample tables above, this typically includes:

- `users.crud/insert`
- `users.crud/insert-many`
- `users.crud/upsert-by-id`
- `users.crud/upsert-by-email`
- `users.crud/get-by-id`
- `users.crud/get-by-email`
- `users.crud/count`
- `users.crud/count-by-status`
- `users.crud/update-by-id`
- `users.crud/update-by-email`
- `users.crud/delete-by-id`
- `users.crud/delete-by-email`
- `orders.crud/insert`
- `orders.crud/insert-many`
- `orders.crud/upsert-by-id`
- `orders.crud/count`
- `orders.crud/count-by-state`
- `orders.crud/count-by-state-and-created-at`
- `orders.crud/list`
- `orders.crud/get-by-id`
- `orders.crud/update-by-id`
- `orders.crud/delete-by-id`
- `orders.crud/list-by-state`
- `orders.crud/list-by-state-and-created-at`

Then execute one of the generated functions:

```clj
(ns app.order-service
  (:require [next.jdbc :as jdbc]
            [sql]
            [sql.postgresql.public.orders.crud :as orders]))

(def datasource
  (jdbc/get-datasource {:dbtype "postgresql"
                        :host "localhost"
                        :port 5432
                        :dbname "bisql_dev"
                        :user "bisql"
                        :password "bisql"}))

(orders/list datasource {:limit 20
                         :offset 0})
```

SQL templates are resolved from the classpath under the logical `sql` base path.
That means they can live under `src/sql/`, `resources/sql/`, or any other classpath root
that exposes `sql/...`.

The same options can also be passed through environment variables such as
`BISQL_HOST`, `BISQL_PORT`, `BISQL_DBNAME`, `BISQL_USER`, `BISQL_PASSWORD`,
`BISQL_SCHEMA`, `BISQL_BASE_DIR`, `BISQL_DBTYPE`, and `BISQL_CONFIG`.

The precedence order is CLI options > environment variables > config file > defaults.

`gen-declarations` is an optional helper for projects that prefer explicit namespace
files. It generates navigation-oriented `declare` forms with docstrings derived
from the SQL templates, so IDEs and the REPL can jump to the intended namespace
and query source without letting a shallow `(defquery)` populate undeclared
namespaces. By default those docstrings include the project-relative SQL file path
and line number; pass `--include-sql-template` if you also want the SQL template
body included.

For more detail on the 2-way SQL syntax and rendering behavior, see:

- [docs/rendering-examples.md](docs/rendering-examples.md)

## Development

For local setup, tasks, and dev workflow, see:

- [docs/dev-local-development.md](docs/dev-local-development.md)

## Roadmap

- Add Malli integration.
- Support databases beyond PostgreSQL.
- Compile analyzed SQL templates into reusable renderer functions for lower runtime overhead.
  - Simplify emitted renderer forms further, especially around branch and loop body handling.
  - Reduce helper calls in emitted code where fragment normalization is still delegated.
  - Restrict `bisql/DEFAULT` to valid SQL value contexts if context-aware rendering becomes necessary.
  - Detect dangerous `nil` comparisons consistently in `WHERE` / `HAVING` clauses instead of letting expressions such as `= NULL`, `LIKE NULL`, or `IN (NULL)` silently behave unexpectedly. This likely needs stricter SQL context parsing, because `= NULL` is dangerous in `WHERE` / `HAVING` but can still be valid assignment syntax in `SET`.
