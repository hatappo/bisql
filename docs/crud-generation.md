# CRUD Generation

Bisql can generate typical index-friendly CRUD SQL from the database schema.

## Generated Query Families

Depending on table structure and indexes, generated SQL commonly includes:

- `insert`
- `insert-many`
- `get-by-*`
- `list`
- `list-by-*`
- `count`
- `count-by-*`
- `update-by-*`
- `delete-by-*`
- `upsert-by-*`

The generated SQL is intended to cover the repetitive queries that usually follow:

- primary keys
- unique constraints
- index prefixes

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

## Typical Workflow

1. generate `bisql.edn`
2. adjust config if needed
3. run `gen-crud`
4. load generated SQL through `defquery`

See also:

- [Getting Started](getting-started.md)
- [Bisql Adapters](bisql-adapters.md)
