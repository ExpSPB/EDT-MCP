# Phase 2 — Manual Test Scenarios (v1.32.0)

Three new tools land this phase: `get_form_structure`, `get_object_help`,
`export_object`. Tool count: 50 → 53.

---

## 1. get_form_structure — basic walk

**Setup** Open a project that has a managed form, e.g. `Catalog.Products` with
`Forms.ItemForm`.

**Action**
```
get_form_structure
  projectName=YourProject
  formPath=Catalog.Products.Forms.ItemForm
```

**Expected**
- JSON envelope `{ formPath, formFqn, emitted, limit, depth, root }`.
- `root` is an object with `type: "Form"`, optional `name`/`title`, and an
  `items` array nested up to depth 5.
- Form items have `type` matching their EClass (`FormGroup`, `FormField`,
  `Button`, `Table`, `Decoration`, etc.).
- Buttons carry `properties.commandName`, fields carry `properties.dataPath`,
  groups carry `properties.kind` when available.

**Verification** Compare with the EDT form editor visually: the JSON tree
order matches the form layout order top-to-bottom.

---

## 2. get_form_structure — depth and subtree

**Setup** Same form, prefer one with nested groups.

**Action 1** Top-level only:
```
get_form_structure ... formPath=... depth=1
```

**Expected** `root.items[]` exists, but each item lacks an `items` array
(walk capped at depth 1).

**Action 2** Drill into one branch:
```
get_form_structure ... formPath=... subtree=ButtonsCommandPanel
```
(Replace `ButtonsCommandPanel` with a real group/page name from action 1's output.)

**Expected** `root.name == "ButtonsCommandPanel"` (or matching name), tree
rooted at the chosen subtree.

**Action 3** maxElements cap:
```
get_form_structure ... formPath=... maxElements=10
```

**Expected** `emitted <= 10`, `truncated: true` in envelope when the form has
more than 10 elements.

---

## 3. get_form_structure — non-existent form / subtree

**Action 1** Wrong form path:
```
get_form_structure ... formPath=Catalog.NoSuchObject.Forms.ItemForm
```

**Expected** Error mentioning "Form not found by FQN" and the BM-canonical
form FQN suggestion.

**Action 2** Wrong subtree name:
```
get_form_structure ... formPath=Catalog.Products.Forms.ItemForm subtree=DoesNotExist
```

**Expected** Error mentioning "Subtree element not found by name".

---

## 4. get_object_help — object with embedded help

**Setup** Open a project containing a metadata object with built-in help. The
1С standard configurations (УТ / БП / ЗУП / ERP) carry rich help on most
documents and catalogs. As fallback, create a small test object and add an
HTML file at `src/Catalogs/MyCatalog/Help/ru.html`.

**Action**
```
get_object_help
  projectName=YourProject
  objectName=Document.SalesOrder
```

**Expected** Markdown response with FrontMatter:
- `source: bm` (BM API path used) or `source: disk` (fallback file scan).
- `pages >= 1`.
- Markdown body containing converted help text (bold/lists/links preserved
  via CopyDown).

**Action 2** Switch language:
```
get_object_help ... language=ru
```

**Expected** Only Russian-language pages in response. With `language=en`
on a Russian-only object: empty pages list and "Help not available".

**Action 3** Format options:
```
get_object_help ... format=html
get_object_help ... format=text
```

**Expected** Raw HTML / plain text variants respectively.

---

## 5. get_object_help — object without help

**Setup** Any common module or simple report without help.

**Action**
```
get_object_help
  projectName=YourProject
  objectName=CommonModule.Common
```

**Expected** Response contains a "# Help not available" body, plus the
synonym/comment fields if present in metadata. No error.

---

## 6. export_object — spike pending behavior

**Setup** Any external data processor (.epf) project in workspace.

**Action**
```
export_object
  projectName=MyExtProcessor
  outputPath=C:/build/MyExtProcessor.epf
```

**Expected** One of two responses:
1. `status: spike-pending` JSON with `discoveredApi: "<some EDT class>"` and
   the `methodHints` listing public method signatures - means the EDT API
   surface is reachable but the call signature still needs runtime
   verification (planned for 1.40).
2. Error response with `EDT export API not available in this EDT version` -
   means none of the probed candidate classes resolved. Manual fallback via
   EDT GUI is required.

**Verification** Either response shape is acceptable for 1.32 - the tool is
documented as best-effort. Confirm:
- `outputPath` parameter is required (omitting returns a clear error).
- The agent gets actionable text on the GUI fallback when API is missing.

This scenario should be re-tested in Phase 4 (1.40) after the proper export
API integration lands.

---

## Release-readiness checklist

- [ ] Scenarios 1-6 pass.
- [ ] `get_form_structure` registered in tool list (53 tools total).
- [ ] `get_object_help` registered.
- [ ] `export_object` registered.
- [ ] No regressions in existing `get_form_screenshot` (PNG output unchanged).
- [ ] Build succeeds with `build.cmd`.
