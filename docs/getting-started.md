# Getting Started

## 1. Create a Minimal Table

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

## 2. Write One SQL Template

Place a SQL template under a classpath `sql/...` directory.

For example:

```text
src/sql/postgresql/public/users/find-active.sql
```

```sql
SELECT *
FROM users
WHERE status = /*$status*/'active'
ORDER BY id
LIMIT /*$limit*/100
```

This template remains executable SQL with its embedded sample values.
At runtime, Bisql renders it into SQL plus bind params.

## 3. Define Query Functions

```clojure
(ns sql
  (:require [bisql.core :as bisql]))

(bisql/defquery)
```

Then call the generated function:

```clojure
(ns app.user-service
  (:require [next.jdbc :as jdbc]
            [sql.postgresql.public.users.core :as users]))

(def datasource
  (jdbc/get-datasource
   {:dbtype "postgresql"
    :host "localhost"
    :port 5432
    :dbname "bisql_dev"
    :user "bisql"
    :password "bisql"}))

(users/find-active datasource {:status "active"
                               :limit 20})
```

`bisql.core/defquery` uses the current namespace only to find SQL files.
Each discovered SQL file defines executable query functions in the namespace derived
from its file path, so the same SQL file always maps to the same namespace.
By default, query execution uses the `:next-jdbc` adapter.

## 4. Generate CRUD SQL

Continuing from the previous example, you can generate a config template (`bisql.edn`) and modify it if needed:

```sh
clojure -M -m bisql.cli gen-config
```

Then generate CRUD SQL:

```sh
clojure -M -m bisql.cli gen-crud
```

This writes files such as:

- `src/sql/postgresql/public/users/crud.sql`
- `src/sql/postgresql/public/orders/crud.sql`

Generated SQL typically includes:

- `insert`
- `insert-many`
- `get-by-*`
- `list-by-*`
- `list-by-*-starting-with`
- `count`
- `count-by-*`
- `count-by-*-starting-with`
- `update-by-*`
- `delete-by-*`
- `upsert-by-*`

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
- `users.crud/count-by-status-starting-with`
- `users.crud/list-by-email-starting-with`
- `users.crud/update-by-id`
- `users.crud/update-by-email`
- `users.crud/delete-by-id`
- `users.crud/delete-by-email`
- `orders.crud/insert`
- `orders.crud/insert-many`
- `orders.crud/upsert-by-id`
- `orders.crud/count`
- `orders.crud/count-by-state`
- `orders.crud/count-by-order-number-starting-with`
- `orders.crud/count-by-state-and-created-at`
- `orders.crud/list`
- `orders.crud/get-by-id`
- `orders.crud/update-by-id`
- `orders.crud/delete-by-id`
- `orders.crud/list-by-order-number-starting-with`
- `orders.crud/list-by-state`
- `orders.crud/list-by-state-and-created-at`

Then execute one of the generated functions:

```clojure
(ns app.order-service
  (:require [next.jdbc :as jdbc]
            [sql]
            [sql.postgresql.public.orders.crud :as orders]))

(def datasource
  (jdbc/get-datasource
   {:dbtype "postgresql"
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

See also:

- [Rendering](rendering.md)
- [CRUD Generation](crud-generation.md)
- [Rendering Examples](rendering-examples.md)

## 5. Wrap up

The developer workflow with Bisql is straightforward:

1. Add `defquery` to your application code once, so Bisql can turn SQL files, including generated ones, into ordinary Clojure functions.

2. Run `clojure -M:bisql gen-crud` to generate the routine CRUD SQL you would otherwise write by hand, and add hand-written SQL where you need more complex queries.

3. If needed, run `clojure -M:bisql gen-declarations` to generate matching `declare` forms for those functions.

4. Call the generated query functions from your application code.
