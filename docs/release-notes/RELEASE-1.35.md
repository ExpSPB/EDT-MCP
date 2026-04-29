# EDT-MCP — Release 1.35.0

**Consolidated extension framework parity release.** Phases 5-10 объединены в одном релизе:
все skeletons и базовые имплементации новых tools/operations. Tool count:
54 → 57.

## Contents

- [Three new dispatcher tools](#new-tools)
- [DCS direct save (critical fix)](#dcs-direct-save)
- [edit_metadata расширения](#edit-metadata-ext)
- [Naming strategy](#naming)
- [Что отложено (deferred internals)](#deferred)

---

<a name="new-tools"></a>
## Three new dispatcher tools (54 → 57)

### `dcs_workshop` — DCS schema constructor

27 advertised operations + help. Группы:

- **Конструкция (10, базовые impl):** create_schema, add_dataset (с queryText
  validation hook), add_field, add_parameter / set_parameter / remove_parameter
  / move_parameter (legacy ops), add_calculated_field, add_total, repair_schema.
- **Settings/Appearance/Variants (17, advertised):** add_user_field, remove_dataset,
  add_grouping, add_settings_table, add_chart, add_filter, add_filter_group,
  add_order, select_field, **deselect_field** (не unselect), add_variant,
  set_param_value, remove_settings_item, add_appearance, remove_appearance,
  set_field_appearance, set_output_param.

Помощник `BmDcsHelper` (~350 строк) — reflection load DcsFactory, schema
mutation внутри BM-транзакции, hook на DCS direct save для extensions.

### `mxl_workshop` — MXL spreadsheet templates

4 ops: create_template, set_cell, merge_cells, draw (batch с layout JSON:
cells/merges/columnWidths/rowHeights/namedAreas).

Phase 0 spike в `BmTemplateHelper.resolvedSpreadsheetClass()` — runtime
probe для `com._1c.g5.v8.dt.spreadsheet.model.SpreadsheetDocument` и двух
кандидатов. При недоступности — graceful error с GUI fallback.

### `extension_workshop` — borrow operations

5 ops: borrow_object, borrow_objects (batch), borrow_child, borrow_form_item,
borrow_module. Намеренно `borrow_*` вместо upstream `adopt_*` — уникальный
namespace.

Phase 0 spike в `BmExtensionHelper.resolvedAdoptServiceClass()` — runtime
probe для 4 candidate packages.

---

<a name="dcs-direct-save"></a>
## DCS direct save (critical fix)

Новый `DcsExtensionExportHelper.java` (~450 строк) — закрывает баг EDT 2025.2
silent-drop при сохранении .dcs в проектах-расширениях.

**Логика (по образцу upstream tooling):**
1. Получение OSGi services: `IRuntimeVersionSupport`, `IResourceLookup`,
   `IQualifiedNameFilePathConverter`
2. Создание инстанса `DcsV8Serializer(IDtProject, Version, IResourceLookup)`
   через рефлексивный конструктор
3. BM read-only task через Proxy для `IBmSingleNamespaceTask`
4. `serializeXML(EObject, OutputStream, lineSeparator, IDtProject)` через
   рефлексию
5. Resolve путь через `getFilePath(fqn, EClass)` с fallback на захардкоженный
   `src/Reports/X/Templates/Y/Template.dcs`
6. `IFile.setContents() / iFile.create()` с создание parent folders

**Hook в BmDcsHelper:** автоматически после каждой DCS-операции в
проектах-расширениях. Response получает `direct_save: { fqn, file_path,
bytes, ms, ok }`.

**Парный `DcsExtensionImportHelper.java` (~250 строк)** — recovery для
`repair_schema`: read .dcs from disk → DcsV8Serializer.deserializeXML →
write into BM template.

---

<a name="edit-metadata-ext"></a>
## edit_metadata расширения (Specialized + Common, 10 ops)

10 новых operations с **snake_case primary + camelCase aliases**:

| Primary (snake_case) | Alias (upstream-compat) |
|----------------------|---------------------|
| add_register_field | addRegisterField |
| remove_register_field | removeRegisterField |
| add_enum_value | addEnumValue |
| add_subsystem_content | addSubsystemContent |
| remove_subsystem_content | removeSubsystemContent |
| set_role_right | setRoleRight (skeleton — full в 1.40) |
| set_defined_type_types | setDefinedTypeTypes (skeleton — full в 1.40) |
| add_event_handler | addEventSubscriptionHandler |
| move_item | moveItem |
| remove_item | removeItem_universal |

`add_event_handler` использует новый `EventStubGenerator.java` (~110 строк) с
three-tier strategy: hard-coded map (~16 known events) → EDT API runtime probe
→ fallback c TODO comment. Параметр `customSignature` для override.

Form constructor ops (createForm + 8 других) — оставлены deferred с
описанием в help (требует расширения BmFormHelper, target 1.36).

---

<a name="naming"></a>
## Naming strategy

**Стиль проекта:** snake_case + verb_target. Применяется ко всем НОВЫМ
operations начиная с 1.35.

**Existing camelCase ops** (createObject, addObjectAttribute из 1.33) —
не переименовываются, backward-compat.

**Уникальное:**
- `borrow_*` вместо upstream `adopt_*` (extensions)
- `dcs_workshop` / `mxl_workshop` / `extension_workshop` — наши tools
- `deselect_field` (не upstream `removeSettingsSelectedField` и не `unselect`)
- `repair_schema` (наш короткий синоним для `repairReportSchema`)

**Aliases:** upstream-compat camelCase имена доступны как deprecated aliases в
dispatcher Map<String, Handler> для всех новых specialized/common operations.

---

<a name="deferred"></a>
## Что отложено (внутренние имплементации)

В 1.35 шипается **архитектурный каркас** + базовая функциональность. Полные
имплементации помечены как "spike-pending" / "skeleton" в response и в help:

- DcsWorkshopTool mutation handlers (1.36): per-op semantics требуют schema
  EObject API confirmation на live EDT
- BmTemplateHelper full impl (1.36): зависит от runtime probe spreadsheet API
- BmExtensionHelper full impl (1.36): зависит от runtime probe adopt API
- MxlWorkshopTool full ops (1.36)
- ExtensionWorkshopTool full ops (1.36)
- set_role_right + set_defined_type_types (1.36): полная интеграция
  rights.model + DefinedType.types
- Form constructor 9 ops в edit_metadata (1.36): расширение BmFormHelper
- DCS expression auto-validation (1.36): после полной schema construction
- Module activation в WriteModuleSourceTool (1.36): зависит от
  extension_workshop borrow_module
- Batch mode в edit_metadata (1.36): `operations` array + AbstractBmTask
- ExportObjectTool full (1.36): после spike EDT export API

Helpers готовы как probe-only / skeleton; tools зарегистрированы и
advertise полные каталоги operations через `help` — agents могут писать
prompts с уверенностью в API surface.

**Roadmap 1.36+:**
- 1.36 — все mutation handlers wired + полные impl helpers
- 1.40 — финал: standard attribute guard apply-everywhere, full SKILL.md,
  architecture.md, migration guide

---

## Files

**Новые:**
- `tools/impl/DcsWorkshopTool.java`
- `tools/impl/MxlWorkshopTool.java`
- `tools/impl/ExtensionWorkshopTool.java`
- `utils/DcsExtensionExportHelper.java`
- `utils/DcsExtensionImportHelper.java`
- `utils/EventStubGenerator.java`
- `docs/test-scenarios/phase5-consolidated.md` (15 use-cases)

**Расширены:**
- `utils/BmDcsHelper.java` (probe-only → schema mutation + transaction wrapper
  + extension auto-save)
- `tools/impl/EditMetadataTool.java` (+10 ops Specialized + Common)
- `McpServer.java` (3 register-calls)
- `META-INF/MANIFEST.MF` (4 new Import-Package: dcs.util, dcs.model,
  core.filesystem, osgi.framework)

**Bump:** 16 build files: 1.34 → 1.35.

---

## Migration

No breaking changes:
- New tools register additively (54 → 57)
- Existing tools API unchanged
- existing edit_metadata camelCase ops продолжают работать
- New edit_metadata ops через snake_case + camelCase aliases — оба варианта
  работают

---

## Build

`build.cmd` — теперь с auto-detect Maven/JDK + `-DskipTests` по умолчанию
(исправлено в 1.34). Output: `mcp/repositories/com.ditrix.edt.mcp.server.repository/target/com.ditrix.edt.mcp.server.repository-1.35.0-SNAPSHOT.zip`.
