# EDT-MCP 1.39 — Security Pack

Adds 4 new tools focused on security audit + developer experience. Tool count
61 → 65.

## What's new

### `audit_role_rights`

Audit 1C role rights. Modes:
- `rights` — per-object rights grid (Read / Update / Insert / Delete / View / Use)
- `missing` — under-privileged objects (no Allow rights)
- `conflicts` — pairs of roles with contradictory verdicts on the same object
- `impact` — rights that only one of N roles allows (what user loses on role removal)

EMF-based with `getRights` / `getRightsSettings` / `getObjectRights` reflection
fallback. RLS detection via `getRestrictionByCondition` / `getRestrictions`.

### `find_rls_violations`

Static analyzer for RLS bypass patterns. 1.39 MVP detects
`УстановитьПривилегированныйРежим(Истина)` without matching `(Ложь)` reset
within the same method. Intra-method analysis only — call-graph traversal
deferred.

Returns `noRlsConfigured: true` when project has no RLS configured (graceful
informational result, not error).

### `sensitive_data_scan`

Scans for personal-data + secret leaks. 4 check kinds:
- `ATTRIBUTE_NAME` — attribute name matches dictionary (Password, Passport,
  СНИЛС, ИНН, etc.). Russian + English. Custom patterns via
  `customPatterns` parameter
- `HARDCODED_SECRET` — Bearer tokens, AWS keys, JWT, base64 blobs in BSL
  string literals
- `COMMENT_LEAK` — email + Russian phone number patterns inside `//` comments
- `LOG_SENSITIVE` — `ЗаписьЖурналаРегистрации` calls referencing sensitive
  attribute names

### `generate_event_handlers`

Generates BSL event-handler stubs for metadata objects. Modes:
- `stub` — empty body with `// TODO`
- `full` — typical templates (e.g. `ПередЗаписью` with `Если ЭтоНовый()`)

Supports Catalog / Document / Register / ChartOfCharacteristicTypes / Task /
BusinessProcess. CommonModule yields `noEventsForModuleType` tag.

## Architecture

- New helpers: `RoleRightsAnalyzer` (EMF + reflection), `SensitivePatternLibrary`
  (Russian + English dictionary, secret regex patterns).

## Known limitations (deferred to 1.40)

- `validate_query fix=true` extension — not in 1.39, deferred
- `compare_configurations mode=commits|branches|bm_vs_disk` (Phase F) —
  deferred, requires git shadow-clone + BmVsDiskDiffer
- `find_rls_violations` cross-method PRIVILEGED_MODE tracking via call graph

## Migration

No breaking changes. New tools opt-in via `tools/list` + parameters.
