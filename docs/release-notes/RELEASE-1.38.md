# EDT-MCP 1.38 — Architecture & analysis pack

Adds 4 architecture / static-analysis tools that operate on top of the BM
semantic index and Xtext AST. Tool count 57 → 61.

## What's new

### `dependency_graph`

Build a dependency graph between metadata objects and / or BSL modules.

- Levels: `metadata` (Document → Catalog edges via reference attrs),
  `modules` (CommonModule.A → CommonModule.B via call graph), `mixed`.
- Formats: `json` (structured), `mermaid`, `plantuml`, `dot`.
- Caps: `maxNodes` (default 200), `maxEdges` (default 500). On hit returns
  partial graph with `truncated=true` / `partial=true` flags.
- Direction: `in` / `out` / `both`.
- Cycle detection via Tarjan SCC algorithm in JSON output.

### `detect_query_anti_patterns`

Static analyzer for 1C query anti-patterns. Regex-based extraction with
documented limitations (no `ЗагрузитьТекстЗапроса` resolution).

Rules:
- `SELECT_STAR` — `ВЫБРАТЬ *` (warning)
- `NO_WHERE_ON_LARGE_TABLE` — query without WHERE on Catalog/Document/Register (warning)
- `VIRTUAL_TABLE_PARAMS` — virtual table call without parameters (warning)
- `CROSS_JOIN_NO_CONDITION` — CROSS JOIN without ON (error)
- `NESTED_QUERY_DEPTH` — subquery depth ≥ 3 (warning)
- `SUBQUERY_IN_SELECT` — subquery in SELECT clause (warning)
- `QUERY_IN_LOOP` — query inside BSL loop, N+1 problem (error)

### `project_metrics`

Sequential pipeline collecting comprehensive metrics:
- objects by type
- modules: count, total LOC, average LOC
- methods: count, average / max cyclomatic complexity, complex hotspots (>15)
- errors: error / warning / info / codeStyle
- tests: YAXUnit detection, test modules / methods
- forms: count, total items, large forms (>100 items)
- debt: long methods, high complexity, too many parameters

Returns `partial=true` with `unscannedModules` list when timeout hit
(default 60 sec).

### `compare_configurations`

Diff two metadata configurations. 1.38 supports `mode=projects` (two open
EDT projects) and `mode=files` (two on-disk exports). VCS-aware modes
(`commits` / `branches` / `bm_vs_disk`) are deferred to 1.39 Phase F per
cross-review HIGH F3+F17.

Levels: `object` / `attribute` / `module` / `template`. Rename detection
via structural similarity heuristic (showRenames=true default).

## Architecture changes

- New helpers: `BmReferencesHelper` (BFS-friendly back/forward refs inside
  one BM read-task — avoids UI-thread deadlock), `BslCallGraphHelper`
  (module-level callgraph), `DependencyGraphBuilder` (multi-format renderer),
  `ProjectMetricsCollector` (sequential pipeline), `QueryAntiPatternRules`
  (10 rule strategies), `MetadataDiffEngine` (level-specific diff strategies).
- BFS in dependency_graph runs inside a single `IBmModel.executeReadonlyTask`
  — never calls `display.syncExec` inside a loop (HIGH cross-review fix F1+F6).
- Cyrillic node labels in Mermaid / PlantUML / DOT are quoted via `"..."`.

## Known limitations (documented)

- `detect_query_anti_patterns`: only catches inline `Запрос.Текст = "..."` and
  `|`-style continuations; `ЗагрузитьТекстЗапроса()` content not visible.
- `project_metrics` complexity is heuristic (regex over branching keywords),
  not full Xtext AST. Accurate enough for trend / hotspot detection.
- `compare_configurations level=form` not yet implemented (level=attribute
  covers the structural diff for forms collection).
- VCS-aware compare modes deferred to 1.39 — see 1.39 plan.

## Risks mitigated

| Risk | Fix |
|---|---|
| BFS deadlock UI-thread | BmReferencesHelper inside one read-task |
| BM read-transaction timeout | maxNodes / maxEdges caps + `truncated=true` |
| Xtext ResourceSet RAM leak | batchSize=50 cleanup in anti-patterns |
| compare commits/branches | deferred to 1.39, graceful error tag |
| project_metrics workspace lock | sequential pipeline, not parallel |

## Migration

No breaking changes. New tools opt-in via parameters.
