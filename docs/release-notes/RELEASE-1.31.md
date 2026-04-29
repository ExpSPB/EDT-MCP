# EDT-MCP — Release 1.31.0

Phase 1 of the extension framework parity initiative. Quick wins on existing tools — no new
tool registrations this release. Tool count stays at 50; per-tool capability
grows.

## Contents

- [WriteModuleSourceTool — insertBefore / insertAfter modes](#insertbefore--insertafter)
- [WriteModuleSourceTool — validation feedback in response](#validation-feedback)
- [WriteModuleSourceTool — persistence sync after write](#persistence-sync)
- [WriteModuleSourceTool — 50% removal hard-stop](#fifty-percent-removal-hard-stop)
- [GetProjectErrorsTool — `scope=session` and refresh polling](#scope-session)
- [FindReferencesTool — `deep=true` produced-type labels](#deep-references)
- [GoToDefinitionTool — Levenshtein fuzzy fallback](#fuzzy-go-to-definition)

---

<a name="insertbefore--insertafter"></a>
## WriteModuleSourceTool — insertBefore / insertAfter modes

**As-was.** Five modes — `searchReplace`, `replace`, `append`, `replaceLines`,
`replaceMethod`. Inserting a fragment at a specific line required either:
- `replaceLines` with a single line and the new fragment glued to the original
  (lossy and noisy), or
- A read-modify-write cycle through `searchReplace` matching unique surrounding
  lines (brittle).

**As-now.** Two new modes:

| Mode | Effect |
|------|--------|
| `insertBefore` | Source is placed before line `N`; line `N` shifts to `N + sourceLines`. |
| `insertAfter`  | Source is placed after line `N`; line `N+1` shifts down. |

Schema gains `line` (1-based, integer). Out-of-range values return a clear error.
Both modes reuse the existing BSL syntax check (Procedure/EndProcedure pairs etc.).

```json
{
  "tool": "write_module_source",
  "projectName": "MyProject",
  "modulePath": "CommonModules/MyModule/Module.bsl",
  "mode": "insertBefore",
  "line": 42,
  "source": "// header for the next method\n"
}
```

---

<a name="validation-feedback"></a>
## WriteModuleSourceTool — validation feedback in response

**As-was.** After `write_module_source`, the agent had to call
`get_project_errors` separately to see if the new code compiled cleanly.

**As-now.** The response now embeds an EDT marker snapshot for the just-written
module, scoped to the file's owner FQN (resolved from `objectName` /
`modulePath`). Default behaviour is **on**; pass `validateAfterWrite=false` to
suppress in batch sequences.

The response gains FrontMatter counters and a markdown section:

```yaml
validationErrors: 0
validationWarnings: 1
validationCodeStyle: 0
validationHint: 1 warning(s) - review and decide.
```

```markdown
## Validation

### Warnings (1)
- L18 Метод 'НеСуществующаяПроцедура' не найден `bsl-method-not-found`
```

The shared logic lives in a new utility — `FileMarkers` — used by both
`WriteModuleSourceTool` and `GetProjectErrorsTool`'s `scope=object` path.

---

<a name="persistence-sync"></a>
## WriteModuleSourceTool — persistence sync after write

**As-was.** Plain `IFile.setContents()` worked, but EDT's BM index didn't always
catch up before the next read or `update_database`. On extension projects in
particular, freshly-written modules sometimes failed to deploy with "file not
found" until F5 / clean build.

**As-now.** After every successful write the tool calls
`IBmModelManager.forceExport(IDtProject, fqn)` (with a fallback to
`waitModelSynchronization`). Result lands in FrontMatter:

```yaml
persistenceSyncOk: true
persistenceSyncMs: 87
```

Failures are non-fatal — the file write itself succeeded — but are surfaced via
`persistenceSyncDetail` for diagnostics.

Phase 4.2 will replace this direct call with a generalized `BmExportHelper`
that also waits on the EXP_O / EXP_B / FORM_EXT segments.

---

<a name="fifty-percent-removal-hard-stop"></a>
## WriteModuleSourceTool — 50% removal hard-stop

**As-was.** A 30% removal triggered a non-blocking `protection` warning. There
was no upper safety net — an off-by-one in `replaceLines` could silently delete
hundreds of lines.

**As-now.** Two thresholds:
- `>30%` continues to emit the existing protection warning.
- `>50%` is a **hard error** unless the caller passes `confirmFullReplace=true`.

The error message is explicit:

```
Error: this change would remove 78% of the module (390 of 500 lines).
Pass confirmFullReplace=true to proceed, or use a more targeted mode
(replaceMethod, replaceLines, searchReplace) to change a smaller fragment.
```

Note that `mode=replace` is exempt — wholesale replacement is the explicit
intent of that mode.

---

<a name="scope-session"></a>
## GetProjectErrorsTool — `scope=session` and refresh polling

**As-was.** Without filters, the tool returned every marker in the workspace —
typically thousands on a stock 1С configuration. Agents had to filter by
`objects` to see anything actionable.

**As-now.** New `scope` parameter (default `session`):

| scope     | Meaning |
|-----------|---------|
| `session` | Only files modified in the current MCP session, via `SessionChangeTracker`. New default. |
| `object`  | Requires `objects=[...]`. Behaves as the legacy filter mode. |
| `project` | Whole project — auto-summarizes when total markers exceed 200. |
| `all`     | Every open project. |

Setting `objects=[...]` implies `scope=object` automatically.

Two new helpers:
- `fileFilter` — extra substring filter on `marker.getObjectPresentation()`.
- `waitForRefresh` (default true) — polls the marker stream up to 3×300 ms when
  the first read returns empty, helping when EDT publishes markers
  asynchronously after a fresh write.

---

<a name="deep-references"></a>
## FindReferencesTool — `deep=true` produced-type labels

**As-was.** Produced-type references (BSL referencing
`СправочникМенеджер.Products`, `СправочникСсылка.Products`, etc.) were collected
but rendered with a generic feature label — `Type: ...` — which hid the
specific kind of usage.

**As-now.** Pass `deep=true` to label each produced-type entry with the
specific kind extracted from its EClass:

```
Type[Reference]: dataPath
Type[Manager]: dataPath
Type[Selection]: dataPath
Type[Object]: dataPath
Type[Cache]: dataPath
Type[List]: dataPath
```

Useful for refactoring impact analysis — e.g. answering "is this catalog
consumed via its Object or via its Manager?".

---

<a name="fuzzy-go-to-definition"></a>
## GoToDefinitionTool — Levenshtein fuzzy fallback

**As-was.** When `go_to_definition` failed to resolve a symbol it offered
suggestions found by simple substring match. Typos that change a single letter
(`СообщитПользоателю` instead of `СообщитьПользователю`) returned no
suggestions.

**As-now.** `MetadataTypeUtils.findSimilarObjects` runs a substring pass first
(unchanged behaviour). When that pass returns zero candidates, it falls back to
a Levenshtein search with a length-scaled threshold — `max(2, len/5)` edits —
and ranks the survivors by distance. Top-5 suggestions are surfaced through the
existing `## Did you mean?` section.

No new public class; the helper is encapsulated inside `MetadataTypeUtils`. The
benefit transparently flows to every caller of `findSimilarObjects`, including
the not-found branch of `go_to_definition`.

---

## Migration

No breaking changes. All new schema parameters have safe defaults:
- `validateAfterWrite=true`
- `confirmFullReplace=false` (only matters at >50% removal)
- `scope=session` (was implicitly `all`; if you used to call
  `get_project_errors` with no filters and expect everything, pass
  `scope=project` or `scope=all` explicitly)
- `waitForRefresh=true`
- `deep=false`
- `line` is required only for `insertBefore` / `insertAfter`.

`get_project_errors` with `objects=[...]` still works exactly as before — the
explicit object list implies `scope=object`.

---

## Files changed

- `tools/impl/WriteModuleSourceTool.java` — 7 modes, `validateAfterWrite`,
  `confirmFullReplace`, `persistenceSync*` fields.
- `tools/impl/GetProjectErrorsTool.java` — `scope`, `fileFilter`,
  `waitForRefresh`; refactored to use the new `FileMarkers` helper.
- `tools/impl/FindReferencesTool.java` — `deep` parameter and produced-type
  classifier.
- `tools/impl/GoToDefinitionTool.java` — no direct change; benefits from
  upgraded `MetadataTypeUtils.findSimilarObjects`.
- `utils/FileMarkers.java` — **new**, ~200 lines.
- `utils/MetadataTypeUtils.java` — Levenshtein fallback in `findSimilarObjects`.
- `docs/test-scenarios/phase1.md` — **new**, 8 manual scenarios.

## Build

`build.cmd` from a developer console (project rule: never let the AI run
`mvn` directly). Output zip ends up under
`mcp/repositories/com.ditrix.edt.mcp.server.repository/target/`.
