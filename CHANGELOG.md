# Changelog

## Unreleased

## 0.4.0 - 2026-04-24

- Added generated Malli schema metadata for CRUD SQL and adjacent schema files.
- Added runtime Malli validation for query execution with configurable validation modes.
- Revised generated `update` and `upsert` templates around explicit `where` / `updates` / `inserts` inputs.
- Added Malli validation documentation and real-DB validation coverage for write queries.

## 0.3.0 - 2026-04-21

- Added SQL source metadata to generated query functions.
- Added a small expression language for `if` conditions.
- Introduced docs callouts and expanded the docs/pages workflow.

## 0.2.2 - 2026-04-17

- Added LIKE helper bindings and generated `starts-with` CRUD queries.
- Added query function listing and renamed generation commands.

## 0.2.0 - 2026-04-16

- Added the docs/playground site and GitHub Pages publishing workflow.
- Added compiled renderer forms and the renderer plan pipeline.
- Added generated `upsert` and `count` queries plus kebab-case adapter results.
- Added `separating` support for `for` blocks and inline `else` fragments.

## 0.1.0 - 2026-04-10

- Initial public release.
