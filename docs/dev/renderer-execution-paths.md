# Renderer Execution Paths

This document summarizes the three renderer execution paths that currently
exist in bisql and clarifies which layers are shared and which are platform-
specific.

## Overview

bisql currently has three execution paths:

1. Macro-driven code generation
2. Runtime `eval`-driven compilation
3. Interpreter-driven execution

They are intentionally not three unrelated implementations. The design goal is
to share the parsing and planning layers as much as possible and only diverge
at the final execution stage.

## Layer Model

The current compiler/runtime layering is:

1. Raw template
2. Analyzed template
3. Parsed template
4. Renderer plan
5. Renderer form or interpreter execution
6. Final rendered SQL + bind params

More concretely:

- `analyze-template`
  extracts declaration metadata and strips declaration blocks
- `parse-template`
  builds the parsed-template tree
- `renderer-plan`
  converts parsed-template into an execution-oriented plan
- `emit-renderer-form`
  generates CLJ renderer code from the plan
- `evaluate-renderer-plan`
  interprets the same plan directly

## Path Matrix

| Processing stage | Macro path | Runtime eval path | Interpreter path (CLJ) | Interpreter path (CLJS) |
| --- | --- | --- | --- | --- |
| Raw Template input | ✓ | ✓ | ✓ | ✓ |
| Declaration analysis (`analyze-template`) | ✓ | ✓ | ✓ | ✓ |
| Parsing (`parse-template`) | ✓ | ✓ | ✓ | ✓ |
| Execution planning (`renderer-plan`) | ✓ | ✓ | ✓ | ✓ |
| Final execution strategy | code generation | code generation | interpretation | interpretation |
| Renderer code generation (`emit-renderer-form`) | ✓ | ✓ | - | - |
| Compilation to executable renderer (`compile-renderer`) | macro expansion + Clojure compiler | `eval` | interpreter-backed fn | interpreter-backed fn |
| Direct plan execution (`evaluate-renderer-plan`) | - | - | ✓ | ✓ |
|  |  |  |  |  |
| Final rendering API | `render-compiled-query` | `render-compiled-query` | `render-query` / interpreter-backed renderer | `render-query` / interpreter-backed renderer |
| Main use today | `defrender`, `defquery` | low-level runtime compilation on CLJ | parity tests / shared semantics on CLJ | browser / playground path |

Read this table top to bottom:

- The first four stages are shared.
- The main split begins after `renderer-plan`.
- The first two columns are CLJ codegen paths.
- The last two columns are the same interpreter model shown separately for CLJ and CLJS.
- CLJ keeps both codegen and interpreter paths for parity testing.

## Path Details

### 1. Macro Path

This is the main path today.

Flow:

1. `load-query` / `load-queries`
2. `analyze-template`
3. `parse-template`
4. `renderer-plan`
5. `emit-renderer-form`
6. macro embeds renderer form
7. Clojure compiler produces executable function

Properties:

- No runtime `eval`
- Main production path on CLJ
- Best runtime performance characteristics
- Depends on Clojure code generation

### 2. Runtime Eval Path

This is the low-level runtime compilation path.

Flow:

1. `analyze-template`
2. `parse-template`
3. `renderer-plan`
4. `emit-renderer-form`
5. `compile-renderer`
6. `eval`
7. executable function runs

Properties:

- Shares the same code generation path as macros
- Useful for runtime-provided templates
- CLJ-only by construction
- Convenience path, not the main definition path

### 3. Interpreter Path

This is the platform-neutral execution path and is the foundation for CLJS and
browser usage.

Flow:

1. `analyze-template`
2. `parse-template`
3. `renderer-plan`
4. `evaluate-renderer-plan`
5. rendered SQL + bind params

On CLJS, `compile-renderer` now returns a reusable function backed by this
interpreter.

Properties:

- No code generation required
- No `eval`
- Suitable for CLJS/browser environments
- Shares parsed-template and renderer-plan semantics with CLJ paths

## Current Sharing Boundary

The important current sharing boundary is:

```clojure
raw template
-> analyze-template
-> parse-template
-> renderer-plan
```

After that point, execution splits:

- CLJ macro/runtime compilation:
  - `emit-renderer-form`
  - compiler or `eval`
- CLJS/interpreter:
  - `evaluate-renderer-plan`

This means the parser and the execution-oriented plan are shared, while the
final execution strategy differs by environment.

## Where Drift Can Still Happen

Even after introducing `renderer-plan`, there is still some drift risk.

Potential drift points:

- `emit-renderer-form` and `evaluate-renderer-plan` may interpret the same step
  differently
- error shaping may differ slightly between codegen and interpreter paths
- future step kinds must be implemented in both places

Current mitigation:

- renderer-plan shape is fixed by tests
- interpreter path is tested against `render-query`
- generated renderer path is also tested against `render-query`

## Current Status

The current status is:

- Parsed-template exists as the parser output
- Renderer-plan exists as the shared execution-oriented layer
- CLJ macro and runtime compilation use renderer-form generation
- CLJS uses interpreter-backed renderer compilation

This is not complete unification, but it is no longer three unrelated paths.
The shared contract is now explicit and testable.

## Likely Next Steps

Likely future work:

1. Add CLJS/browser integration around `bisql.engine/render-query`
2. Keep tightening parity tests between codegen and interpreter paths
3. Reduce remaining codegen/interpreter duplication where it is practical
4. Consider whether more execution semantics should move from codegen helpers
   into renderer-plan itself
