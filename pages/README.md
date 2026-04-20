# Pages

`pages/` contains the static docs site and playground sub-application for bisql.

It includes:

- the Docs site
- the Playground
- route-specific static HTML generation
- build-time generation of docs and examples catalogs

## Main Commands

Run these from the repository root.

```sh
bb pages-dev
bb pages-build
bb pages-clean
```

- `bb pages-dev`
  - starts the dev server
  - watches docs markdown, Pages scripts, and static assets
  - re-syncs generated docs HTML, CSS, and images into `pages/dist/` on changes
  - serves the app at `http://localhost:8000`
- `bb pages-build`
  - writes the static site into `pages/dist/`
- `bb pages-clean`
  - removes `pages/dist/` and `pages/.shadow-cljs/`

## Structure

- [deps.edn](/Users/fumi/ws/hobby/bisql/pages/deps.edn)
  - classpath settings for the Pages sub-app
- [shadow-cljs.edn](/Users/fumi/ws/hobby/bisql/pages/shadow-cljs.edn)
  - `shadow-cljs` configuration for Pages
- [static/index.html](/Users/fumi/ws/hobby/bisql/pages/static/index.html)
  - HTML template used to generate route-specific pages
- [static/playground.css](/Users/fumi/ws/hobby/bisql/pages/static/playground.css)
  - shared styling for Docs and Playground
- [../scripts/pages/sync.bb](/Users/fumi/ws/hobby/bisql/scripts/pages/sync.bb)
  - one-shot sync for docs catalogs, route HTML, CSS, and images
- [../scripts/pages/dev.bb](/Users/fumi/ws/hobby/bisql/scripts/pages/dev.bb)
  - polling watcher used by `bb pages-dev`
- [scripts/generate_docs_catalog.bb](/Users/fumi/ws/hobby/bisql/pages/scripts/generate_docs_catalog.bb)
  - builds the Docs catalog from `docs/*.md`
- [scripts/generate_examples_catalog.bb](/Users/fumi/ws/hobby/bisql/pages/scripts/generate_examples_catalog.bb)
  - converts rendering examples into Pages data
- [scripts/generate_route_html.bb](/Users/fumi/ws/hobby/bisql/pages/scripts/generate_route_html.bb)
  - writes route-specific `index.html` files

## Outputs

`bb pages-build` mainly generates:

- `pages/dist/`
  - deployable static site output
- `pages/generated/`
  - generated code for docs and examples catalogs

`pages/generated/` is build output and is not committed.

## Note

[static/img/bisql-social.*](static/img/bisql-social.png) is generated with [GitHub Socialify](https://socialify.git.ci/hatappo/bisql?description=1&language=1&logo=https%3A%2F%2Fraw.githubusercontent.com%2Fhatappo%2Fbisql%2Frefs%2Fheads%2Fmain%2Fdocs%2Fimg%2Fbicycle.svg&name=1&pattern=Circuit%20Board&theme=Light).
