# Releasing

Current artifact coordinates:

```clojure
io.github.hatappo/bisql
```

## Checklist

```sh
bb lint
bb test
bb jar <version>
```

## Clojars Auth

Use `~/.m2/settings.xml`:

```xml
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 https://maven.apache.org/xsd/settings-1.0.0.xsd">
  <servers>
    <server>
      <id>clojars</id>
      <username>YOUR_CLOJARS_USERNAME</username>
      <password>YOUR_DEPLOY_TOKEN</password>
    </server>
  </servers>
</settings>
```

Notes:

- `id` must be `clojars`
- `username` must be the Clojars username
- `password` must be a deploy token

## Publish

```sh
bb deploy <version>
```

For convenience, this project also provides:

```sh
bb token
```

This opens `~/.m2/settings.xml` so you can paste a deploy token, publish, and remove it afterward.

## Known Issues

### 401 Unauthorized

Check:

1. `~/.m2/settings.xml` exists
2. `id` is `clojars`
3. `username` is correct
4. `password` is a deploy token
5. the token was copied without extra whitespace

### `nth not supported on this type: Character`

This came from passing `:repository "clojars"` directly to `deps-deploy 0.2.2`.
`build.clj` now works around it by resolving the repository map before deploy.

## References

- <https://clojars.org/>
- <https://github.com/clojars/clojars-web/wiki/Pushing>
- <https://github.com/clojars/clojars-web/wiki/Clojure-CLI-deps.edn>
