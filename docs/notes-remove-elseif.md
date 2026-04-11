# Why `elseif` Was Removed

`elseif` was removed from bisql's template syntax to keep conditional rendering
small, explicit, and easier to reason about.

## Summary

Bisql now supports:

- `/*%if ... */`
- `/*%else */`
- `/*%end */`

Bisql no longer supports:

- `/*%elseif ... */`

## Rationale

### 1. The value was limited

`elseif` is mostly convenient when a template needs three or more alternative
SQL fragments in the same position.

In practice, many of those cases can be handled without a dedicated `elseif`
construct:

- choose a raw SQL fragment in Clojure and pass it via a raw variable
- choose a collection in Clojure and pass it to `IN (...)`
- use nested `if` blocks when branching is still genuinely local

That means `elseif` was useful, but not essential.

### 2. The syntax made the template language heavier

`elseif` added branching complexity to:

- the parser
- conditional branch representation
- template examples and documentation
- future syntax design for inline branch fragments

Removing it keeps conditional blocks closer to bisql's intended scope: local,
minimal SQL assembly.

### 3. It simplifies future design choices

Bisql still has an open design question around possible inline branch syntax,
such as comment-contained `else => <fragment>` forms.

Keeping `elseif` would force those designs to answer more questions:

- whether `elseif` should get its own inline form
- whether block and inline `elseif` should coexist
- whether `elseif` bodies should remain outside comments

Removing `elseif` narrows the future design space and makes `if` / `else`
easier to evolve.

## Recommended Alternatives

### Raw variables

Instead of branching inside SQL for `ORDER BY`, choose the fragment in Clojure:

```sql
ORDER BY /*!order-by*/id ASC
```

### Collection parameters

Instead of branching among status predicates, choose the collection in Clojure:

```sql
WHERE status IN /*$statuses*/('active')
```

### Nested `if`

If the branching really belongs in the template, nested `if` is still
available:

```sql
/*%if use-user-id */
user_id
/*%else */
/*%if use-order-id */
order_id
/*%else */
id
/*%end */
/*%end */
```

## Tradeoff

This decision intentionally favors:

- a smaller template language
- fewer parser rules
- less branching-specific complexity

over:

- a more compact syntax for three-way or four-way branching

That tradeoff matches bisql's direction better.
