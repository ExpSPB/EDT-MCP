# EDT-MCP 1.41 — Closeout of 4 deferred ops blocks

Closes the four large deferred items from 1.40 / 1.40.x to reach
~95% RSV 4.0 parity. No new tools; existing tools deepen their reach.

## What's new

### Phase 1: `find_references` — bounded executor for the Pending pattern

The Variant A (Pending/runKey) flow shipped wired-up in 1.40.x but used an
unbounded `Executors.newCachedThreadPool`. 1.41 swaps that for a bounded
`ThreadPoolExecutor(corePoolSize=2, maxPoolSize=8, queue=20)` with
`CallerRunsPolicy` so a burst of parallel `find_references` calls cannot
spawn dozens of background threads.

Schema:
- `timeoutSeconds` is now clamped to the range `[5, 120]`. Values outside
  the range are silently coerced.
- `DEFAULT_TIMEOUT_MS = 30s` extracted as a constant; both the initial and
  retry call sites use it.

Note: `CallerRunsPolicy` blocks the calling MCP HTTP-handler thread on
queue saturation. Acceptable for the target audience of 1-2 concurrent AI
clients; load tests of 100+ overflow tasks may saturate the HTTP server.

### Phase 2: `export_object` — kind detection + Pending mechanism

Replaces the spike with a probe-and-export flow that handles both
`.epf` (ExternalDataProcessor) and `.erf` (ExternalReport) outputs.

- **Auto-detection**: `outputPath` extension determines the kind. When the
  extension is missing, the kind falls back to project nature
  (`V8ExternalDataProcessorNature` / `V8ExternalReportNature`); the proper
  extension is appended. Conflicting extension/nature returns
  `kindMismatch` with a structured tag.
- **Extension validation**: paths with non-`.epf` / non-`.erf` extensions
  (e.g. `.txt`) are rejected up-front to prevent garbage like
  `output.txt.epf`.
- **3 new probe candidates**: `EpfExportService`, `IMetadataExporter`,
  `IBmExporter` joined the existing 4 service candidates (total 7).
- **BM sync**: `BmExportHelper.forceExportAndWait` runs before the probe
  so freshly-edited objects flush to disk before the export service reads
  them.
- **Pending pattern**: same 2/8/queue=20 bounded executor as Phase 1 (own
  registry, `PendingExportRegistry`). `timeoutSeconds` and `runKey`
  parameters mirror `find_references`.
- **Unified `exportApiNotFound` tag** with `phase=probe` (no service
  found) or `phase=invocation` (service found but failed) so AI clients
  can branch on a single tag name.

### Phase 3: Forms ops — 3 deferred ops landed natively

`BmFormHelper` extensions (~+330 LOC):

- **`addFormAttributeColumn(form, parent, name, title, dataPath)`** —
  appends a column to a parent FormAttribute of type Table. Idempotent.
  Surfaces `formApiNotFound:` prefix when the EDT factory does not expose
  `createFormAttributeColumn`.
- **`addDynamicListAttributeAndTable(...)`** — creates the FormAttribute
  with DynamicList ExtInfo and a UI Table bound via dataPath. Best-effort
  wizard properties: `mainTable`, `autoSaveCustomization=true`,
  `dynamicDataRead=true`, `customQuery=false`.
- **`setupSettingsComposer(...)`** — DataCompositionSettingsComposer
  attribute + 2 UI tables (Settings + UserSettings). The response carries
  RU and EN BSL snippets ready to paste into `ProcedureOnCreateAtServer`,
  in both idempotent and fresh paths.

Helpers added: `probeFactoryCreate(name...)` (probes `FormFactory` then
`MdClassFactory`), `findFormAttributeByName`, `invokeSingleParamSetter`,
defensive unwrap of `InvocationTargetException` chains in
`executeFormOperation` so the `formApiNotFound:` marker is preserved
through the proxy layer.

### Phase 4: DCS — 13 deferred ops landed natively

`DcsWorkshopTool` extensions (~+595 LOC), all dispatch + applySchemaMutation
symmetric, no orphan entries.

#### DefaultSettings tree (4)

- `addSettingsTable` — Settings.Structure[] item
- `addSettingsChart` — Settings.Structure[] item
- `addSettingsVariant` — Schema.Variants[] item, with idempotency guard
  (silent duplicates corrupt the report)
- `addSettingsFilterGroup` — FilterItemGroup container for nested AND/OR

#### Order / Selection (3)

- `addSettingsOrder` — Settings.Order[] item with Asc/Desc
- `addSettingsSelectedField` — Settings.Selection[] item
- `removeSettingsSelectedField` — by field name

#### Parameters / Cascade (3)

- `setSettingsParameter` — overwrites Settings.DataParameters value by name
- `setOutputParameter` — sets Schema.OutputParameters value by name
- `removeSettingsItem` — universal cascade-remove via path
  `"Structure[0].Filter[2]"`. Walks dot-separated tokens, resolves through
  reflection, removes the leaf EObject; EMF auto-detaches the contained
  subtree.

#### Schema-level / Appearance (3)

- `addUserField` — `createUserFieldExpression` factory + Schema.UserFields
  list. Resolves the target list FIRST (the previous fallback to
  CalculatedFields silently produced ClassCastException inside the EList
  add).
- `removeConditionalAppearance` — by index, `target=schema|settings`
- `setDataSetFieldAppearance` — 6 properties (font, textColor, backColor,
  horizontalAlignment, verticalAlignment, border)

`factoryMissingTag(triedMethods)` surfaces `dcsFactoryMethodNotFound`
when the EDT EMF factory does not expose the method name(s); the AI gets
a structured tag with the array of attempted method names instead of a
generic `RuntimeException`.

`DCS_OP_ALIASES` in `EditMetadataTool` extended with 13 camelCase →
snake_case mappings so all of `edit_metadata operation=addSettingsTable`
(and the others) route correctly to `DcsWorkshopTool`.

## Tool count

`66` (no new tools in 1.41 — only deeper coverage of existing surfaces).

## Files changed

- `utils/PendingReferencesRegistry.java` — bounded executor (Phase 1)
- `tools/impl/FindReferencesTool.java` — clamps + DEFAULT_TIMEOUT_MS (Phase 1)
- `utils/PendingExportRegistry.java` — new (Phase 2)
- `tools/impl/ExportObjectTool.java` — kind detection + Pending (Phase 2)
- `utils/BmFormHelper.java` — 3 new ops + helpers (Phase 3)
- `tools/impl/EditMetadataTool.java` — 3 Form op methods + DCS aliases
  (Phase 3 + 4)
- `tools/impl/groups/FormsOperationGroup.java` — catalog (Phase 3)
- `tools/impl/DcsWorkshopTool.java` — 13 doXxx methods + helpers (Phase 4)
- `tools/impl/groups/DcsOperationGroup.java` — catalog (Phase 4)

## Risks and known limitations

- **DCS factory probes**: when EDT 2026.1 does not expose specific factory
  methods (e.g. `createSettingsTable`, `createSchemaVariant`), the op
  surfaces `dcsFactoryMethodNotFound` with a `triedMethods` array and the
  GUI fallback hint. Each op is independent — others continue to work.
- **Forms factory probes**: same pattern with `formApiNotFound`. The 3
  ops are independent.
- **`export_object` API discovery**: 7 candidate services covers known
  EDT namespaces. When all 7 fail, `exportApiNotFound` with `phase=probe`
  is returned. The user has to fall back to the EDT GUI (File → Export →
  External data processor / report).
- **`CallerRunsPolicy`** in both Pending registries blocks the MCP HTTP
  handler thread on queue saturation. Acceptable for desktop AI client
  loads (1-2 concurrent calls). Sized to 4/16 queue=50 is the next step
  if production telemetry justifies it.

## What is NOT in 1.41

- Template cell ops (3) — `ITemplateLayoutService` writer API still not
  public in EDT 2026.1
- HTTP services tooling, WSDL/SOAP, REST endpoint generator
- Removal of `RunYaxunitTestsTool` / `DebugYaxunitTestsTool` aliases
  (scheduled for 2.0)
- Compaction of `EditFormTool` / `DcsWorkshopTool` to ~200-LOC alias
  stubs (scheduled for 2.0)
