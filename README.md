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

- [docs/installation.md](docs/installation.md)
- [https://hatappo.github.io/bisql/docs/installation/](https://hatappo.github.io/bisql/docs/installation/)

## Getting Started

The full Getting Started guide lives here:

- [docs/getting-started.md](docs/getting-started.md)
- [https://hatappo.github.io/bisql/docs/getting-started/](https://hatappo.github.io/bisql/docs/getting-started/)

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
