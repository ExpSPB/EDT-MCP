# EDT-MCP — Release 1.33.0

First slice of the `edit_metadata` constructor (Phase 3 of the extension framework
parity initiative). **One new tool, five new helpers.** Tool count:
53 → 54.

## Contents

- [`edit_metadata` — single-entry metadata constructor](#edit-metadata)
- [Helpers — `BmObjectHelper`, `BmDcsHelper`, `BmTemplateHelper`,
  `BmExtensionHelper`, `BmRegisterHelper`](#helpers)
- [What's deferred and why](#deferred)

---

<a name="edit-metadata"></a>
## `edit_metadata` — single-entry metadata constructor

**Why a single tool with `operation=...`?** Designed for parity with the upstream
established API; one help system; one `dryRun` semantic; one `batch`
semantic; smaller footprint on the tool list. The seven existing focused
tools (`add_metadata_attribute`, `rename_metadata_object`,
`delete_metadata_object`, `edit_form`) stay as ergonomic shortcuts -
nothing breaks.

The dispatcher advertises ~59 operations across 7 groups via
`edit_metadata operation=help`. This release **fully implements the Object
group (8 operations)**:

| Operation | Effect |
|-----------|--------|
| `createObject` | New top-level metadata object. `objectType` accepts English singular ("Catalog") or Russian ("Справочник"). |
| `setObjectProperty` | Generic EMF-feature setter (e.g. `DescriptionLength=50`, `Hierarchical=true`). String→type coercion handles primitives and enums. |
| `addObjectAttribute` | Adds an attribute to Catalog/Document/etc. |
| `removeObjectAttribute` | Removes by name. |
| `addTabularSection` | Adds a TC. |
| `removeTabularSection` | Removes by name. |
| `addTabularSectionAttribute` | Adds a column to an existing TC. |
| `removeTabularSectionAttribute` | Removes a column from a TC. |

Every operation supports `dryRun=true`. Inside the BM transaction the
dispatcher applies the change and then aborts with a sentinel exception,
so the model is never persisted. The response distinguishes preview from
real apply via the `dryRun` field.

```json
{
  "operation": "edit_metadata",
  "ok": true,
  "message": "Dry run: Catalog.PhaseThreeProbe would be created.",
  "dryRun": true
}
```

`operation=help` returns a markdown catalog. `operation=help topic=workflow`
shows the typical `createObject → addObjectAttribute → addTabularSection →
addTabularSectionAttribute → setObjectProperty` chain.
`operation=help topic=availability` introspects the reflection probes for
DCS / Spreadsheet / Adopt service classes on the live EDT runtime, so AI
agents can preflight the deferred groups.

---

<a name="helpers"></a>
## Helpers

All five helpers are reflection-based and version-guarded - they probe the
EDT runtime at first use and cache the result.

- **`BmObjectHelper`** (~360 lines, *implemented*). Owns the BM-transaction
  boilerplate: project resolution, `IBmTransaction` management, dry-run
  rollback, and reflection helpers (`getAttributes`, `getTabularSections`,
  `findByName`, `setProperty` with coercion, `createObject` via
  `MdClassFactory`, `addToConfiguration`).
- **`BmRegisterHelper`** (~80 lines, *skeleton*). Reflection accessors for
  `Dimensions`, `Resources`, `EnumValues`. Operations land in 1.34.
- **`BmDcsHelper`** (~70 lines, *probe-only*). Resolves
  `com._1c.g5.v8.dt.dcs.model.DcsFactory` /
  `DataCompositionSchema`. Reports availability via `isAvailable()`. ~22
  operations land in 1.39.
- **`BmTemplateHelper`** (~100 lines, *probe-only*). Probes three candidate
  spreadsheet-model packages
  (`com._1c.g5.v8.dt.spreadsheet.model.SpreadsheetDocument` and two
  alternatives). 4 operations land in 1.40 once the API is confirmed.
- **`BmExtensionHelper`** (~95 lines, *probe-only*). Probes four candidate
  packages for the adoption service (`IMdAdoptObjectsService`). 5 operations
  land in 1.40 alongside the supplier-lock guard.

The skeleton helpers expose `deferredMessage(op)` - the dispatcher uses it
to return precise, actionable errors that name the missing API and suggest
the GUI fallback. This avoids the "tool exists but does nothing" dead end.

---

<a name="deferred"></a>
## What's deferred and why

| Group | Ops | Status | Target |
|-------|-----|--------|--------|
| Objects | 8 | **Implemented** | 1.33 |
| Specialized (RegisterField, EnumValue, SubsystemContent, RoleRight, DefinedTypeTypes, EventSubscription) | 7 | Deferred | 1.34 |
| Forms (createForm, addFormAttribute, setProperty, etc.) | 9 | Deferred (use `edit_form` for the basic 6) | 1.35-1.36 |
| Templates (MXL) | 4 | Deferred | 1.40 (after spreadsheet API confirmed) |
| Extensions (adopt*) | 5 | Deferred | 1.40 (after adopt service confirmed) |
| DCS | 22 | Deferred | 1.39 |
| Common (moveItem, removeItem) | 2 | Deferred | 1.36 |

The catalog of 59 operations is announced by `operation=help` from day one,
so AI agents and prompts can reference any operation name; the dispatcher
returns a clear deferred response for the unimplemented ones rather than
"unknown operation".

Why split: 59 operations × proper reflection wiring × per-type quirks
(Catalog vs ChartOfAccounts vs Register) = ~3 weeks of full-time work.
Shipping the dispatcher and the most-used 8 ops first lets prompts adopt
the API surface immediately while the rest land incrementally without
breaking changes.

---

## Migration

No breaking changes.

- New tool registers additively. Tool count 53 → 54.
- Existing focused tools are unchanged - prompts that call
  `add_metadata_attribute`, `rename_metadata_object`,
  `delete_metadata_object`, or `edit_form` keep working.
- `edit_metadata` is a superset, not a replacement; both APIs coexist.

---

## Files

**New:**
- `tools/impl/EditMetadataTool.java` (~600 lines).
- `utils/BmObjectHelper.java` (~360 lines).
- `utils/BmRegisterHelper.java` (~80 lines, skeleton).
- `utils/BmDcsHelper.java` (~70 lines, probe).
- `utils/BmTemplateHelper.java` (~100 lines, probe).
- `utils/BmExtensionHelper.java` (~95 lines, probe).
- `docs/test-scenarios/phase3.md` — 8 manual scenarios.

**Updated:**
- `McpServer.java` — `register(new EditMetadataTool())`.
- 15 build files: version bump 1.32 → 1.33.

---

## Build

`build.cmd` from a developer console (project rule: never let the AI run
`mvn` directly). Output zip ends up under
`mcp/repositories/com.ditrix.edt.mcp.server.repository/target/`.
