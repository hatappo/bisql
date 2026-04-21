# CRUD Generation

Bisql can generate typical index-friendly CRUD SQL from the database schema with
`gen-crud`.

Alongside `crud.sql`, Bisql also generates a sibling `schema.clj` file for each
table. These generated schema namespaces are currently used to attach
`:malli/in` and `:malli/out` declarations to generated CRUD queries.

> [!TIP]+
> In practice, you will usually also want the corresponding function namespace
> files, so `gen-crud-and-functions` is typically the more convenient command.

## Generated Query Families

Depending on table structure and indexes, generated SQL commonly includes:

- `insert`
- `insert-many`
- `get-by-*`
- `list`
- `list-by-*`
- `list-by-*-starting-with`
- `count`
- `count-by-*`
- `count-by-*-starting-with`
- `update-by-*`
- `delete-by-*`
- `upsert-by-*`

The generated SQL is intended to cover the repetitive queries that usually follow:

- primary keys
- unique constraints
- index prefixes

Generated `*-starting-with` queries are intentionally conservative:

- they are only generated when the final column of a unique key or index is a text column
- earlier columns remain exact matches
- the final text column uses `LIKE ... ESCAPE '\'`

This keeps the generated queries aligned with ordinary index-friendly prefix search.

## Generated Schema Files

For each generated CRUD SQL file, Bisql also writes a colocated schema namespace:

- `src/sql/postgresql/public/users/crud.sql`
- `src/sql/postgresql/public/users/schema.clj`

The schema file contains table-level base schemas such as:

- `row`
- `insert`

Generated CRUD SQL then refers to those definitions through declaration comments
like `/*:malli/in ... */` and `/*:malli/out ... */`, composing simple Malli
forms inline where that keeps the generated schema file smaller.

## Why This Exists

The goal is not to hide SQL.
The goal is to generate the SQL you would usually write by hand anyway.

That means:

- generated files remain SQL templates
- you can inspect them directly
- you can copy and adapt them when a custom query is needed

## Upsert Generation

Generated upserts use PostgreSQL `ON CONFLICT ... DO UPDATE`.

Bisql also supports generated update policy control via:

- `:inserting`
- `:non-updating-cols`

This allows templates such as:

```sql
SET status = /*%if non-updating-cols.status */ t.status /*%else => EXCLUDED.status */ /*%end */
```

In practice:

- `:inserting` lists the values you want to provide for the insert path
- `:non-updating-cols` names columns that should be used on insert, but should not be updated on conflict

When a column is listed in `:non-updating-cols`, the generated upsert keeps the
existing table value on `DO UPDATE` instead of taking the value from
`EXCLUDED`. A typical example is `created_at`, which is usually set on insert
but should remain unchanged on later updates.

See also:

- [Getting Started](03-getting-started.md)
- [Bisql Adapters](12-bisql-adapters.md)
