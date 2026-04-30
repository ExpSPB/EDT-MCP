# EDT-MCP 1.40 — edit_metadata + yaxunit_tests

Major release: edit_metadata covers ~64 operations across 7 groups.
Adds 1 new tool, ~40 new operations to `edit_metadata`, 4 defensive layers,
10 new helpers. Tool count 65 → 66.

## What's new

### `yaxunit_tests` (new tool)

Unified YAxUnit runner replacing the legacy two-tool surface
(`run_yaxunit_tests` + `debug_yaxunit_tests`).

- `mode=run` (default) — synchronous polling with Pending JSON when timeout
  elapses. Second call with same parameters fetches the JUnit report.
- `mode=debug` — launches in debug mode so breakpoints set via
  `set_breakpoint` / `launch_debugger` fire normally.
- `help=topics|writing|assertions|setup|events|advanced` — built-in markdown
  guidance (6 topics) for AI agents unfamiliar with YAxUnit.
- `updateBeforeLaunch=true` (default) — auto-syncs the infobase before
  launching, avoiding the "Update configuration?" modal that blocks headless
  test runs. Uses the new `ApplicationUpdater` with `FULL_UPDATE_REQUIRED`
  auto-switch.
- Filter parity: extensions, modules, tests, suites, tags, contexts
  (Server / Client / ExternalConnection).

Legacy `run_yaxunit_tests` / `debug_yaxunit_tests` remain registered for
backward compat with existing skills. Full removal scheduled for 2.0.

### `edit_metadata` — 7 groups, ~48 operations

#### Object group (8) — enhanced

- **`propertyMismatch` idempotency**: when `addObjectAttribute` /
  `addTabularSection` / `createObject` is called for an existing element with
  different property values, response now carries
  `[{name, requested, existing}]` mismatches instead of opaque `alreadyExists`.
- **`idempotentSkipTag`** when properties match — explicit "no-op success"
  signal for AI agents.
- **`compareProperties`** helper compares EMF getter values via reflection
  (boolean / enum / string / numeric coercion).

#### Specialized group (7) — implemented

- `addRegisterField` / `removeRegisterField` — fields of all 4 register kinds
- `addEnumValue` — batch supported
- `addSubsystemContent` / `removeSubsystemContent` — FQN-based via the new
  `BmSubsystemHelper`, idempotent
- `setRoleRight` — right-name resolution + canonical mapping (RU/EN aliases)
  through new `BmRightsHelper`. Mutation writer landed in 1.40.1.
- `setDefinedTypeTypes` — FQN validation via new `BmDefinedTypeHelper`.
  Mutation writer landed in 1.40.1.
- `addEventSubscriptionHandler` — with **defensive layer 3.8.1** handler
  auto-prefix.

#### Forms group (15 ops) — 11 implemented

- `createForm` — with **defensive layer 3.8.3** for Generic+empty layout
  (FormBaseSetup applies 11 base properties).
- `addFormAttribute`, `addFormCommand` / `addCommandHandler`, `setProperty` /
  `setFormItemProperty` — pre-existing.
- `addField`, `addGroup`, `addButton`, `addTable`, `addDecoration`,
  `removeFormItem` — delegated to the existing `EditFormTool` (operation
  names already match camelCase convention, no aliasing needed).
- `listPictures` — search across stock EDT pictures (via reflection probe of
  `StandardPictures`-style classes) + project's `CommonPicture.*` library.
- Deferred to 1.40.x patch (require BmFormHelper extensions):
  `addFormAttributeColumn`, `addDynamicListTable`, `addRadioButton`,
  `setupSettingsComposerOnForm`. Surface a graceful "lands in follow-up
  patch" message when called.

#### Templates group (4) — 1 implemented

- `addTemplate` — creates a Template MdObject with `templateType` resolved
  from English/Russian alias (Spreadsheet / Text / DCS / Appearance / Binary
  / HTML / Geo / Graph / ActiveDocument / AddIn). 10 supported types.
- `setTemplateCell`, `mergeTemplateCells`, `drawTemplate` — surface a
  graceful `mxlApiNotFound` error tag with a GUI-fallback hint when
  `ITemplateLayoutService` is missing on the EDT build. Cell-level writers
  land in 1.40.x patch.

#### Extensions group (5) — implemented

- `adoptObject` (with `recursive=true` option), `adoptObjects` (batch),
  `adoptChild`, `adoptFormItem`, `adoptModule` — all via the existing
  `BmExtensionHelper.attemptBorrow`. Per-object result for batch ops.
- **Defensive layer 3.8.2** in `opCreateObject` for CommonModule:
  `BmCommonModuleGuards` early-fails on `privileged=true` and
  `global=true+server=true` in extension projects (platform rejects on
  UpdateDBCfg).

#### DCS group (27 ops) — 14 implemented via delegate

Delegated to the existing `DcsWorkshopTool` (snake_case → camelCase convention,
aliasing in `EditMetadataTool.DCS_OP_ALIASES`):

`createReportSchema`, `repairReportSchema`, `addDataSet`, `removeDataSet`,
`addDataSetField`, `addSchemaParameter`, `setSchemaParameter`,
`removeSchemaParameter`, `moveSchemaParameter`, `addCalculatedField`,
`addTotalField`, `addConditionalAppearance`, `addSettingsGroup`,
`addSettingsFilter`.

13 DCS ops (addUserField, addSettingsTable, addSettingsChart,
addSettingsOrder, addSettingsSelectedField, removeSettingsSelectedField,
addSettingsVariant, setSettingsParameter, removeSettingsItem,
removeConditionalAppearance, setDataSetFieldAppearance, setOutputParameter,
addSettingsFilterGroup) land in 1.40.x via DcsWorkshopTool extension.

#### Common group (2) — implemented

- `moveItem` / `removeItem` — universal dispatchers routing by container FQN
  shape: form / DCS / metadata-collection. Returns `moveItemRouting` /
  `removeItemRouting` tag indicating which subsystem handled the call.

### Defensive layers 3.8 (all integrated)

These guards prevent platform-rejected configurations that previously only
surfaced at UpdateDBCfg time, requiring manual rollback:

- **3.8.1** EventSubscription handler auto-prefix `CommonModule.X.Method`
  (without prefix the runtime can't resolve the method, but the EDT visual
  editor doesn't flag it).
- **3.8.2** Extension CommonModule guards: `privileged=true` and
  `global=true+server=true` are rejected by the platform but pass headless
  creation; now early-fail in `opCreateObject` / `opSetObjectProperty`.
- **3.8.3** Generic+empty form 11 base properties: forms created without the
  groupHorizontalAlign / commandBar / commandInterface / etc. defaults
  refuse to open in the EDT editor and tables collapse at runtime.
- **3.8.4** CommonForm createObject auto inner form: `createObject
  CommonForm.X` creates both the wrapper and the inner Form with files
  alongside the `.mdo` (without nested `Forms/Форма/`).

## Architecture changes

### `OperationGroup` interface

New `tools/impl/groups/OperationGroup.java` interface with 7 implementing
classes (one per operation group). Each declares its operation names,
catalog (for help system), and dispatch.

In 1.40 these are skeleton proxies — they delegate to the legacy
`EditMetadataTool.opXxx()` methods. In 2.0 the implementation logic
migrates into the OperationGroup classes and EditMetadataTool sheds 1000+
LOC.

### `ApplicationUpdater` extracted

New `utils/ApplicationUpdater.java` — single source of truth for FULL /
INCREMENTAL infobase updates via `IApplicationManager`. Used by
`UpdateDatabaseTool`, `DebugLaunchTool`, and the new `YaxunitTestsTool`.

Adds `FULL_UPDATE_REQUIRED` auto-switch — the legacy
`DebugLaunchTool.updateDatabase` always sent INCREMENTAL, which fails on
structural metadata changes; the new path picks FULL when the platform
reports it.

`Result` record carries outcome + hint (`UPDATED` /
`BEING_UPDATED_BY_ANOTHER` / `ALREADY_UP_TO_DATE` / `FAILED` / etc.) so
callers don't parse strings.

### 10 new helper classes

- `BmRightsHelper`, `BmDefinedTypeHelper`, `BmSubsystemHelper`,
  `BmEventSubscriptionHelper` — Specialized group writers
- `BmFormCleanupHelper` — cascade form-item cleanup at attribute removal
- `BmCommonModuleGuards` — defensive layer 3.8.2
- `BmCommonFormPostCreate` — defensive layer 3.8.4
- `FormBaseSetup` — defensive layer 3.8.3
- `ApplicationUpdater` — yaxunit / debug / DB update single source
- `YaxunitHelp` — 6 markdown topics for the help system

All helpers follow the probe-pattern (Class.forName fallback) so missing
EDT API on a particular build degrades gracefully to error tag instead of
NoClassDefFoundError.

## Behavioral changes

- `JsonUtils.extractBooleanArgumentNullable` — new helper that returns
  `null` for unset boolean parameters, useful for guards distinguishing
  "not specified" from "explicitly false". Existing
  `extractBooleanArgument` delegates to it.
- `EditMetadataTool` description / help text updated — all 7 groups now
  marked `implemented in 1.40` (was "deferred to 1.34-1.39").
- `EditMetadataTool` `topic=availability` reports presence of all helper
  APIs (BmTemplateHelper, BmExtensionHelper, BmDcsHelper, BmRightsHelper)
  + intentions of all 4 defensive layers.

## Limitations / what's deferred to 1.40.x

- 4 Form ops requiring BmFormHelper extensions:
  addFormAttributeColumn, addDynamicListTable, addRadioButton,
  setupSettingsComposerOnForm. `addRadioButton` landed in 1.40.2 (delegate
  to addField with elementType=RadioButton); the other 3 land in 1.41.
- 3 Template cell ops requiring ITemplateLayoutService writers:
  setTemplateCell, mergeTemplateCells, drawTemplate. Architectural
  limitation in EDT 2026.1.
- 13 DCS ops requiring DcsWorkshopTool extensions. Land in 1.41.
- `setRoleRight` / `setDefinedTypeTypes` mutation writers - landed in
  1.40.1 ahead of schedule.
- Real transactional batch (one IBmModel.execute for multiple ops with
  rollback on error) — current 1.40 batch is non-transactional

## Compatibility

- **Non-breaking**: all existing tool surfaces preserved.
  `RunYaxunitTestsTool` / `DebugYaxunitTestsTool` / `EditFormTool` /
  `DcsWorkshopTool` continue to work.
- New `yaxunit_tests` is registered alongside the legacy two — agents can
  migrate at their own pace.
- `EditFormTool` / `DcsWorkshopTool` operations are now also reachable
  through `edit_metadata` (delegate). The legacy tools keep their original
  surface for backward compat; full deprecation happens in 2.0.

## Plan / cross-review

- Plan: `~/.claude/plans/EDT-MCP/1.40-edit-metadata-yaxunit-parity.md` (cross-reviewed by
  Sonnet, APPROVED after 6 iterations)
- Reference: upstream extension documentation
- Reference materials: `` (used
  for ApplicationUpdater, YAxUnit help structure, defensive layer
  rationale)
