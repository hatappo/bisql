# Conditional Expressions

Bisql supports a small expression language inside `if` and `elseif` directives.

The goal is to keep conditional SQL readable without turning SQL templates into
a general-purpose scripting language.

## Supported Operators

Bisql currently supports:

- logical operators: `and`, `or`
- comparison operators: `=`, `!=`, `<`, `<=`, `>`, `>=`
- parentheses: `(`, `)`

Examples:

```sql
/*%if active and status = expected_status */
  status = 'pending'
/*%end */
```

```sql
/*%if (active and status = expected_status) or pending */
  status = 'pending'
/*%end */
```

## Operands

Operands are parameter references.

That means both sides of a comparison are resolved from the params map:

```sql
/*%if status = expected_status */
```

```clojure
{:status "pending"
 :expected_status "pending"}
```

Dot-path access also works:

```sql
/*%if user.id = owner.id */
```

```clojure
{:user {:id 10}
 :owner {:id 10}}
```

A bare identifier is treated as a truthiness check:

```sql
/*%if active */
```

## Operator Precedence

Expressions follow this precedence order:

1. comparison operators
2. `and`
3. `or`

So this:

```sql
/*%if active or status = expected_status and pending */
```

is interpreted as:

```text
active or ((status = expected_status) and pending)
```

Use parentheses when you want a different grouping.

## Current Limitations

The initial expression language is intentionally small.

These are not supported yet:

- `not`
- string, number, boolean, or `nil` literals
- arithmetic operators
- function calls

For example, this is not supported:

```sql
/*%if status = "active" */
```

Instead, compare parameters:

```sql
/*%if status = expected_status */
```

```clojure
{:status "active"
 :expected_status "active"}
```

> [!NOTE]- Why So Small?
> Bisql is designed to keep SQL executable and predictable.
>
> The expression language is intentionally limited so that:
>
> - SQL templates stay easy to read
> - conditional rendering remains predictable
> - the same evaluator works in both Clojure and ClojureScript
>
> Bisql currently supports this expression language only inside `if` and
> `elseif` conditions.
>
> The main reason is that more complex logic should usually be finished in
> Clojure before values reach the SQL template. Bisql intentionally keeps SQL
> templates focused on SQL structure, with only a small amount of conditional
> control flow.
>
> The same reasoning applies to the language itself. It is intentionally much
> smaller than a general-purpose expression language so that templates stay
> readable, predictable, and close to executable SQL.
