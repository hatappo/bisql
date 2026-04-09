# Local Development

## Prerequisites

```sh
mise install # to install Java(JDK)
brew install clojure/tools/clojure # or see https://clojure.org/guides/install_clojure
brew install borkdude/brew/babashka # or see https://github.com/babashka/babashka#installation
```

## Setup

### Setup Clojure

```sh
clj -P
```

### Setup PostgreSQL

```sh
docker compose up -d
bb test-db
```

Connection settings:

- host: `localhost`
- port: `5432`
- database: `bisql_dev`
- user: `bisql`
- password: `bisql`

Connect with `psql`:

```sh
psql -h localhost -p 5432 -U bisql -d bisql_dev
```

Quick checks:

```sql
\dn+

\d

SELECT * FROM users;
SELECT * FROM orders;
```

## Tasks

```text
$ bb tasks
The following tasks are available:

lint         Run clj-kondo on project sources
format       Fix Clojure formatting with cljfmt
format-check Check Clojure formatting with cljfmt
test         Run tests
test-db      Reset sample schema and run integration tests against local PostgreSQL
verify       Run format check, lint, and tests
fix          Run format, lint, and tests
db-reset     Reset local PostgreSQL sample schema and seed data
gen-config   Generate a bisql.edn config template
gen-crud     Generate CRUD SQL files from local PostgreSQL
gen-ns       Generate query namespace files from local PostgreSQL
jar          Build the jar via build.clj; accepts `bb jar 0.1.0` or raw build args
install      Install to local Maven cache via build.clj; accepts `bb install 0.1.0` or raw build args
deploy       Deploy to Clojars via build.clj; accepts `bb deploy 0.1.0` or raw build args
token        Open ~/.m2/settings.xml for editing the temporary Clojars token
demo         Run example scripts for manual verification
demo-preview Write demo output to docs/spec-rendering-examples.md and open it
```

## Related Docs

- [dev-releasing.md](dev-releasing.md)
- [spec-rendering-examples.md](spec-rendering-examples.md)
