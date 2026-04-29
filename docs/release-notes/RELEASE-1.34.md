# EDT-MCP — Release 1.34.0

Phase 4 of the extension framework parity initiative — defensive layers. **Two new
helpers, no new tools.** Tool count unchanged (54).

## Contents

- [`BmExportHelper` — wait-for-segment after every write](#bmexporthelper)
- [`MetadataGuards` — standard-attribute conflict + supplier lock](#metadataguards)
- [`WriteModuleSourceTool` refactored on top of `BmExportHelper`](#refactor)

---

<a name="bmexporthelper"></a>
## `BmExportHelper` — wait-for-segment after every write

**As-was.** Each tool that mutated BM data carried its own copy of the
`forceExport` reflection dance. `WriteModuleSourceTool` got one in 1.31;
`EditFormTool` had a different one for years. None of them waited for the
EDT derived-data segments (`EXP_O`, `EXP_B`, `FORM_EXT`) to finish
recomputing - so a fast follow-up read could miss the change.

**As-now.** New `utils/BmExportHelper.java` (~220 lines) provides:

```java
BmExportHelper.Result r = BmExportHelper.forceExportAndWait(manager, project, fqn);
// r.forceExportOk  — did the BM model accept the export call
// r.waitComputationOk — did the derived-data segments settle
// r.forceExportMs / r.waitComputationMs / r.totalMs
// r.error           — non-null on hard failure
```

Internally:
1. Resolves `IDtProject` via `IBmModelManager.getDtProject(name)` reflection.
2. Tries the `forceExport(IDtProject, List<String>)` overload first - lets EDT
   batch multiple FQNs into one call. Falls back to the per-FQN String overload.
3. Locates `waitComputation(...)` reflectively and feeds it the canonical
   `EXP_O` / `EXP_B` / `FORM_EXT` segment names with a 10s soft cap.
4. Fails gracefully when any step is unavailable - never throws past the
   helper boundary.

The pattern mirrors a similar pattern in upstream tooling, which is the
ground truth for what a robust write-then-sync loop looks like in EDT.

---

<a name="metadataguards"></a>
## `MetadataGuards` — standard-attribute conflict + supplier lock

**As-was.** AI agents can request `addObjectAttribute name=Code` on a
catalog and silently produce a configuration that won't compile. Same for
writes targeting an object on vendor support with editing disabled - the
write succeeds at the file level, then `update_database` fails.

**As-now.** New `utils/MetadataGuards.java` (~200 lines) exposes two
predicates:

```java
MetadataGuards.Verdict v = MetadataGuards.checkStandardAttributeConflict(owner, name);
if (v.blocked) { /* return v.error + " - " + v.hint; */ }
```

- **Standard attribute conflict.** First tries
  `MdObject.getStandardAttributes()` reflectively (catches
  configuration-specific tweaks like `UseStandardCommands`). Falls back to
  a hard-coded English + Russian set covering Ref / DeletionMark /
  Predefined / Code / Description / Owner / Parent / Date / Number /
  Posted / LineNumber / Recorder / Period and their RU equivalents.
- **Supplier lock.** Probes a small set of getter names
  (`getUserSupportMode`, `getSupportMode`, `getSupport`, `isOnSupport`)
  and inspects the returned enum/string for `NOT_ALLOWED` / `DENIED` /
  `DISABLED` markers. Returns the discovered API name in the verdict so
  callers can log it. **Does not block** when no API is reachable - the
  alternative would punish every project on EDT versions without the API.

Wired into `edit_metadata operation=addObjectAttribute` in this release.
The same guards land in `add_metadata_attribute` and other addAttribute-
shaped operations as they get reviewed.

---

<a name="refactor"></a>
## `WriteModuleSourceTool` refactored on top of `BmExportHelper`

**As-was.** The 1.31 release inlined a `Method.invoke()` chain to call
`forceExport`. Functional, but duplicated the same code we now have a
helper for, and never waited for derived data.

**As-now.** `forceExportModule(...)` is now a thin shim over
`BmExportHelper.forceExportAndWait`. The FrontMatter contract is unchanged
(`persistenceSyncOk`, `persistenceSyncMs`, `persistenceSyncDetail`), but
the timing now reflects both phases (force + wait). `Method` import dropped.

Net diff: ~80 lines removed from `WriteModuleSourceTool`, replaced with a
12-line delegation; the helper class lives once and benefits every future
tool that learns to call it.

---

## What's still deferred

This release wires the **first two** Phase 4 layers. The remaining ones
land in 1.35 / 1.39 / 1.40 once their prerequisites are in place:

| Layer | Depends on | Target |
|-------|-----------|--------|
| DCS direct save in extensions | `DcsV8Serializer` reflection scaffolding | 1.35 |
| `repairReportSchema` | DCS direct save | 1.35 |
| DCS query/expression validation before write | DCS group implemented | 1.39 |
| Module activation in extension (Phase 4.7) | `BmExtensionHelper.adoptModule` | 1.40 |

The roadmap is visible to AI agents through `edit_metadata operation=help
topic=availability` - the same `isAvailable()` probes used by the helpers.

---

## Migration

No breaking changes.

- New helpers register no tools.
- `WriteModuleSourceTool` API and FrontMatter contract are unchanged.
- `edit_metadata addObjectAttribute` now blocks two new error cases;
  callers should treat the new error strings as "fix the request and retry".

---

## Files

**New:**
- `utils/BmExportHelper.java`
- `utils/MetadataGuards.java`
- `docs/test-scenarios/phase4.md` — 5 manual scenarios.

**Updated:**
- `tools/impl/WriteModuleSourceTool.java` — refactored on `BmExportHelper`,
  dropped inline `Method` reflection.
- `tools/impl/EditMetadataTool.java` — `addObjectAttribute` now calls
  `MetadataGuards`.
- 15 build files: version bump 1.33 → 1.34.

---

## Build

`build.cmd` from a developer console (project rule: never let the AI run
`mvn` directly).
