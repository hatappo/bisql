# Installation

Add Bisql to `deps.edn`:

```clojure
{:deps {io.github.hatappo/bisql {:mvn/version "0.2.2"}}}
```

If you want a shorter `clojure` entrypoint, add an alias:

```clojure
{:aliases
 {:bisql {:main-opts ["-m" "bisql.cli"]}}}
```

Then these commands become available:

```sh
clojure -M:bisql gen-config
clojure -M:bisql gen-crud
clojure -M:bisql gen-functions
clojure -M:bisql gen-crud-and-functions
```

> [!TIP]- Prefer `bb` instead?
> If you prefer `bb`, define tasks like this:
>
> ```clojure
> {:tasks
>  {:gen-config (apply clojure "-M:bisql" "gen-config" *command-line-args*)
>   :gen-crud (apply clojure "-M:bisql" "gen-crud" *command-line-args*)
>   :gen-functions (apply clojure "-M:bisql" "gen-functions" *command-line-args*)
>   :gen-crud-and-functions (apply clojure "-M:bisql" "gen-crud-and-functions" *command-line-args*)}}
> ```
>
> When passing CLI flags through `bb`, use `--` as a separator:
>
> ```sh
> bb gen-functions -- --include-sql-template --suppress-unused-public-var
> ```

Current assumptions:

- PostgreSQL is the primary target database
- `next.jdbc` is the default execution adapter

See also:

- [Getting Started](03-getting-started.md)
- [Bisql Adapters](12-bisql-adapters.md)
