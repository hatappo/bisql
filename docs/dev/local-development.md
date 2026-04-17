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

lint             Run clj-kondo on project sources
format           Fix Clojure formatting with cljfmt
format-check     Check Clojure formatting with cljfmt
test             Run tests
test-db          Reset sample schema and run integration tests against local PostgreSQL
verify           Run format check, lint, and tests
fix              Run format, lint, and tests
db-reset         Reset local PostgreSQL sample schema and seed data
gen-config       Generate a bisql.edn config template
gen-crud         Generate CRUD SQL files from local PostgreSQL
gen-declarations Generate declaration namespace files from local SQL templates
jar              Build the jar via build.clj; accepts `bb jar x.x.x` or raw build args
install          Install to local Maven cache via build.clj; accepts `bb install x.x.x` or raw build args
release          Release to Clojars via build.clj; accepts `bb release x.x.x` or raw build args
release-check    Verify a released version resolves from Clojars and loads bisql.core; accepts `bb release-check x.x.x`
token            Open ~/.m2/settings.xml for editing the temporary Clojars token
gen-examples     Generate docs/07-rendering-examples.md from docs/data/rendering-examples.edn and open it
pages-build      Build the static pages app into pages/dist/
pages-dev        Start the shadow-cljs pages dev server on http://localhost:8000
```

`bb gen-declarations` に CLI フラグ相当を渡したい場合は、
`bb gen-declarations -- --include-sql-template --suppress-unused-public-var`
のように `--` 区切りで渡す。`bb` の `--flag` は Babashka 自身が先に解釈するため、
この書き方に統一する。

`pages-build` / `pages-dev` は内部的に `pages/` 配下の `deps.edn` と
`shadow-cljs.edn` を使う。`pages/` は独立したサブアプリとして扱っている。

## REPL Check

Start a REPL with the test classpath:

```sh
clj -M:test-repl
```

Then define a datasource and load generated queries:

```clojure
(ns user.demo
  (:require [bisql.core :as bisql]
            [next.jdbc :as jdbc]))

(def ds
  (jdbc/get-datasource
   {:dbtype "postgresql"
    :host "localhost"
    :port 5432
    :dbname "bisql_dev"
    :user "bisql"
    :password "bisql"}))

(bisql/defquery "/sql/postgresql/public/user_devices/crud.sql")
```

Simple example:

```clojure
(sql.postgresql.public.user-devices.crud/count ds {})
```

More advanced example:

```clojure
(sql.postgresql.public.user-devices.crud/upsert-by-user-id-and-device-identifier
 ds
 {:inserting {:user-id 1
              :device-type "browser"
              :device-identifier "browser-1"
              :status "active"
              :last-seen-at (java.time.OffsetDateTime/parse "2026-04-14T00:00:00Z")}
  :non-updating-cols {:status true}})
```

Then fetch the row again:

```clojure
(sql.postgresql.public.user-devices.crud/get-by-user-id-and-device-identifier
 ds
 {:user-id 1
  :device-identifier "browser-1"})
```

Notes:

- SQL resource paths under `test/sql/...` are loaded as `/sql/...`
- `user_devices` becomes `user-devices` in the generated namespace
- Use `java.time.*` values for timestamp columns in REPL tests
- Missing parameters now include the parameter name, for example:
  `Missing query parameter: inserting.device-type`

## Related Docs

- [dev-releasing.md](dev-releasing.md)
- [../07-rendering-examples.md](../07-rendering-examples.md)
