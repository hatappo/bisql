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

## 4. Generate CRUD SQL and Function Namespaces

Continuing from the previous example, you can generate a config template (`bisql.edn`) and modify it if needed:

```sh
clojure -M -m bisql.cli gen-config
```

Then generate CRUD SQL:

```sh
clojure -M -m bisql.cli gen-crud-and-functions
```

This writes files such as:

- `src/sql/postgresql/public/users/crud.sql`
- `src/sql/postgresql/public/orders/crud.sql`

Generated SQL covers the common CRUD-oriented, index-friendly query patterns you
would otherwise write by hand. The same command also writes matching function
namespace files so those generated queries are easy to `require` from ordinary
application code.

## 5. Execute One Generated Query

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

## 6. Copy and Adapt One Generated Query

When you need a custom query, a generated SQL template is often a convenient
starting point.

For example, you might copy a generated list query and narrow the selected
columns for a real application use case:

```sql
SELECT id, email, created_at
FROM users
WHERE status = /*$status*/'active'
ORDER BY id
LIMIT /*$limit*/100
```

This keeps the SQL-first workflow intact while still letting you customize the
final query.

## 7. Execute the Customized Query

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

See also:

- [Rendering](06-rendering.md)
- [CRUD Generation](08-crud-generation.md)
- [Rendering Examples](07-rendering-examples.md)

## 8. Wrap up

The developer workflow with Bisql is straightforward:

1. Add `defquery` to your application code once, so Bisql can turn SQL files, including generated ones, into ordinary Clojure functions.

2. Run `clojure -M:bisql gen-crud-and-functions` to generate both routine CRUD SQL and matching function namespace files, and add hand-written SQL where you need more complex queries.

3. Copy and adapt generated SQL templates when you need custom queries.

4. Call both generated and hand-written query functions from your application code.

<details>
<summary>See a more detailed example of generated CRUD query families</summary>

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

</details>
