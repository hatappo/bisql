# Introduction

Bisql is a SQL-first data access library for Clojure.

Its design goal is simple:

- keep SQL as the source of truth
- keep templates readable and executable SQL
- generate repetitive CRUD queries from the database schema
- keep runtime behavior explicit and predictable

Bisql does not try to replace SQL with a DSL or query builder.
Instead, it gives you:

- comment-based 2-way SQL templating
- rendering into SQL + bind params
- declaration metadata inside `.sql` files
- generated CRUD-oriented SQL and Clojure entrypoints, including many index-friendly query variants
- adapter-based execution, currently centered on `next.jdbc`

The minimal intended workflow is:

1. write or generate SQL templates
2. add a single macro call to your code to turn those SQL templates into Clojure functions

See also:

- [Installation](02-installation.md)
- [Getting Started](03-getting-started.md)
- [What is 2-way-SQL](04-what-is-2-way-sql.md)
