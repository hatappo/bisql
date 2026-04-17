# Bisql

[![Clojars Project](https://img.shields.io/clojars/v/io.github.hatappo/bisql.svg)](https://clojars.org/io.github.hatappo/bisql)
[![Docs](https://img.shields.io/badge/docs-pages-cdc0a6)](https://hatappo.github.io/bisql/)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/hatappo/bisql)

<p align="center">
  <img src="docs/img/bicycle.svg" alt="Bisql bicycle logo" width="96" height="96" />
</p>

Bisql (pronounced `báisikl` 🚲) is a SQL-first, SQL-only, SQL-obsessed data access toolkit for Clojure.

Write real SQL. Keep it executable. Generate the boring parts.

- Query templates remain valid, executable SQL.
- Typical index-friendly queries are generated automatically, as comprehensively as possible.

No query builder  
No data mapper  
No hidden SQL  
No boilerplate SQL  

> [!NOTE]
> This project is still early and the API may change.
> Support for databases beyond PostgreSQL and Malli integration are both planned.

## Installation

The full Installation guide lives here:

- [docs/02-installation.md](docs/02-installation.md)
- [https://hatappo.github.io/bisql/docs/installation/](https://hatappo.github.io/bisql/docs/installation/)

## Getting Started

The full Getting Started guide lives here:

- [docs/03-getting-started.md](docs/03-getting-started.md)
- [https://hatappo.github.io/bisql/docs/getting-started/](https://hatappo.github.io/bisql/docs/getting-started/)

## Quick Example

Write one SQL template:

```sql
-- src/sql/postgresql/public/users/find-active.sql
SELECT *
FROM users
WHERE status = /*$status*/'active'
ORDER BY id
LIMIT /*$limit*/100
```

Define query functions once:

```clojure
(ns sql
  (:require [bisql.core :as bisql]))

(bisql/defquery)
```

Then call the generated function:

```clojure
(sql.postgresql.public.users.core/find-active
 datasource
 {:status "active"
  :limit 20})

;; => [{:id 1
;;      :email "user@example.com"
;;      :status "active"}]
```

At runtime, bisql turns the SQL template and params into:

```clojure
{:sql "SELECT *\nFROM users\nWHERE status = ?\nORDER BY id\nLIMIT ?"
 :params ["active" 20]
 :meta {}}
```

For repetitive CRUD queries, generate SQL first:

```sh
clojure -M:bisql gen-crud
```

That writes files such as:

- `src/sql/postgresql/public/users/crud.sql`

Then the same `(bisql/defquery)` call exposes generated functions such as:

```clojure
(sql.postgresql.public.users.crud/count-by-status datasource {:status "active"})
```

You can keep the generated SQL as-is, or add hand-written SQL files alongside it when you need more complex queries.

## Development

For local setup, tasks, and dev workflow, see:

- [docs/dev-local-development.md](docs/dev-local-development.md)

## Roadmap

- Support a very small expression language to improve expressiveness in `if` conditions.
- Add Malli integration.
- Support databases beyond PostgreSQL.
- Compile analyzed SQL templates into reusable renderer functions for lower runtime overhead.
  - Simplify emitted renderer forms further, especially around branch and loop body handling.
  - Reduce helper calls in emitted code where fragment normalization is still delegated.
  - Restrict `bisql/DEFAULT` to valid SQL value contexts if context-aware rendering becomes necessary.
  - Detect dangerous `nil` comparisons consistently in `WHERE` / `HAVING` clauses instead of letting expressions such as `= NULL`, `LIKE NULL`, or `IN (NULL)` silently behave unexpectedly. This likely needs stricter SQL context parsing, because `= NULL` is dangerous in `WHERE` / `HAVING` but can still be valid assignment syntax in `SET`.
