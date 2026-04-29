# Phase 1 — Manual Test Scenarios (v1.31.0)

Use-case-driven scenarios for the Phase 1 enhancements. Run each in EDT against
a real project (any open 1C configuration). Mark each one PASS / FAIL / N/A on
the release-readiness checklist.

For every scenario:
1. **Setup** describes the project state needed.
2. **Action** is the MCP call to make.
3. **Expected** is what should appear in the response.
4. **Verification** is how to confirm the change really happened.

> **Tip:** Open the EDT MCP server log (Eclipse → View → Console) before running
> scenarios. Persistence and validation events are logged at WARNING level when
> they fail.

---

## 1. WriteModuleSourceTool — `insertBefore` mode

**Setup**
- Open any project with a `CommonModule.MyTest` whose `Module.bsl` is at least
  100 lines long. (Create via `1c-meta-compile` if needed.)
- Read the module to confirm a stable line layout.

**Action**
```
write_module_source
  projectName=YourProject
  modulePath=CommonModules/MyTest/Module.bsl
  mode=insertBefore
  line=50
  source="// inserted by phase 1.1"
```

**Expected**
- Response status `success`.
- FrontMatter `linesBefore` is the original line count, `linesAfter` is
  `linesBefore + 1`.
- `persistenceSyncOk: true`.
- `validationErrors: 0` (or unrelated existing errors only).

**Verification**
- Re-read the module via `read_module_source`. Original line 50 must now be
  line 51, and line 50 must equal `// inserted by phase 1.1`.
- Editor in EDT (after F5 refresh) shows the same change.

---

## 2. WriteModuleSourceTool — `insertAfter` mode

**Setup** Same module as scenario 1. Optional: undo scenario 1 with another
write or `git checkout` to start clean.

**Action**
```
write_module_source
  ...
  mode=insertAfter
  line=50
  source="// inserted-after-50\n// second line"
```

**Expected**
- `linesAfter == linesBefore + 2`.
- Original lines 51..end shifted down by 2; original line 50 still at line 50.

**Verification** Re-read; lines 51 and 52 are the new content.

---

## 3. WriteModuleSourceTool — 50% removal hard-stop

**Setup** A module with at least 100 lines.

**Action** (deliberately destructive)
```
write_module_source
  ...
  mode=replaceLines
  lineFrom=1
  lineTo=80
  source="// new"
```
(80 of 100 lines removed = 80% — should be blocked.)

**Expected**
- `Error:` response containing the phrase `would remove 80% of the module`.
- Hint mentions `Pass confirmFullReplace=true to proceed`.
- File on disk is **unchanged** (verify mtime).

**Action 2** Repeat with `confirmFullReplace=true`.

**Expected** Write succeeds; `linesAfter` is `21` (1 new + remaining 20 from
lines 81..100); `protection` warning still emitted in FrontMatter as info.

---

## 4. WriteModuleSourceTool — validation feedback in response

**Setup** Module with no current errors.

**Action** Write code that EDT will flag as a warning:
```
write_module_source
  ...
  mode=append
  source="\nПроцедура BadCall() Экспорт\n    НеСуществующаяПроцедура();\nКонецПроцедуры\n"
```

**Expected**
- `validateAfterWrite` defaults to true.
- Response contains a `## Validation` section listing at least one warning
  about the missing procedure.
- `validationWarnings >= 1` in FrontMatter.
- `validationHint` contains the word `warning(s)`.

**Action 2** Re-run with `validateAfterWrite=false`.

**Expected** `validation*` fields absent; no `## Validation` section.

---

## 5. WriteModuleSourceTool — persistenceSync on extension module

**Setup** Open a project that is an extension (configuration extension, not
main config). Pick any extension's CommonModule.

**Action**
```
write_module_source
  projectName=MyExtension
  objectName=CommonModule.SomeModule
  mode=append
  source="\n// phase 1.3 sync\n"
```

**Expected**
- `persistenceSyncOk: true`.
- `persistenceSyncMs` reasonable (typically <500 ms; <2000 ms acceptable on
  cold caches).

**Verification** Without restarting EDT, run `update_database` (sync_database).
The new comment must apply without any "file not found" error — confirms BM
flushed the change to disk in time.

---

## 6. GetProjectErrorsTool — `scope=session`

**Setup** Open a configuration that has many existing markers (a typical 1С
configuration has hundreds). Wait for full validation to settle.

**Action 1** Without modifying any file:
```
get_project_errors
  projectName=YourProject
```

**Expected**
- Response is a `# No Session Changes` block (since no files were modified in
  this MCP session).
- No marker dump.

**Action 2** Run any `write_module_source` (e.g. scenario 4). Then immediately:
```
get_project_errors projectName=YourProject
```

**Expected** Markers returned only for the just-written file. Other modules in
the configuration are absent from the result, even if they have errors.

**Action 3** Override scope:
```
get_project_errors projectName=YourProject scope=project
```

**Expected** Full project marker scan. If total >200, the response is the
`# Too Many Project Markers` summary instead of a list.

---

## 7. FindReferencesTool — `deep=true`

**Setup** A configuration with at least one Catalog (e.g. `Catalog.Products`)
referenced from BSL via `СправочникМенеджер.Products` or
`СправочникСсылка.Products`.

**Action**
```
find_references
  projectName=YourProject
  objectFqn=Catalog.Products
  deep=true
```

**Expected** Each produced-type reference has a feature label of the form
`Type[Reference]: ...`, `Type[Manager]: ...`, `Type[Selection]: ...` etc., not
just `Type: ...`. Without `deep` (default false), feature labels are still
`Type: ...`.

---

## 8. GoToDefinitionTool — fuzzy fallback

**Setup** Configuration that contains `CommonModule.ОбщегоНазначенияВызовСервера`
(or any module whose name has at least 6 letters; replace below).

**Action** Misspell the symbol:
```
go_to_definition
  projectName=YourProject
  symbol=ОбщегоНазначеняВызовСервера.МойМетод
```
(One letter missing — `Назначеня` instead of `Назначения`.)

**Expected** Response is a `## Symbol not found` block with a `### Similar
Common Modules` list including the correct name `ОбщегоНазначенияВызовСервера`.

**Edge case** With a totally fictitious name (`ZZZxxx.Foo`), the response
returns "Supported Metadata Types" but no spurious "did you mean" matches.

---

## Release-readiness checklist

- [ ] Scenarios 1-8 all pass.
- [ ] `mvn clean verify -DskipTests` (run by user) builds the bundle.
- [ ] P2 update site is generated and installs in EDT 2026.1 cleanly.
- [ ] After install: `tools/list` reports 50 tools (count unchanged this phase).
- [ ] `write_module_source` schema includes `line`, `validateAfterWrite`,
  `confirmFullReplace`.
- [ ] `get_project_errors` schema includes `scope`, `fileFilter`,
  `waitForRefresh`.
- [ ] `find_references` schema includes `deep`.
- [ ] No new errors appear in EDT's own log (Window → Show View → Error Log).
