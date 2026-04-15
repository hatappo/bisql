# Rendering

Rendering is the process of turning:

- a SQL template
- a params map

into:

- rendered SQL
- bind params
- metadata

## Render Result Shape

Rendering returns a map like this:

```clojure
{:sql "SELECT * FROM users WHERE id = ?"
 :params [42]
 :meta {}}
```

If declaration comments exist, `:meta` contains them.

## Bind Variables

```sql
WHERE id = /*$id*/1
```

```clojure
{:id 42}
```

→

```clojure
{:sql "WHERE id = ?"
 :params [42]}
```

## Collection Binding

```sql
WHERE id IN /*$ids*/(1,2,3)
```

```clojure
{:ids [10 20 30]}
```

→

```clojure
{:sql "WHERE id IN (?, ?, ?)"
 :params [10 20 30]}
```

## Conditional Rendering

```sql
WHERE
/*%if active */
  active = true
/*%else => status = 'inactive' */
/*%end */
```

The rendered SQL depends on the truthiness of `active`.

## `DEFAULT` and `ALL`

`bisql/DEFAULT` and `bisql/ALL` are rendered as SQL keywords for scalar `$` bindings.

```clojure
{:status bisql/DEFAULT
 :limit bisql/ALL}
```

## `LIKE_*` Helpers

Bisql also provides small helpers for `LIKE` bindings:

- `bisql/LIKE_STARTS_WITH`
- `bisql/LIKE_ENDS_WITH`
- `bisql/LIKE_CONTAINS`

For example:

```sql
WHERE name LIKE /*$search*/'foo%' ESCAPE '\'
```

```clojure
{:search (bisql/LIKE_CONTAINS "foo%_bar")}
```

→

```clojure
{:sql "WHERE name LIKE ? ESCAPE '\\'"
 :params ["%foo\\%\\_bar%"]}
```

These helpers:

- add `%` in the expected position
- escape `%`
- escape `_`
- escape `\`

They are intended for scalar `$` bindings.

## Runtime APIs

Typical user-facing APIs are:

- `bisql.core/defrender`
- `bisql.core/defquery`

For lower-level examples, see:

- [Rendering Examples](rendering-examples.md)
