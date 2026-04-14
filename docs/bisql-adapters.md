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

## Adapter Scope

Bisql does not try to replace the entire JDBC layer.
Adapters are intentionally thin.

That keeps Bisql focused on:

- SQL templates
- rendering
- CRUD generation

while delegating actual execution to established database libraries.
