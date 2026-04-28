# EDT-MCP 1.37 — Inventory closeout + deferred features merge

Production-ready release: closes RSV 3.7 inventory gaps and merges all
previously deferred 1.36 / 1.37+ features into a single drop. Tool count
unchanged at 57.

## Highlights

### Structured error tags (foundation)

Every mutation tool now surfaces machine-readable fields next to the
`error` string. AI agents can branch on tag presence instead of parsing
human-readable error text.

Tags surfaced:
- `supportLock` — vendor-supported object, editing not allowed.
- `standardAttributeConflict` — candidate name shadows a platform-standard
  attribute (e.g. `Code`, `Description`, `Period`).
- `alreadyExists` / `notFound` — idempotency markers for create / remove
  operations.
- `queryValidation` / `expressionValidation` — Xtext-based DCS query and
  expression validation.
- `fontColorGuard` — appearance value passed as JSON object/array instead
  of `Name=Value` string (RSV's lesson).
- `mxlApiNotFound` / `adoptServiceNotFound` / `exportServiceNotFound` —
  EDT API not reachable in this version.
- `autoBorrowed` / `autoBorrowSkipped` — extension auto-borrow telemetry.

### Guards apply-everywhere

`MetadataGuards.checkSupplierLock` runs automatically inside
`BmObjectHelper.executeWriteOnObject` for every mutation operation.
Caller-specific guards (e.g. `checkStandardAttributeConflict`) are
attached via the new `PreExecuteCheck` callback. The legacy
`AddMetadataAttributeTool` was wired through the same guard chain.

### DCS top-5 ops actual implementation

`dcs_workshop` no longer returns "impl pending" placeholders. Twelve ops
work end-to-end through reflection on the DCS schema EObject:
- `add_dataset` (+ auto query validation), `remove_dataset` (cascades
  calculated/total fields referencing the removed set).
- `add_field`, `add_parameter` / `set_parameter` / `remove_parameter` /
  `move_parameter`.
- `add_calculated_field` / `add_total` (+ auto expression validation).
- `add_appearance` (+ font/color guard), `add_grouping`, `add_filter`.

### Auto-validation

New `QlValidator` wraps the EDT QL/DCS Xtext language. `add_dataset`
validates `queryText` before opening the BM transaction; expression-
carrying ops validate their `expression` argument. Both can be disabled
via `validate_query=false` / `validate_expression=false`.

### Help topics enrichment

- `edit_metadata`: `composerWorkflow`, `matrixWorkflow`, `errorTags`.
- `dcs_workshop`: `dcsWorkflow`, `propertyValues`, `examples`,
  `errorTags`.
- `mxl_workshop` / `extension_workshop`: workflow + errorTags.

### Form constructor (4 ops)

`edit_metadata` ops `createForm`, `addFormAttribute`, `addFormCommand`,
`setFormItemProperty` are now wired through `BmFormHelper`. The latter
two work on existing form FQNs; `createForm` attaches a Form metadata
stub to the owner's `getForms()` collection.

### MXL workshop

`mxl_workshop` `create_template` is wired through `BmObjectHelper`. The
cell-level ops (`set_cell`, `merge_cells`, `draw`) probe the EDT layout
service; when the API is not reachable they surface `mxlApiNotFound`
with a GUI workaround hint.

### Extension workshop

`extension_workshop` ops `borrow_object`, `borrow_objects` (batch),
`borrow_child`, `borrow_form_item`, `borrow_module`, `list_borrowed`
attempt the EDT adopt service via OSGi + reflection. Failure modes
surface as `adoptServiceNotFound` or `adoptInvocationFailed` tags.

### Batch mode in edit_metadata

`edit_metadata batch=true operations=[...]` runs a sequence of
operations. JSON array and newline-separated formats are both accepted.
The 1.37 implementation is **sequential, not transactional**: failed
ops are recorded but earlier successes are committed. Atomic rollback
is on the 1.38 backlog.

### ExportObjectTool finalization

`export_object` now attempts a reflection-based invocation against the
discovered EDT export service. Failures surface as
`exportServiceNotFound` with a GUI workaround hint.

### Auto-borrow related objects

In extension projects, `addObjectAttribute type=CatalogRef.X` triggers
an automatic borrow of `Catalog.X` before adding the attribute.
Toggleable via `auto_borrow=true` (default). Telemetry surfaces as
`autoBorrowed` / `autoBorrowSkipped` tags.

## What is NOT in 1.37

- Module activation in `WriteModuleSourceTool` — already implemented in
  earlier releases.
- License / role / server-update layers — out of scope from 1.35.
- Atomic transactional batch mode — deferred to 1.38.
- Layer-2 cell editing for MXL — depends on the EDT layout service that
  is not stable across versions.

## Risks

- MXL / Extension / Export probe-based ops degrade gracefully but rely
  on EDT's internal API. If a probe fails on the real EDT 2026.1, the
  1.38 followup fixes it after a real probe in the running runtime.
- `auto_borrow` is depth=1 (does not cascade transitive references).

## Verification

1. `build.cmd` produces `1.37` zip artifact (~1.6-1.8 MB).
2. `addObjectAttribute name=Code` returns `standardAttributeConflict`
   tag.
3. `dcs_workshop add_dataset` with bad SQL returns `queryValidation`.
4. `extension_workshop borrow_object` returns `borrowed` tag on success
   or `adoptServiceNotFound` on probe failure.
5. `mxl_workshop create_template` succeeds; `set_cell` returns
   `mxlApiNotFound`.
6. Help topics render for all enrichment topics.
