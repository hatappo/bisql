# Bisql Adapters

Bisql separates:

- SQL loading and rendering
- actual database execution

The execution side is handled by adapters.

## Current Default

The current default adapter is `:next-jdbc`.

In practice, `bisql.core/defquery` generates functions that execute through
`next.jdbc`.

## Result Maps

The current `next.jdbc` adapter returns result keys in kebab-case.

For example:

```clojure
{:display-name "Alice"
 :created-at ...}
```

instead of:

```clojure
{:display_name "Alice"
 :created_at ...}
```

## JDBC Time Helpers

For explicit JDBC-oriented conversions, the current adapter namespace provides:

- `bisql.adapter.next-jdbc/->timestamp`
- `bisql.adapter.next-jdbc/->date`
- `bisql.adapter.next-jdbc/->time`

These are useful when working with REPL examples or explicit JDBC values.

For example:

```clojure
(require '[bisql.adapter.next-jdbc :as bisql.jdbc])

(sql.postgresql.public.users.core/list-by-created-at
 ds
 {:created-after (bisql.jdbc/->timestamp #inst "2026-04-14T00:00:00.000-00:00")
  :birth-date (bisql.jdbc/->date (java.time.LocalDate/parse "2026-04-14"))
  :alarm-time (bisql.jdbc/->time (java.time.LocalTime/parse "08:30:00"))})
```

This is mainly useful when you want to keep application values explicit before
passing them to `next.jdbc`.

## Adapter Scope

Bisql does not try to replace the entire JDBC layer.
Adapters are intentionally thin.

That keeps Bisql focused on:

- SQL templates
- rendering
- CRUD generation

while delegating actual execution to established database libraries.
