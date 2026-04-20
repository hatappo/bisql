# Getting Started

## 1. Create a Minimal Table

```sql
CREATE TABLE users (
  id BIGSERIAL PRIMARY KEY,
  email TEXT NOT NULL UNIQUE,
  status TEXT NOT NULL,
  display_name TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX users_status_created_at_idx
  ON users (status, created_at);

CREATE INDEX users_status_display_name_idx
  ON users (status, display_name);

INSERT INTO users (id, email, status, display_name, created_at)
VALUES
  (42, 'alice@example.com', 'active', 'Alice', '2026-04-01T10:00:00Z'),
  (43, 'bob@example.com', 'inactive', 'Bob', '2026-04-02T11:00:00Z');
```

## 2. Define Query Functions

```clojure
(ns sql
  (:require [bisql.core :as bisql]))

(bisql/defquery)
```

`bisql.core/defquery` uses the current namespace only to find SQL files.
Each discovered SQL file defines executable query functions in the namespace derived
from its file path, so the same SQL file always maps to the same namespace.
By default, query execution uses the `:next-jdbc` adapter.

## 3. Generate CRUD SQL and Function Declarations

Continuing from the schema above, you can generate a config template (`bisql.edn`) and modify it if needed:

```sh
clojure -M -m bisql.cli gen-config
```

Then generate CRUD SQL and matching function namespace files:

```sh
clojure -M -m bisql.cli gen-crud-and-functions
```

This writes files such as:

- `src/sql/postgresql/public/users/crud.sql`

Generated SQL covers the common CRUD-oriented, index-friendly query patterns you
would otherwise write by hand. The same command also writes matching function
namespace files so those generated queries are easy to `require` from ordinary
application code.

## 4. Execute One Generated Query

```clojure
(ns app.user-service
  (:require [next.jdbc :as jdbc]
            [sql]
            [sql.postgresql.public.users.crud :as users]))

(def datasource
  (jdbc/get-datasource
   {:dbtype "postgresql"
    :host "localhost"
    :port 5432
    :dbname "bisql_dev"
    :user "bisql"
    :password "bisql"}))

(users/get-by-id datasource {:id 42})

;; => {:id 42
;;     :email "alice@example.com"
;;     :status "active"
;;     :display_name "Alice"
;;     :created_at #object[java.time.OffsetDateTime ...]}
```

## 5. Write a Custom SQL Template

The generated `get-by-id` query is a good starting point for a custom query:

```sql
SELECT *
FROM users
WHERE id = /*$id*/1
```

This template remains executable SQL with its embedded sample values.
At runtime, Bisql renders it into SQL plus bind params.

When you need a custom query, you can copy that generated SQL and narrow it for
your application.

For example, you might save this as:

```text
src/sql/postgresql/public/users/find-email-by-id.sql
```

```sql
SELECT email
FROM users
WHERE id = /*$id*/1
```

This keeps the SQL-first workflow intact while still letting you customize the
final query.

After adding a hand-written SQL file, regenerate function namespace files:

```sh
clojure -M -m bisql.cli gen-functions
```

## 6. Execute the Customized Query

```clojure
(ns app.user-service
  (:require [sql]
            [next.jdbc :as jdbc]
            [sql.postgresql.public.users.core :as users]))

(def datasource
  (jdbc/get-datasource
   {:dbtype "postgresql"
    :host "localhost"
    :port 5432
    :dbname "bisql_dev"
    :user "bisql"
    :password "bisql"}))

(users/find-email-by-id datasource {:id 42})

;; => {:email "alice@example.com"}
```

See also:

- [SQL Rendering](06-sql-rendering.md)
- [Conditional Expressions](08-conditional-expressions.md)
- [CRUD Generation](09-crud-generation.md)
- [Rendering Examples](07-rendering-examples.md)

## 7. Wrap up

The developer workflow with Bisql is straightforward:

1. Add `defquery` to your application code once, so Bisql can turn SQL files, including generated ones, into ordinary Clojure functions.

2. Run `clojure -M:bisql gen-crud-and-functions` to generate both routine CRUD SQL and matching function namespace files, and add hand-written SQL where you need more complex queries.

3. Copy and adapt generated SQL templates when you need custom queries.

4. Call both generated and hand-written query functions from your application code.

> [!TIP]- See a more detailed example of generated CRUD query families
> Generated SQL typically includes:
>
> - `insert`
> - `insert-many`
> - `get-by-*`
> - `list-by-*`
> - `list-by-*-starting-with`
> - `count`
> - `count-by-*`
> - `count-by-*-starting-with`
> - `update-by-*`
> - `delete-by-*`
> - `upsert-by-*`
>
> These generated queries are meant to cover the typical index-friendly SQL
> patterns you would usually write by hand. In practice, that often means you do
> not need to write much custom SQL at all. When you do need a custom query, the
> generated SQL templates are also a convenient base to copy and adapt.
>
> For the sample tables above, this typically includes:
>
> - `users.crud/insert`
> - `users.crud/insert-many`
> - `users.crud/upsert-by-id`
> - `users.crud/upsert-by-email`
> - `users.crud/get-by-id`
> - `users.crud/get-by-email`
> - `users.crud/count`
> - `users.crud/count-by-status`
> - `users.crud/count-by-status-and-created-at`
> - `users.crud/count-by-status-and-display-name`
> - `users.crud/count-by-status-and-display-name-starting-with`
> - `users.crud/list`
> - `users.crud/list-order-by-status-and-created-at`
> - `users.crud/list-order-by-status-and-display-name`
> - `users.crud/list-by-status-order-by-created-at`
> - `users.crud/list-by-status-order-by-display-name`
> - `users.crud/list-by-status-and-created-at`
> - `users.crud/list-by-status-and-display-name`
> - `users.crud/list-by-status-and-display-name-starting-with`
> - `users.crud/update-by-id`
> - `users.crud/update-by-email`
> - `users.crud/delete-by-id`
> - `users.crud/delete-by-email`
