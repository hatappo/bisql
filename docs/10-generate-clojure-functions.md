# Generate Clojure Functions

Bisql does not ask you to call a generic runtime API for every query.
Instead, it can generate normal Clojure vars and functions from SQL resources.

## `defquery`

`defquery` defines executable query functions on top of an adapter.

Typical use:

```clojure
(ns sql
  (:require [bisql.core :as bisql]))

(bisql/defquery)
```

Generated functions take:

- an adapter-compatible datasource or connection
- a params map

For example:

```clojure
(sql.postgresql.public.users.core/get-by-id ds {:id 42})
```

This corresponds to a SQL resource like:

```text
sql/postgresql/public/users/core.get-by-id.sql
```

For example:

```sql
SELECT *
FROM users
WHERE id = /*$id*/1
```

Note:

- Bisql resolves SQL files by `<ns-suffix>.<function-name>.sql`
- if `<ns-suffix>` is omitted, `core` is used as the default namespace suffix
- that is why `sql/postgresql/public/users/get-by-id.sql` also resolves to the same function

`defquery` is the main macro most users will use in application code.

## `defrender`

`defrender` loads SQL templates and defines pure renderer functions.

Typical use:

```clojure
(ns sql
  (:require [bisql.core :as bisql]))

(bisql/defrender)
```

This creates functions that return:

- rendered SQL
- bind params
- metadata

For example:

```clojure
(sql.postgresql.public.users.core/get-by-id {:id 42})
```

This corresponds to the same SQL resource:

```text
sql/postgresql/public/users/core.get-by-id.sql
```

returns a map like:

```clojure
{:sql "SELECT * FROM users WHERE id = ?"
 :params [42]
 :meta {}}
```

`defrender` is useful for debugging and development workflows where you want to inspect rendered SQL without executing it.

## Why This Matters

This keeps the call site simple:

- no manual resource lookup
- no repeated render calls at every usage site
- ordinary vars and functions that work well with REPL navigation

It also keeps SQL visible.
The source of truth is still the SQL file itself.

## Relationship to CRUD Generation

Generated CRUD SQL and handwritten SQL follow the same model.
Once a SQL file exists under `sql/...`, Bisql can generate matching Clojure functions for it.

See also:

- [SQL File Layout](05-sql-file-layout.md)
- [CRUD Generation](09-crud-generation.md)
- [Rendering](06-rendering.md)
