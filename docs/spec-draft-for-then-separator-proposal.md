# Draft: inline `elseif/else => <fragment>` branches

This note records a possible future extension to bisql's template language for
`if`, `elseif`, and `else`.

`for ... separating <separator>` has already been adopted and is now part of the
current template syntax. The remaining draft here is only for inline branch
fragments in `elseif` and `else`.

## `elseif ... => <fragment>` and `else => <fragment>`

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
- The syntax still pairs naturally with `for ... separating <separator>`.

### Open Questions

- Whether `if` should eventually gain a matching `=>` form as well
- Whether multiline inline fragments should remain out of scope
- Exact validation rule and error message for rejecting mixed usage
- Whether a breaking change that forbids comment-outside bodies for `elseif`
  and `else` would be worth the simplification
