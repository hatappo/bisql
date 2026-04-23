# Malli Validation

Bisql can attach `:malli/in` and `:malli/out` metadata to generated CRUD queries.

That metadata can then be used for runtime validation.

## Generated CRUD

`gen-crud` now generates:

- CRUD SQL templates
- table-local `schema.clj` files
- `:malli/in` and `:malli/out` declarations inside generated CRUD SQL

For example:

```sql
/*:name crud.get-by-id */
/*:cardinality :one */
/*:malli/in [:map {:closed true} [:id int?]] */
/*:malli/out [:maybe sql.postgresql.public.users.schema/row] */
SELECT *
FROM users
WHERE id = /*$id*/1
```

Generated map schemas are closed, so unexpected keys are rejected.

## Runtime Validation

Bisql validates through the execution path in the `:next-jdbc` adapter.

Validation is controlled by `bisql.validation/*bisql-malli-validation-mode*`.

Supported modes:

- `:off`
- `:when-present`
- `:strict`

You can also control input and output separately:

```clojure
{:in :when-present
 :out :off}
```

For applications, it is usually better to set this once at startup:

```clojure
(require '[bisql.core :as bisql])

(bisql/set-malli-validation-mode! {:in :when-present
                                   :out :when-present})
```

For temporary checks in a REPL or test, use `binding`:

```clojure
(require '[bisql.validation :as validation])

(binding [validation/*bisql-malli-validation-mode* :strict]
  ...)
```

## REPL Example

```clojure
(ns sql
  (:require [bisql.core :as bisql]
            [next.jdbc :as jdbc]))

(def ds
  (jdbc/get-datasource
   {:dbtype "postgresql"
    :host "localhost"
    :port 5432
    :dbname "bisql_dev"
    :user "bisql"
    :password "bisql"}))

(bisql/defquery)
(bisql/set-malli-validation-mode! {:in :when-present
                                   :out :when-present})

(sql.postgresql.public.users.crud/get-by-id ds {:id 1})
```

Unexpected input keys are rejected:

```clojure
(sql.postgresql.public.users.crud/get-by-id ds {:id 1 :foo 1})
```

This fails with a message like:

```clojure
Malli input validation failed for crud.get-by-id. {:foo ["disallowed key"]}
```

## Scope

Generated CRUD gets Malli metadata automatically.

Hand-written SQL does not.
If you want runtime validation there too, add `:malli/in` and `:malli/out`
declarations yourself.
