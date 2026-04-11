## Result Shape Notes

This note records the current decision around generated `count` queries and why
bisql does not introduce a new scalar return contract yet.

### Current Decision

- Keep the existing `:cardinality` metadata with `:one` and `:many`.
- Generate `count` queries as ordinary one-row queries.
- Use `SELECT COUNT(*) AS count ...` and return the usual row map shape.

Example:

```sql
/*:name crud.count-by-state */
/*:cardinality :one */
SELECT COUNT(*) AS count
FROM orders
WHERE state = /*$state*/'sample'
```

This means the runtime result remains consistent with the current adapter model:

```clojure
{:count 42}
```

### Why Not Introduce `:scalar` Yet

We considered introducing a separate scalar return contract, but that quickly
expands into a larger result-shape design problem:

- `0 or 1` value
- exactly `1` value
- `0..n` values
- `1..n` values
- `0 or 1` row
- exactly `1` row
- `0..n` rows
- `1..n` rows

That is a broader contract system than the current `:one` / `:many` split.

There is also an ambiguity problem:

- no result
- one scalar result whose value is `NULL`

If a scalar API returned `nil` for both, those cases would be indistinguishable.

### Why This Is Deferred

That kind of result-shape contract is better handled together with future schema
or output validation work, especially Malli integration.

At that point, bisql can decide whether it needs concepts such as:

- row vs rows
- scalar
- optional row
- non-empty rows

and how strict each contract should be.

For now, `count` remains intentionally simple:

- generated automatically
- returned as a normal one-row result
- keyed by the explicit alias `count`
