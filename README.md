# Bisql

Bisql (pronunce `bάɪsɪkl` 🚲️ ) is a 2-way SQL toolkit for Clojure.

[![Clojars Project](https://img.shields.io/clojars/v/io.github.hatappo/bisql.svg)](https://clojars.org/io.github.hatappo/bisql)

> [!NOTE]
> This project is still early and should be treated as alpha software.
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
clojure -M:bisql gen-ns
```

If you prefer `bb`, you can also add tasks like:

```clojure
{:tasks
 {:gen-config (clojure "-M:bisql" "gen-config")
  :gen-crud   (clojure "-M:bisql" "gen-crud")
  :gen-ns     (clojure "-M:bisql" "gen-ns")}}
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

### 2. Write One Custom Query

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

```clj
(ns sql
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

Continuing from the above, generate a config template first:

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
`update-by-*`, `delete-by-*`, `list`, and `list-by-*`.

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
- `orders/list`
- `orders/get-by-id`
- `orders/update-by-id`
- `orders/delete-by-id`
- `orders/list-by-state`
- `orders/list-by-state-and-created-at`

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

`gen-ns` is an optional helper for projects that prefer explicit namespace
files over letting a shallow `(defquery)` define functions into namespaces that
were not declared ahead of time.

For more detail on the 2-way SQL syntax and rendering behavior, see:

- [docs/spec-rendering-examples.md](docs/spec-rendering-examples.md)

## Development

For local setup, tasks, and dev workflow, see:

- [docs/dev-local-development.md](docs/dev-local-development.md)

## Roadmap

- Add Malli integration.
- Support databases beyond PostgreSQL.
- Compile analyzed SQL templates into reusable renderer functions for lower runtime overhead.
  - Restrict `bisql/default` to valid SQL value contexts if context-aware rendering becomes necessary.
  - Detect dangerous `nil` comparisons consistently in `WHERE` / `HAVING` clauses instead of letting expressions such as `= NULL`, `LIKE NULL`, or `IN (NULL)` silently behave unexpectedly. This likely needs stricter SQL context parsing, because `= NULL` is dangerous in `WHERE` / `HAVING` but can still be valid assignment syntax in `SET`.
- Expand CRUD generation output and integration coverage further.
