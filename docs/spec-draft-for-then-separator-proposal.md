# Draft: `for ... separating <separator>` and `if/elseif/else => <fragment>`

This note records possible future extensions to bisql's template language for
`for`, `if`, `elseif`, and `else`.

## 1. `for ... separating <separator>`

### Motivation

Current `for` blocks render each iteration body as-is, then trim the trailing
separator from the last rendered fragment.

Today this is used to support patterns such as:

```sql
UPDATE users
SET
/*%for item in items */
  /*!item.name*/ = /*$item.value*/'sample',
/*%end */
WHERE id = /*$id*/1
```

This works, but it has two drawbacks:

1. Generated output depends on a post-processing rule that removes the last
   trailing separator.
2. The source template is harder to read as SQL because separators are written
   after each item, including the last one.

### Proposed Syntax

Extend `for` with an optional `separating` separator:

```sql
/*%for row in rows separating , */
...
/*%end */
```

The separator is not emitted before the first iteration. It is emitted before
every subsequent iteration.

Conceptually:

- first item: no separator
- second and later items: emit `,` first, then emit the body

This makes `for` blocks work more like join/interpose than "render everything
and trim the last separator".

### Example

Instead of:

```sql
VALUES
/*%for row in rows */
(
  /*$row.email*/'a@example.com',
  /*$row.status*/'active'
),
/*%end */
```

use:

```sql
VALUES
/*%for row in rows separating , */
(
  /*$row.email*/'a@example.com',
  /*$row.status*/'active'
)
/*%end */
```

This is easier to read and produces a more SQL-like source template.

### Potential Benefits

- Removes the need to trim trailing `,`, `AND`, or `OR` from the last rendered
  `for` iteration.
- Makes generated SQL rules simpler because separators become prefix separators
  instead of trailing separators.
- Keeps templates closer to the SQL shape they are trying to express.

### Open Questions

- Exact grammar for `separating`
- Whether separators should be restricted to a small set such as `,`, `AND`,
  and `OR`
- Whether existing trailing-separator behavior should remain for backward
  compatibility or eventually be removed

## 2. `elseif ... => <fragment>` and `else => <fragment>`

### Motivation

Current `if` / `elseif` / `else` blocks place alternative SQL fragments outside
the directive comments, which makes template source visibly diverge from raw
SQL as soon as branching appears.

The goal of this draft is not to make control-flow templates fully valid raw
SQL. The smaller goal is to keep more of the alternative branch content inside
directive comments so the surrounding SQL stays flatter and easier to scan.

### Proposed Syntax

Keep `if` bodies as they are today, but allow `elseif` and `else` to carry an
inline fragment using `=>`.

```sql
/*%if use-user-id */
user_id
/*%elseif use-order-id => user.order_id */
/*%else => user.id */
/*%end*/
```

### Mixed Usage Rule

If an `elseif` or `else` directive has both:

- an inline `=> <fragment>` body inside the comment, and
- body content outside the comment before the next directive,

the template should be rejected as invalid.

Inline and block forms should remain mutually exclusive for a given branch. This
keeps the syntax easier to reason about and avoids surprising concatenation
rules.

### Stricter Alternative

If backward compatibility is allowed to break, bisql could go further and make
`elseif` and `else` comment-only constructs:

- `elseif` and `else` may carry output only via `=> <fragment>`
- body content outside those directive comments is never allowed

Conceptually:

```sql
/*%if use-user-id */
user_id
/*%elseif use-order-id => user.order_id */
/*%else => user.id */
/*%end*/
```

In that stricter model, comment-outside branch bodies are rejected entirely for
`elseif` and `else`.

This would simplify the grammar further, but it would be a more opinionated
syntax change and would require explicit migration from the current block style.

### Potential Benefits

- `elseif` and `else` no longer need to introduce all branch content outside
  the directive comment.
- Small alternative fragments become much easier to read.
- The syntax pairs naturally with `for ... separating <separator>`.

### Open Questions

- Whether `if` should eventually gain a matching `=>` form as well
- Whether multiline inline fragments should remain out of scope
- Exact validation rule and error message for rejecting mixed usage
- Whether a breaking change that forbids comment-outside bodies for `elseif`
  and `else` would be worth the simplification
