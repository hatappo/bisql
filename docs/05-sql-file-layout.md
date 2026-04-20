# SQL File Layout

Bisql resolves SQL templates from the logical `sql/` base path on the classpath.
In practice, that usually means `src/sql/` or `resources/sql/`.

Recommended layout:

```text
sql/<dialect>/<schema>/<table>/<clj-function>.sql
```

Example:

```text
src/sql/postgresql/public/users/get-by-id.sql
```

Rules and conventions:

- `postgresql` is the dialect segment in the current implementation
- `public` is the default schema
- the table directory becomes part of the generated namespace
- the file name usually matches the generated or handwritten Clojure function name

This keeps:

- SQL files easy to find
- generated CRUD files aligned with schema structure
- handwritten queries and generated queries in the same shape

See also:

- [Getting Started](03-getting-started.md)
- [CRUD Generation](09-crud-generation.md)
