# Phase 3 — Manual Test Scenarios (v1.33.0)

First slice of `edit_metadata`. Tool count: 53 → 54. The Object group (8
operations) is fully wired. The remaining ~50 operations advertise themselves
through `operation=help` and return precise deferred-error responses; they
land incrementally in 1.34-1.39.

---

## 1. operation=help — full catalog

**Action**
```
edit_metadata operation=help
```

**Expected** Markdown index listing 7 groups (Objects, Specialized, Forms,
Templates, Extensions, DCS, Common) with the Object group marked as
"implemented in 1.33". Topics list at the bottom: `workflow`, `types`,
`availability`.

---

## 2. operation=help topic=availability

**Action**
```
edit_metadata operation=help topic=availability
```

**Expected** Plain-text status per group:
- Objects: implemented
- Templates: deferred + boolean "Spreadsheet API present?"
- Extensions: deferred + boolean "Adopt service present?"
- DCS: deferred + boolean "DCS API present?"

Use this scenario to confirm the reflection probes resolve the right packages
on the live EDT 2026.1 runtime - the booleans should mostly read `true`.

---

## 3. createObject — dryRun then real

**Setup** Any open project where you have permission to create new metadata.
Avoid touching production configs — make a scratch project first.

**Action 1 — dry run**
```
edit_metadata
  operation=createObject
  projectName=ScratchProject
  objectType=Catalog
  name=PhaseThreeProbe
  dryRun=true
```

**Expected** `success=true`, `message` mentions "Dry run". The catalog does
**not** appear in the EDT navigator after refresh.

**Action 2 — real**
```
edit_metadata operation=createObject projectName=... objectType=Catalog name=PhaseThreeProbe
```

**Expected** Catalog appears under `src/Catalogs/PhaseThreeProbe/` and in the
EDT navigator after F5.

---

## 4. addObjectAttribute / addTabularSection / addTabularSectionAttribute

**Setup** Use the catalog created in scenario 3 (or any test catalog).

**Action**
```
edit_metadata operation=addObjectAttribute
  projectName=...  ownerFqn=Catalog.PhaseThreeProbe  name=Article

edit_metadata operation=addTabularSection
  projectName=...  ownerFqn=Catalog.PhaseThreeProbe  name=Specifications

edit_metadata operation=addTabularSectionAttribute
  projectName=...  ownerFqn=Catalog.PhaseThreeProbe
  tabularSectionName=Specifications  name=Quantity
```

**Expected** All three calls return `success=true`. The catalog now has an
`Article` attribute and a `Specifications` tabular section with a `Quantity`
column. Verify in EDT (refresh, expand the catalog).

---

## 5. setObjectProperty — coercion

**Action**
```
edit_metadata operation=setObjectProperty
  projectName=...
  ownerFqn=Catalog.PhaseThreeProbe
  propertyName=DescriptionLength
  propertyValue=50
```

**Expected** `success=true`. EDT object editor shows `Length=50` for the
description. The string `"50"` is coerced to the setter's `int` parameter
type by `BmObjectHelper.coerceValue`.

**Edge case** Misspelled property:
```
edit_metadata operation=setObjectProperty
  ... propertyName=DescriptiunLength propertyValue=50
```
Expected error: `Property 'DescriptiunLength' not found on Catalog`.

---

## 6. removeObjectAttribute — error path

**Action**
```
edit_metadata operation=removeObjectAttribute
  projectName=...
  ownerFqn=Catalog.PhaseThreeProbe
  name=NoSuchAttribute
```

**Expected** Error mentioning "Attribute not found". File on disk is
unchanged (no failed-write artifacts).

**Action 2** — remove the existing attribute:
```
edit_metadata operation=removeObjectAttribute
  projectName=... ownerFqn=Catalog.PhaseThreeProbe name=Article
```

**Expected** `success=true`. EDT shows the attribute is gone after refresh.

---

## 7. Deferred groups — graceful errors

**Action 1 — DCS**
```
edit_metadata operation=createReportSchema
  projectName=... ownerFqn=Report.Sample
```

**Expected** Error string mentioning "DCS operation 'createReportSchema'
is not yet implemented in this build. Planned for the 1.40 release. DCS
API is reachable" (or "NOT reachable" depending on runtime).

**Action 2 — Extensions**
```
edit_metadata operation=adoptObject
  projectName=... ownerFqn=Catalog.Foo
```

**Expected** Error mentioning the Adopt service availability probe.

**Action 3 — Templates**
```
edit_metadata operation=addTemplate ...
```

**Expected** Error citing the spreadsheet-model probe result.

---

## 8. Unknown operation — fuzzy suggestion

**Action**
```
edit_metadata operation=createObjct
```

**Expected** Error response mentioning "Unknown operation" and suggesting
`createObject` (substring match by `suggest`).

---

## Release-readiness checklist

- [ ] All 8 scenarios pass on a real EDT 2026.1 workspace.
- [ ] `edit_metadata` registered (tool list now reports 54 tools).
- [ ] `operation=help topic=availability` reports the helper booleans
  correctly for the runtime.
- [ ] No regressions in `add_metadata_attribute`, `rename_metadata_object`,
  `delete_metadata_object`, `edit_form` (these remain as ergonomic shortcuts).
- [ ] Build succeeds with `build.cmd`.
