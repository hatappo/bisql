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

Place a SQL template under a classpath `sql/...` directory:

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

## 4. Generate CRUD SQL

Generate a config template:

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
- `count`
- `count-by-*`
- `update-by-*`
- `delete-by-*`
- `upsert-by-*`

See also:

- [Rendering](rendering.md)
- [CRUD Generation](crud-generation.md)

## 5. Developer Workflow with Bisql

1. Run `clojure -M:bisql gen-crud` to generate the routine CRUD SQL you would otherwise write by hand.

2. Add `defquery` to your application code so Bisql can turn SQL files, including generated ones, into ordinary Clojure functions.

3. If needed, run `clojure -M:bisql gen-declarations` to generate matching `declare` forms for those functions.

4. Call the generated query functions from your application code.
