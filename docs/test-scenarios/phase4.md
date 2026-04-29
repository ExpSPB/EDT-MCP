# Phase 4 — Manual Test Scenarios (v1.34.0)

Defensive layers — safer writes, clearer errors. Tool count unchanged (54).

---

## 1. BmExportHelper — wait-for-segment after write_module_source

**Setup** Any project. Open the EDT log view (Window → Show View → Error Log).

**Action**
```
write_module_source
  projectName=YourProject
  modulePath=CommonModules/Common/Module.bsl
  mode=append
  source="\n// phase 4 sync probe\n"
```

**Expected** Response includes:
```yaml
persistenceSyncOk: true
persistenceSyncMs: 100..2000
```

When `waitComputation` times out, `persistenceSyncDetail` reads
`forceExport ok, waitComputation timed out (X ms)`. The actual write still
landed - the wait-phase is purely advisory.

**Verification** EDT log shows no warnings about missing methods. The new
comment is visible in `update_database` and after EDT restart.

---

## 2. Standard attribute conflict in edit_metadata

**Setup** Any catalog you can modify (e.g. the `PhaseThreeProbe` from
Phase 3 scenarios).

**Action — English variant**
```
edit_metadata operation=addObjectAttribute
  projectName=...
  ownerFqn=Catalog.PhaseThreeProbe
  name=Code
```

**Expected** Error response:
```
Name 'Code' clashes with the standard attribute 'Code' - ...
```

Catalog is unchanged on disk.

**Action — Russian variant**
```
edit_metadata operation=addObjectAttribute
  ownerFqn=Catalog.PhaseThreeProbe name=Дата
```

Inside a Document or Catalog with a Date standard attribute, this returns
the same conflict error.

**Action — case-insensitive**
```
... name=CODE
... name=cOdE
```

All blocked.

---

## 3. Standard attribute conflict — fallback path

**Setup** A metadata type whose EClass does not expose
`getStandardAttributes()` (e.g. an obscure register or a custom type). The
guard falls back to the hard-coded English+Russian set in
`MetadataGuards.EN_STANDARD` / `RU_STANDARD`.

**Action**
```
edit_metadata operation=addObjectAttribute
  ownerFqn=AccountingRegister.Std name=LineNumber
```

**Expected** Error mentioning the fallback list:
```
Name 'LineNumber' matches a known platform-standard attribute - ...
```

---

## 4. Supplier lock detection (best-effort)

**Setup** Open a configuration that is on vendor support with editing
disabled. Common pattern: 1С standard configuration imported from a
distribution but kept "on support, no edit". If you don't have one, this
scenario is **skipped** — the guard silently passes when the API isn't
reachable, which is correct behaviour.

**Action**
```
edit_metadata operation=addObjectAttribute
  projectName=SupplierLockedConfig
  ownerFqn=Document.SalesOrder
  name=Whatever
```

**Expected** When EDT exposes a support-mode getter and the value contains
`NOT_ALLOWED` / `DENIED` / `DISABLED`, the response is:
```
Object 'SalesOrder' is on vendor support and editing is not allowed (mode=...) - Either enable editing in EDT (right-click -> Support -> Enable change), or work via a configuration extension (adoptObject + extension-side operations).
```

**Verification** Disable support in EDT → repeat the call → succeeds. Re-enable
→ blocked again. If no support API is reachable, the guard does NOT block; check
the EDT log to confirm the probe ran and reported back.

---

## 5. Phase 4 helpers — code-path coverage

**JUnit (when ready):**
- `BmExportHelperTest` — mock `IBmModelManager` and verify the reflection
  picks the `(IDtProject, List)` overload first, falls back to the
  per-FQN `(IDtProject, String)` overload, and degrades to a short sleep
  when `waitComputation` is missing.
- `MetadataGuardsTest` — exercise the standard-attribute path with both
  the EClass-backed list and the hard-coded fallback. Test the
  supplier-lock path with a stub object that returns enum-shaped values.

**Manual smoke test:** confirm no NPE on workspaces where `IBmModelManager`
is unavailable - all helpers degrade silently and log to the Error Log.

---

## What's still deferred

- **DCS direct save in extensions** — needs `DcsV8Serializer` reflection
  scaffolding from an upstream helper. Plan: 1.35.
- **`repairReportSchema`** — depends on the DCS direct save. Plan: 1.35.
- **DCS auto-validation before write** — depends on the DCS group landing.
  Plan: 1.39.
- **Module activation in extensions (Phase 4.7)** — depends on
  `BmExtensionHelper.adoptModule`. Plan: 1.40.

These are tracked in `edit_metadata operation=help topic=availability`,
which now also reports on `BmExportHelper.forceExportAndWait` reachability
implicitly (via the helper booleans).

---

## Release-readiness checklist

- [ ] All 4 active scenarios pass on a real EDT 2026.1 workspace.
- [ ] BmExportHelper is wired into `WriteModuleSourceTool` (no more inline
  `Method` reflection in that tool).
- [ ] Standard-attribute guard fires on both `edit_metadata
  addObjectAttribute` and the legacy `add_metadata_attribute` shortcut
  when applicable.
- [ ] Supplier-lock guard logs the probe outcome and does not block when
  no API is reachable.
- [ ] Build succeeds with `build.cmd`.
