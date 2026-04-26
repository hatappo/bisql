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
- `update`

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

Generated update and upsert templates now use map-shaped params:

- `update-by-*`
  - `:where`
  - `:updates`
- `upsert-by-*`
  - `:inserts`
  - `:updates`

For example, a generated update template uses a `for` block in `SET`:

```sql
UPDATE users
SET
/*%for item in updates separating , */
  /*!item.name*/created_at = /*$item.value*/CURRENT_TIMESTAMP
/*%end */
WHERE id = /*$where.id*/1
RETURNING *
```

This allows:

- partial updates without supplying every updatable column
- updating columns that also appear in the predicate
- generated SQL that still remains ordinary executable SQL templates

Generated upserts follow the same idea:

- `:inserts` contains the insert path values
- `:updates` contains the conflict update assignments

For insert-style generated templates, defaultable primary key columns use
`default-to` with `DEFAULT`:

```sql
/*$id default-to */DEFAULT
```

This applies to `insert`, `insert-many`, and upserts where the defaultable primary
key is not part of the conflict target. In an upsert such as `upsert-by-id`, the
conflict target column remains a required bind value because it defines what the
upsert is matching.

If `:updates` is `nil`, the generated upsert renders `DO NOTHING`.
If `:updates` is an empty map, rendering still fails because an empty `SET`
clause is not allowed.

If you need special `EXCLUDED` expressions or more unusual conflict behavior,
write a custom SQL template instead.
