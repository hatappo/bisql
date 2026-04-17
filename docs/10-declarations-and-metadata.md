# Declarations and Metadata

Bisql supports declaration comments inside SQL files.

These declarations are parsed and returned under `:meta`.

## Syntax

```sql
/*:name
<edn-or-text>
*/
```

Example:

```sql
/*:doc
Loads a user by id.
*/
/*:cardinality
:one
*/
SELECT *
FROM users
WHERE id = /*$id*/1
```

Rendered result:

```clojure
{:sql "SELECT * FROM users WHERE id = ?"
 :params [42]
 :meta {:doc "Loads a user by id."
        :cardinality :one}}
```

## Common Uses

- documentation
- tags
- cardinality hints
- project-specific metadata

## Notes

- duplicate declarations are errors
- declaration bodies are parsed as EDN by default
- `:doc` also accepts plain text and is trimmed into a string

This metadata is useful both in rendering output and in generated declaration namespaces.

See also:

- [Rendering](06-rendering.md)
- [Rendering Examples](07-rendering-examples.md)
