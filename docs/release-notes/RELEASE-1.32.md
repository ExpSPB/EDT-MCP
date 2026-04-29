# EDT-MCP — Release 1.32.0

Phase 2 of the extension framework parity initiative. **Three new tools.** Tool count
50 → 53.

## Contents

- [`get_form_structure` — JSON tree of a managed form](#get-form-structure)
- [`get_object_help` — embedded help in markdown](#get-object-help)
- [`export_object` — spike for .epf / .erf binary build](#export-object)

---

<a name="get-form-structure"></a>
## `get_form_structure` — JSON tree of a managed form

**As-was.** `get_form_screenshot` returned a PNG. Useful for human review,
useless for programmatic walks. To navigate a form structure agents had to
parse `Form.form` XML directly - error-prone with EDT's evolving schema.

**As-now.** New tool emits a structured JSON tree by walking
`Form.getItems()` recursively via reflection (reusing `BmFormHelper`'s
already-loaded form-model classes). Pagination via `depth`, `subtree`,
`maxElements` for forms with hundreds of elements.

```json
{
  "formPath": "Catalog.Products.Forms.ItemForm",
  "formFqn": "Catalog.Products.Form.ItemForm.Form",
  "emitted": 47,
  "limit": 500,
  "depth": 5,
  "root": {
    "type": "Form",
    "name": "ItemForm",
    "items": [
      {
        "type": "FormField",
        "name": "Name",
        "properties": { "dataPath": "Object.Name", "visible": true }
      },
      {
        "type": "FormGroup",
        "name": "ButtonsCommandPanel",
        "properties": { "kind": "CommandBar" },
        "items": [
          {
            "type": "Button",
            "name": "PostAndClose",
            "properties": { "commandName": "PostAndClose" }
          }
        ]
      }
    ]
  }
}
```

Per-element properties extracted opportunistically: `dataPath`, `kind`,
`commandName`, `representation`, `childrenGroup`, `visible`, `enabled`. When a
property is absent on the element's EClass, it is silently omitted - no
exceptions during the walk.

Use `subtree=GroupName` to drill into one part of the form without dumping the
whole tree. Use `depth=1` for an outline scan first, then re-issue with a
target subtree at deeper levels.

The existing `get_form_screenshot` tool is unchanged - still returns PNG.

---

<a name="get-object-help"></a>
## `get_object_help` — embedded help in markdown

**As-was.** No way to read the embedded "?" help on metadata objects without
opening EDT manually. Agents lost the business intent baked into the
configuration's help pages.

**As-now.** New tool reads embedded help and returns Markdown by default
(via the bundled `CopyDown` HTML→Markdown converter, same as
`get_symbol_info`).

Two-tier strategy:
1. **BM API first.** Tries `MdObject.getHelp()` reflectively; iterates
   `Help.getPages()` collecting `(language, content)` pairs.
2. **Disk fallback.** Scans `src/<Plural>/<Name>/Help/*.html` and treats each
   file's stem as the language code.

The chosen path is reported in FrontMatter:

```yaml
source: bm    # or "disk", or "none"
pages: 2
synonym: Sales Order
```

Filter by language: `language=ru` keeps Russian pages, `language=en` keeps
English ones, `language=auto` (default) emits all.

Format options: `format=markdown` (default) / `format=html` (raw) /
`format=text` (tag-stripped plain text).

Use this *before* modifying an unfamiliar object - the help often documents
which standard processes the object participates in, what fields are
mandatory, and how it integrates with the rest of the configuration.

---

<a name="export-object"></a>
## `export_object` — spike for .epf / .erf binary build

**As-was.** No way to assemble a binary external data processor / report from
its EDT project. Manual GUI export only.

**As-now.** **Spike implementation.** The Phase 2 plan included a 2-day spike
to confirm the EDT export API. Findings:

- The CLI (`1cedtcli`) `export` command outputs project XML files, not .epf /
  .erf binaries. Its `build` command is validation-only.
- The internal export classes (`IExportPlatformService`, `IEpfExportService`
  and similar) are not exported by EDT 2025.2 / 2026.1 OSGi bundles.

The shipped tool probes a small set of candidate class names via reflection
and reports what it finds. When a candidate resolves the response is:

```json
{
  "status": "spike-pending",
  "discoveredApi": "com._1c.g5.v8.dt.export.IExportPlatformService",
  "methodHints": "exportToFile(2); ...",
  "hint": "EDT export API surface confirmed but the call signature needs a runtime spike against EDT 2026.1 before invocation. For now, use the EDT GUI: right-click the project, choose 'Export' -> 'External data processor / report'."
}
```

When no candidate resolves, the response is a clear error pointing the agent
at the GUI workflow.

Phase 4 (1.40) will replace this scaffold with a proper implementation once
the API contract is verified against a live EDT 2026.1 runtime, including the
`IRuntimeVersionSupport` integration for platform-version auto-detect.

**Why ship the spike now?** It registers the tool name (so existing prompts
and skills can reference `export_object`), surfaces actionable diagnostics
when the API is reachable (helping the next iteration land faster), and
documents the GUI fallback so agents do not block on the missing capability.

---

## Migration

No breaking changes.

- New tools register additively. Tool count 50 → 53.
- `get_form_screenshot` is unchanged - clients pinned to PNG output keep
  working.
- `export_object` returns informational JSON when the API is missing; treat
  any `status: spike-pending` response as "not yet supported - try GUI".

---

## Files

**New:**
- `tools/impl/GetFormStructureTool.java`
- `tools/impl/GetObjectHelpTool.java`
- `tools/impl/ExportObjectTool.java`
- `docs/test-scenarios/phase2.md` — six manual scenarios.

**Updated:**
- `McpServer.java` — three new `register(...)` calls.
- 15 build files: version bump 1.31 → 1.32.

---

## Build

`build.cmd` from a developer console (project rule: never let the AI run
`mvn` directly). Output zip ends up under
`mcp/repositories/com.ditrix.edt.mcp.server.repository/target/`.
