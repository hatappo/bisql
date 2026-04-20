# What is 2-way-SQL

2-way SQL is a style of SQL templating where:

- the file is still recognizable as SQL
- sample literals remain in place
- those literals are replaced at runtime by bind params or fragments

For example:

```sql
SELECT *
FROM users
WHERE id = /*$id*/1
```

Rendered with:

```clojure
{:id 42}
```

becomes:

```sql
SELECT *
FROM users
WHERE id = ?
```

with params:

```clojure
[42]
```

Why this style is useful:

- SQL stays readable in code review
- you can often run the template directly with its sample values
- the final runtime query remains explicit

Bisql keeps the syntax deliberately small.
It supports:

- bind variables
- collection binding in `IN (...)`
- `if / elseif / else / end`
- `for ... separating ...`
- declaration metadata

Bisql does **not** try to become a full templating language.

See also:

- [SQL Rendering](06-sql-rendering.md)
- [SQL File Layout](05-sql-file-layout.md)
