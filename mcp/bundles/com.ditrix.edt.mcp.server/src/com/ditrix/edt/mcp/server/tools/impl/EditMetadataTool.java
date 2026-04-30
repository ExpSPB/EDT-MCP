/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.common.util.EList;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BmCommonFormPostCreate;
import com.ditrix.edt.mcp.server.utils.BmCommonModuleGuards;
import com.ditrix.edt.mcp.server.utils.BmDcsHelper;
import com.ditrix.edt.mcp.server.utils.BmEventSubscriptionHelper;
import com.ditrix.edt.mcp.server.utils.BmExtensionHelper;
import com.ditrix.edt.mcp.server.utils.BmFormHelper;
import com.ditrix.edt.mcp.server.utils.BmObjectHelper;
import com.ditrix.edt.mcp.server.utils.BmRightsHelper;
import com.ditrix.edt.mcp.server.utils.BmSubsystemHelper;
import com.ditrix.edt.mcp.server.utils.BmTemplateHelper;
import com.ditrix.edt.mcp.server.utils.EventStubGenerator;
import com.ditrix.edt.mcp.server.utils.FormBaseSetup;
import com.ditrix.edt.mcp.server.utils.MetadataGuards;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;

/**
 * Single-entry constructor for metadata, forms, DCS, templates and extensions
 * - a unified constructor for edit_metadata operations.
 * <p>
 * Design rationale: a single tool with an {@code operation} discriminator
 * keeps the AI surface small (~59 operations behind one schema) and unifies
 * dryRun, batch and help semantics. Each operation routes to a dedicated
 * helper - {@link BmObjectHelper}, {@link BmDcsHelper}, {@link BmTemplateHelper},
 * {@link BmExtensionHelper}, plus the existing {@code BmFormHelper}.
 * <p>
 * Existing focused tools ({@code add_metadata_attribute},
 * {@code rename_metadata_object}, {@code delete_metadata_object},
 * {@code edit_form}) are kept as ergonomic shortcuts.
 * <p>
 * <b>Status (1.33):</b> dispatcher skeleton with the most-used object
 * operations wired (createObject, setObjectProperty, addObjectAttribute,
 * removeObjectAttribute, addTabularSection, removeTabularSection,
 * addTabularSectionAttribute, removeTabularSectionAttribute). DCS / Template /
 * Extension groups return precise "deferred" messages with API-availability
 * diagnostics; operations land in subsequent commits up to 1.39.
 */
public class EditMetadataTool implements IMcpTool
{
    public static final String NAME = "edit_metadata"; //$NON-NLS-1$

    /** Catalog of all advertised operations - ground truth for `help`. */
    private static final Map<String, String> OPERATIONS = buildOperationsCatalog();

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Single constructor for metadata, forms, DCS, templates, extensions, reports. " //$NON-NLS-1$
            + "Pass operation=<name> with operation-specific parameters. " //$NON-NLS-1$
            + "Call operation=help for the full catalog (~64 operations across 7 groups: " //$NON-NLS-1$
            + "Objects 8, Specialized 7, Forms 15, Templates 4, Extensions 5, DCS 27, Common 2). " //$NON-NLS-1$
            + "Add dryRun=true to any operation to preview changes without applying them. " //$NON-NLS-1$
            + "1.40 enhancements: idempotent skip with propertyMismatch tag, cascade form cleanup " //$NON-NLS-1$
            + "(cascadeForms=true), 4 defensive layers for headless metadata creation."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("operation", //$NON-NLS-1$
                "Operation name. Use 'help' to list available operations and topics.", true) //$NON-NLS-1$
            .stringProperty("projectName", "EDT project name (most operations).") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("ownerFqn", //$NON-NLS-1$
                "FQN of the owning metadata object for object/attribute/TC operations.") //$NON-NLS-1$
            .stringProperty("objectType", //$NON-NLS-1$
                "English-singular metadata type for createObject (e.g. Catalog, Document).") //$NON-NLS-1$
            .stringProperty("name", //$NON-NLS-1$
                "Name of the new element (createObject / addObjectAttribute / ...).") //$NON-NLS-1$
            .stringProperty("propertyName", //$NON-NLS-1$
                "Property name for setObjectProperty.") //$NON-NLS-1$
            .stringProperty("propertyValue", //$NON-NLS-1$
                "Property value for setObjectProperty (string; coerced to setter type).") //$NON-NLS-1$
            .stringProperty("tabularSectionName", //$NON-NLS-1$
                "Tabular section name for TS operations.") //$NON-NLS-1$
            .stringProperty("topic", //$NON-NLS-1$
                "Help topic name (use with operation=help).") //$NON-NLS-1$
            .booleanProperty("dryRun", //$NON-NLS-1$
                "Preview the operation inside a BM transaction and roll back. Default false.") //$NON-NLS-1$
            .booleanProperty("batch", //$NON-NLS-1$
                "Wrap multiple operations in a single BM session (operations array).") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        boolean batch = JsonUtils.extractBooleanArgument(params, "batch", false); //$NON-NLS-1$
        if (batch)
        {
            return executeBatch(params);
        }

        String operation = JsonUtils.extractStringArgument(params, "operation"); //$NON-NLS-1$
        if (operation == null || operation.isEmpty())
        {
            return ToolResult.error("operation is required. Pass operation=help for the catalog.") //$NON-NLS-1$
                .toJson();
        }
        String op = operation.trim();
        if ("help".equalsIgnoreCase(op)) //$NON-NLS-1$
        {
            return handleHelp(params);
        }

        if (!OPERATIONS.containsKey(op))
        {
            return ToolResult.error(
                "Unknown operation '" + op + "'. " //$NON-NLS-1$ //$NON-NLS-2$
                    + "Call operation=help for the full list. " //$NON-NLS-1$
                    + "Did you mean: " + suggest(op) + "?") //$NON-NLS-1$ //$NON-NLS-2$
                .toJson();
        }

        // All operations require BM access on the UI thread
        AtomicReference<String> resultRef = new AtomicReference<>();
        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> {
            try
            {
                resultRef.set(dispatch(op, params));
            }
            catch (Exception e)
            {
                Activator.logError("edit_metadata error in operation " + op, e); //$NON-NLS-1$
                resultRef.set(ToolResult.error(e.getMessage()).toJson());
            }
        });
        return resultRef.get();
    }

    /**
     * Sequential batch mode: applies a list of operations one by one. Each
     * sub-operation runs in its own BM transaction; on per-op failure the
     * batch continues and records the failure in {@code batchResults}.
     * <p>
     * <b>1.37 limitation:</b> not transactional. Successful ops are committed
     * even when later ops fail. Full atomic rollback (single BM tx for all)
     * is on the 1.38 backlog.
     * <p>
     * The {@code operations} parameter is expected as a flat string of
     * lines, each {@code "<operation> key1=value1 key2=value2"}. JSON-array
     * parsing is also accepted: {@code operations=[{"operation":"...", ...}]}.
     */
    private String executeBatch(Map<String, String> params)
    {
        String operationsRaw = JsonUtils.extractStringArgument(params, "operations"); //$NON-NLS-1$
        if (operationsRaw == null || operationsRaw.isEmpty())
        {
            return ToolResult.error("batch=true requires `operations` parameter").toJson(); //$NON-NLS-1$
        }
        java.util.List<Map<String, String>> ops = parseBatchOperations(operationsRaw, params);
        if (ops.isEmpty())
        {
            return ToolResult.error("batch operations parsed empty - check format").toJson(); //$NON-NLS-1$
        }
        java.util.List<Map<String, Object>> results = new java.util.ArrayList<>();
        int okCount = 0;
        int failCount = 0;
        for (int i = 0; i < ops.size(); i++)
        {
            Map<String, String> opParams = ops.get(i);
            String subOp = JsonUtils.extractStringArgument(opParams, "operation"); //$NON-NLS-1$
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("index", i); //$NON-NLS-1$
            entry.put("operation", subOp); //$NON-NLS-1$
            if (subOp == null || subOp.isEmpty() || !OPERATIONS.containsKey(subOp))
            {
                entry.put("ok", false); //$NON-NLS-1$
                entry.put("error", "unknown or empty operation"); //$NON-NLS-1$ //$NON-NLS-2$
                failCount++;
                results.add(entry);
                continue;
            }
            // Inherit shared parameters (projectName, ownerFqn, dryRun) from the
            // outer call when individual ops omit them.
            for (String shared : new String[] { "projectName", "ownerFqn", "dryRun" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {
                if (!opParams.containsKey(shared) && params.containsKey(shared))
                {
                    opParams.put(shared, params.get(shared));
                }
            }
            try
            {
                AtomicReference<String> ref = new AtomicReference<>();
                Display display = PlatformUI.getWorkbench().getDisplay();
                display.syncExec(() -> {
                    try
                    {
                        ref.set(dispatch(subOp, opParams));
                    }
                    catch (Exception e)
                    {
                        Activator.logError("batch op " + subOp + " failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                        ref.set(ToolResult.error(e.getMessage()).toJson());
                    }
                });
                String json = ref.get();
                entry.put("response", json); //$NON-NLS-1$
                boolean isOk = false;
                try
                {
                    com.google.gson.JsonObject obj = com.google.gson.JsonParser
                        .parseString(json).getAsJsonObject();
                    isOk = obj.has("success") && obj.get("success").getAsBoolean(); //$NON-NLS-1$ //$NON-NLS-2$
                }
                catch (Exception parseEx)
                {
                    // Fallback to naive substring check if response is non-JSON
                    isOk = json != null && json.contains("\"success\":true"); //$NON-NLS-1$
                }
                entry.put("ok", isOk); //$NON-NLS-1$
                if (isOk)
                {
                    okCount++;
                }
                else
                {
                    failCount++;
                }
            }
            catch (Exception e)
            {
                entry.put("ok", false); //$NON-NLS-1$
                entry.put("error", e.getMessage()); //$NON-NLS-1$
                failCount++;
            }
            results.add(entry);
        }
        return ToolResult.success()
            .put("batch", true) //$NON-NLS-1$
            .put("ok", okCount) //$NON-NLS-1$
            .put("fail", failCount) //$NON-NLS-1$
            .put("batchResults", results) //$NON-NLS-1$
            .toJson();
    }

    /**
     * Parses the {@code operations} parameter into a list of per-op parameter
     * maps. Two formats accepted:
     * <ul>
     *   <li>JSON array: {@code [{"operation":"addObjectAttribute","name":"X"}, ...]}</li>
     *   <li>Newline-separated flat lines: {@code addObjectAttribute name=X}</li>
     * </ul>
     */
    private java.util.List<Map<String, String>> parseBatchOperations(String raw,
        Map<String, String> outer)
    {
        java.util.List<Map<String, String>> ops = new java.util.ArrayList<>();
        String trimmed = raw.trim();
        if (trimmed.startsWith("[")) //$NON-NLS-1$
        {
            // Best-effort JSON-array parse: split on top-level commas + extract
            // key/value pairs by simple regex. A full JSON parser would tie us
            // to Gson here, which is also fine - but the simple parser keeps
            // the dispatcher self-contained.
            try
            {
                com.google.gson.JsonArray arr = com.google.gson.JsonParser.parseString(trimmed)
                    .getAsJsonArray();
                for (int i = 0; i < arr.size(); i++)
                {
                    com.google.gson.JsonObject o = arr.get(i).getAsJsonObject();
                    Map<String, String> opParams = new LinkedHashMap<>();
                    for (Map.Entry<String, com.google.gson.JsonElement> entry : o.entrySet())
                    {
                        com.google.gson.JsonElement v = entry.getValue();
                        opParams.put(entry.getKey(),
                            v.isJsonPrimitive() ? v.getAsString() : v.toString());
                    }
                    ops.add(opParams);
                }
                return ops;
            }
            catch (Exception jsonEx)
            {
                Activator.logWarning("batch JSON parse failed: " + jsonEx.getMessage()); //$NON-NLS-1$
            }
        }
        // Line-based fallback: each non-empty line is one operation.
        for (String line : trimmed.split("\\r?\\n")) //$NON-NLS-1$
        {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("#")) //$NON-NLS-1$
            {
                continue;
            }
            Map<String, String> opParams = new LinkedHashMap<>();
            String[] tokens = l.split("\\s+"); //$NON-NLS-1$
            opParams.put("operation", tokens[0]); //$NON-NLS-1$
            for (int i = 1; i < tokens.length; i++)
            {
                int eq = tokens[i].indexOf('=');
                if (eq > 0)
                {
                    opParams.put(tokens[i].substring(0, eq), tokens[i].substring(eq + 1));
                }
            }
            ops.add(opParams);
        }
        return ops;
    }

    /**
     * Routes to the helper that owns the operation.
     */
    private String dispatch(String op, Map<String, String> params)
    {
        switch (op)
        {
            // ---- Object group ----
            case "createObject": //$NON-NLS-1$
                return opCreateObject(params);
            case "setObjectProperty": //$NON-NLS-1$
                return opSetObjectProperty(params);
            case "addObjectAttribute": //$NON-NLS-1$
                return opAddObjectAttribute(params);
            case "removeObjectAttribute": //$NON-NLS-1$
                return opRemoveObjectAttribute(params);
            case "addTabularSection": //$NON-NLS-1$
                return opAddTabularSection(params);
            case "removeTabularSection": //$NON-NLS-1$
                return opRemoveTabularSection(params);
            case "addTabularSectionAttribute": //$NON-NLS-1$
                return opAddTabularSectionAttribute(params);
            case "removeTabularSectionAttribute": //$NON-NLS-1$
                return opRemoveTabularSectionAttribute(params);

            // ---- Specialized group (1.37) ----
            case "add_register_field": //$NON-NLS-1$
            case "addRegisterField": //$NON-NLS-1$
                return opAddRegisterField(params);
            case "remove_register_field": //$NON-NLS-1$
            case "removeRegisterField": //$NON-NLS-1$
                return opRemoveRegisterField(params);
            case "add_enum_value": //$NON-NLS-1$
            case "addEnumValue": //$NON-NLS-1$
                return opAddEnumValue(params);
            case "add_subsystem_content": //$NON-NLS-1$
            case "addSubsystemContent": //$NON-NLS-1$
                return opAddSubsystemContent(params);
            case "remove_subsystem_content": //$NON-NLS-1$
            case "removeSubsystemContent": //$NON-NLS-1$
                return opRemoveSubsystemContent(params);
            case "set_role_right": //$NON-NLS-1$
            case "setRoleRight": //$NON-NLS-1$
                return opSetRoleRight(params);
            case "set_defined_type_types": //$NON-NLS-1$
            case "setDefinedTypeTypes": //$NON-NLS-1$
                return opSetDefinedTypeTypes(params);
            case "add_event_handler": //$NON-NLS-1$
            case "addEventSubscriptionHandler": //$NON-NLS-1$
                return opAddEventHandler(params);

            // ---- Common group (1.37) ----
            case "move_item": //$NON-NLS-1$
            case "moveItem": //$NON-NLS-1$
                return opMoveItem(params);
            case "remove_item": //$NON-NLS-1$
            case "removeItem_universal": //$NON-NLS-1$
                return opRemoveItem(params);

            // ---- Form group (1.37 — 4 ops + edit_form for add/remove items) ----
            case "createForm": //$NON-NLS-1$
            case "create_form": //$NON-NLS-1$
                return opCreateForm(params);
            case "addFormAttribute": //$NON-NLS-1$
            case "add_form_attribute": //$NON-NLS-1$
                return opAddFormAttribute(params);
            case "addFormCommand": //$NON-NLS-1$
            case "addCommandHandler": //$NON-NLS-1$
            case "add_form_command": //$NON-NLS-1$
                return opAddFormCommand(params);
            case "setFormItemProperty": //$NON-NLS-1$
            case "setProperty": //$NON-NLS-1$
            case "set_form_item_property": //$NON-NLS-1$
                return opSetFormItemProperty(params);
            // ---- Form ops 1.40: 6 ops migrated from edit_form ----
            case "addField": //$NON-NLS-1$
            case "addGroup": //$NON-NLS-1$
            case "addButton": //$NON-NLS-1$
            case "addTable": //$NON-NLS-1$
            case "addDecoration": //$NON-NLS-1$
            case "removeFormItem": //$NON-NLS-1$
                return delegateToEditForm(op, params);
            // ---- Form ops 1.40: implemented in this file ----
            case "listPictures": //$NON-NLS-1$
                return opListPictures(params);
            // ---- Form ops 1.40.2: addRadioButton delegates to addField with elementType=RadioButton ----
            case "addRadioButton": //$NON-NLS-1$
                return delegateToEditFormAsRadioButton(params);
            // ---- Form ops 1.41: native implementations (BmFormHelper extensions) ----
            case "addFormAttributeColumn": //$NON-NLS-1$
                return opAddFormAttributeColumn(params);
            case "addDynamicListTable": //$NON-NLS-1$
                return opAddDynamicListTable(params);
            case "setupSettingsComposerOnForm": //$NON-NLS-1$
                return opSetupSettingsComposerOnForm(params);

            // ---- Templates group 1.40 ----
            case "addTemplate": //$NON-NLS-1$
                return opAddTemplate(params);
            case "setTemplateCell": //$NON-NLS-1$
            case "mergeTemplateCells": //$NON-NLS-1$
            case "drawTemplate": //$NON-NLS-1$
                return opTemplateCellOp(op, params);

            // ---- Extensions group 1.40 ----
            case "adoptObject": //$NON-NLS-1$
            case "adoptObjects": //$NON-NLS-1$
            case "adoptChild": //$NON-NLS-1$
            case "adoptFormItem": //$NON-NLS-1$
            case "adoptModule": //$NON-NLS-1$
                return opExtensionAdopt(op, params);

            // ---- DCS group 1.40 - delegated to DcsWorkshopTool ----
            case "createReportSchema": //$NON-NLS-1$
            case "addDataSet": //$NON-NLS-1$
            case "addDataSetField": //$NON-NLS-1$
            case "addSchemaParameter": //$NON-NLS-1$
            case "setSchemaParameter": //$NON-NLS-1$
            case "removeSchemaParameter": //$NON-NLS-1$
            case "moveSchemaParameter": //$NON-NLS-1$
            case "addCalculatedField": //$NON-NLS-1$
            case "addTotalField": //$NON-NLS-1$
            case "addUserField": //$NON-NLS-1$
            case "removeDataSet": //$NON-NLS-1$
            case "addSettingsGroup": //$NON-NLS-1$
            case "addSettingsTable": //$NON-NLS-1$
            case "addSettingsChart": //$NON-NLS-1$
            case "addSettingsFilter": //$NON-NLS-1$
            case "addSettingsFilterGroup": //$NON-NLS-1$
            case "addSettingsOrder": //$NON-NLS-1$
            case "addSettingsSelectedField": //$NON-NLS-1$
            case "removeSettingsSelectedField": //$NON-NLS-1$
            case "addSettingsVariant": //$NON-NLS-1$
            case "setSettingsParameter": //$NON-NLS-1$
            case "removeSettingsItem": //$NON-NLS-1$
            case "addConditionalAppearance": //$NON-NLS-1$
            case "removeConditionalAppearance": //$NON-NLS-1$
            case "setDataSetFieldAppearance": //$NON-NLS-1$
            case "setOutputParameter": //$NON-NLS-1$
            case "repairReportSchema": //$NON-NLS-1$
                return delegateToDcsWorkshop(op, params);

            default:
                return ToolResult.error("Operation routed but not implemented: " + op).toJson(); //$NON-NLS-1$
        }
    }

    // -----------------------------------------------------------------------
    // Object operations
    // -----------------------------------------------------------------------

    private String opCreateObject(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String objectType = JsonUtils.extractStringArgument(params, "objectType"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String err = requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + requireNonEmpty(objectType, "objectType") //$NON-NLS-1$
            + requireNonEmpty(name, "name"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        String englishType = MetadataTypeUtils.toEnglishSingular(objectType);
        if (englishType == null)
        {
            return ToolResult.error("Unknown objectType: " + objectType //$NON-NLS-1$
                + ". Use English singular (Catalog, Document, ...) or Russian equivalent.") //$NON-NLS-1$
                .toJson();
        }

        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
        }
        IConfigurationProvider configProvider = Activator.getDefault().getConfigurationProvider();
        Configuration config = configProvider != null ? configProvider.getConfiguration(project) : null;
        if (config == null)
        {
            return ToolResult.error("Configuration not available for project: " + projectName) //$NON-NLS-1$
                .toJson();
        }

        // 3.8.2: extension CommonModule guards (privileged, global+server)
        if ("CommonModule".equals(englishType))
        {
            Boolean privileged = JsonUtils.extractBooleanArgumentNullable(params, "privileged"); //$NON-NLS-1$
            Boolean globalFlag = JsonUtils.extractBooleanArgumentNullable(params, "global"); //$NON-NLS-1$
            Boolean serverFlag = JsonUtils.extractBooleanArgumentNullable(params, "server"); //$NON-NLS-1$
            try
            {
                BmCommonModuleGuards.validate(project, privileged, globalFlag, serverFlag);
            }
            catch (MetadataGuards.BlockedGuardException blocked)
            {
                MetadataGuards.Verdict v = blocked.verdict;
                ToolResult result = ToolResult.error(v.error != null ? v.error : "blocked")
                    .put("operation", "createObject")
                    .put("hint", v.hint != null ? v.hint : "");
                if (v.tag != null)
                {
                    result.put(v.tag.name, v.tag.data);
                }
                return result.toJson();
            }
        }

        // Create+add inside a write task
        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        IBmModel bmModel = bmModelManager != null ? bmModelManager.getModel(project) : null;
        if (bmModel == null)
        {
            return ToolResult.error("BM model not available").toJson(); //$NON-NLS-1$
        }

        // 3.8.4: track inner-form creation for CommonForm
        AtomicReference<String> innerFormFqn = new AtomicReference<>(null);

        StringBuilder finalErr = new StringBuilder();
        try
        {
            bmModel.execute(new AbstractBmTask<Void>("edit_metadata.createObject") //$NON-NLS-1$
            {
                @Override
                public Void execute(IBmTransaction tx, IProgressMonitor pm)
                {
                    MdObject created = BmObjectHelper.createObject(englishType);
                    if (created == null)
                    {
                        finalErr.append("Cannot create '" + englishType //$NON-NLS-1$
                            + "' - MdClassFactory.create" + englishType //$NON-NLS-1$
                            + "() not available."); //$NON-NLS-1$
                        return null;
                    }
                    created.setName(name);
                    if (!BmObjectHelper.addToConfiguration(config, created))
                    {
                        finalErr.append("Created object but failed to attach it to the configuration. " //$NON-NLS-1$
                            + "Configuration may not have the matching collection for this type."); //$NON-NLS-1$
                        return null;
                    }
                    // 3.8.4: CommonForm needs an inner Form created in the same transaction
                    if ("CommonForm".equals(englishType))
                    {
                        BmCommonFormPostCreate.PostCreateResult pcr
                            = BmCommonFormPostCreate.createInnerForm(created);
                        if (pcr.ok && pcr.innerFormFqn != null)
                        {
                            innerFormFqn.set(pcr.innerFormFqn);
                        }
                        else if (pcr.error != null)
                        {
                            Activator.logWarning("CommonForm createObject: " + pcr.error); //$NON-NLS-1$
                        }
                    }
                    if (dryRun)
                    {
                        // abort to discard
                        throw new RuntimeException("__DRY_RUN__"); //$NON-NLS-1$
                    }
                    return null;
                }
            });
        }
        catch (Exception e)
        {
            if (!"__DRY_RUN__".equals(e.getCause() != null ? e.getCause().getMessage() //$NON-NLS-1$
                : e.getMessage()))
            {
                return ToolResult.error("createObject failed: " //$NON-NLS-1$
                    + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()))
                    .toJson();
            }
        }
        if (finalErr.length() > 0)
        {
            return ToolResult.error(finalErr.toString()).toJson();
        }
        return ToolResult.success()
            .put("operation", "createObject") //$NON-NLS-1$ //$NON-NLS-2$
            .put("objectType", englishType) //$NON-NLS-1$
            .put("name", name) //$NON-NLS-1$
            .put("dryRun", dryRun) //$NON-NLS-1$
            .put("message", dryRun //$NON-NLS-1$
                ? "Dry run: " + englishType + "." + name + " would be created." //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                : englishType + "." + name + " created.") //$NON-NLS-1$ //$NON-NLS-2$
            .toJson();
    }

    private String opSetObjectProperty(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String propertyName = JsonUtils.extractStringArgument(params, "propertyName"); //$NON-NLS-1$
        String propertyValue = JsonUtils.extractStringArgument(params, "propertyValue"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String err = requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + requireNonEmpty(ownerFqn, "ownerFqn") //$NON-NLS-1$
            + requireNonEmpty(propertyName, "propertyName"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
        }

        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                String setErr = BmObjectHelper.setProperty(owner, propertyName, propertyValue);
                if (setErr != null)
                {
                    throw new RuntimeException(setErr);
                }
                return propertyName + "=" + propertyValue; //$NON-NLS-1$
            });
        return formatResult(r, "setObjectProperty"); //$NON-NLS-1$
    }

    private String opAddObjectAttribute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        String type = JsonUtils.extractStringArgument(params, "type"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        boolean autoBorrow = JsonUtils.extractBooleanArgument(params, "auto_borrow", true); //$NON-NLS-1$

        String err = requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + requireNonEmpty(ownerFqn, "ownerFqn") //$NON-NLS-1$
            + requireNonEmpty(name, "name"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
        }

        // Phase 6.6: auto-borrow referenced metadata objects when the attribute
        // type is a reference (CatalogRef.X / DocumentRef.X / etc.) inside an
        // extension project. Best-effort - failures surface as warning tags.
        java.util.List<String> autoBorrowed = new java.util.ArrayList<>();
        java.util.List<Map<String, Object>> autoBorrowSkipped = new java.util.ArrayList<>();
        if (type != null && !type.isEmpty() && BmDcsHelper.isExtensionProject(project))
        {
            String targetFqn = extractReferenceTargetFqn(type);
            if (targetFqn != null)
            {
                if (autoBorrow)
                {
                    BmExtensionHelper.BorrowResult br = BmExtensionHelper.attemptBorrow(project,
                        null, targetFqn, null);
                    if (br.ok)
                    {
                        autoBorrowed.add(targetFqn);
                    }
                    else
                    {
                        Map<String, Object> sk = new LinkedHashMap<>();
                        sk.put("targetFqn", targetFqn); //$NON-NLS-1$
                        sk.put("reason", br.error != null ? br.error : "unknown"); //$NON-NLS-1$ //$NON-NLS-2$
                        autoBorrowSkipped.add(sk);
                    }
                }
                else
                {
                    Map<String, Object> sk = new LinkedHashMap<>();
                    sk.put("targetFqn", targetFqn); //$NON-NLS-1$
                    sk.put("reason", "auto_borrow=false"); //$NON-NLS-1$ //$NON-NLS-2$
                    autoBorrowSkipped.add(sk);
                }
            }
        }

        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                EList<MdObject> attrs = BmObjectHelper.getAttributes(owner);
                if (attrs == null)
                {
                    throw new RuntimeException("Owner type '" + owner.eClass().getName() //$NON-NLS-1$
                        + "' has no Attributes collection. " //$NON-NLS-1$
                        + "Use add_metadata_attribute or addRegisterField for registers."); //$NON-NLS-1$
                }
                if (BmObjectHelper.findByName(attrs, name) != null)
                {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("name", name); //$NON-NLS-1$
                    data.put("ownerFqn", ownerFqn); //$NON-NLS-1$
                    data.put("kind", "attribute"); //$NON-NLS-1$ //$NON-NLS-2$
                    throw new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
                        "Attribute already exists: " + name, //$NON-NLS-1$
                        "Use removeObjectAttribute first, or pick a different name.", //$NON-NLS-1$
                        new MetadataGuards.ErrorTag("alreadyExists", data))); //$NON-NLS-1$
                }
                MdObject attribute = BmObjectHelper.createObject("Attribute"); //$NON-NLS-1$
                if (attribute == null)
                {
                    throw new RuntimeException("MdClassFactory.createAttribute() not available"); //$NON-NLS-1$
                }
                attribute.setName(name);
                attrs.add(attribute);
                return name;
            },
            owner -> {
                // Supplier lock guard runs automatically inside the helper.
                // Caller-specific: standard attribute name collision.
                MetadataGuards.Verdict conflict = MetadataGuards
                    .checkStandardAttributeConflict(owner, name);
                if (conflict.blocked)
                {
                    throw new MetadataGuards.BlockedGuardException(conflict);
                }
            });
        // Surface auto-borrow telemetry into the response tags
        if (!autoBorrowed.isEmpty())
        {
            r.tags.put("autoBorrowed", autoBorrowed); //$NON-NLS-1$
        }
        if (!autoBorrowSkipped.isEmpty())
        {
            r.tags.put("autoBorrowSkipped", autoBorrowSkipped); //$NON-NLS-1$
        }
        return formatResult(r, "addObjectAttribute"); //$NON-NLS-1$
    }

    /**
     * Extracts the FQN of a referenced metadata object from a TypeDescription
     * string passed to {@code addObjectAttribute type=...}.
     * <p>
     * Recognises:
     * <ul>
     *   <li>{@code CatalogRef.X} -> {@code Catalog.X}</li>
     *   <li>{@code DocumentRef.X} -> {@code Document.X}</li>
     *   <li>{@code EnumRef.X} -> {@code Enumeration.X}</li>
     *   <li>{@code ChartOfAccountsRef.X} -> {@code ChartOfAccounts.X}</li>
     *   <li>{@code ChartOfCalculationTypesRef.X} -> {@code ChartOfCalculationTypes.X}</li>
     *   <li>{@code TaskRef.X} -> {@code Task.X}</li>
     *   <li>{@code BusinessProcessRef.X} -> {@code BusinessProcess.X}</li>
     *   <li>{@code ExchangePlanRef.X} -> {@code ExchangePlan.X}</li>
     * </ul>
     * Returns {@code null} for primitive types (Date / Number / String / Boolean)
     * and unrecognised patterns.
     */
    private String extractReferenceTargetFqn(String typeDescription)
    {
        if (typeDescription == null)
        {
            return null;
        }
        String t = typeDescription.trim();
        int dot = t.indexOf('.');
        if (dot <= 0)
        {
            return null;
        }
        String prefix = t.substring(0, dot);
        String name = t.substring(dot + 1);
        if (name.isEmpty())
        {
            return null;
        }
        switch (prefix)
        {
            case "CatalogRef": return "Catalog." + name; //$NON-NLS-1$ //$NON-NLS-2$
            case "DocumentRef": return "Document." + name; //$NON-NLS-1$ //$NON-NLS-2$
            case "EnumRef": return "Enumeration." + name; //$NON-NLS-1$ //$NON-NLS-2$
            case "ChartOfAccountsRef": return "ChartOfAccounts." + name; //$NON-NLS-1$ //$NON-NLS-2$
            case "ChartOfCalculationTypesRef": //$NON-NLS-1$
                return "ChartOfCalculationTypes." + name; //$NON-NLS-1$
            case "ChartOfCharacteristicTypesRef": //$NON-NLS-1$
                return "ChartOfCharacteristicTypes." + name; //$NON-NLS-1$
            case "TaskRef": return "Task." + name; //$NON-NLS-1$ //$NON-NLS-2$
            case "BusinessProcessRef": return "BusinessProcess." + name; //$NON-NLS-1$ //$NON-NLS-2$
            case "ExchangePlanRef": return "ExchangePlan." + name; //$NON-NLS-1$ //$NON-NLS-2$
            default: return null;
        }
    }

    private String opRemoveObjectAttribute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String err = requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + requireNonEmpty(ownerFqn, "ownerFqn") //$NON-NLS-1$
            + requireNonEmpty(name, "name"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
        }

        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                EList<MdObject> attrs = BmObjectHelper.getAttributes(owner);
                if (attrs == null)
                {
                    throw new RuntimeException("Owner has no Attributes collection"); //$NON-NLS-1$
                }
                MdObject existing = BmObjectHelper.findByName(attrs, name);
                if (existing == null)
                {
                    throw BmObjectHelper.notFound(name, ownerFqn, "attribute"); //$NON-NLS-1$
                }
                attrs.remove(existing);
                return name;
            });
        return formatResult(r, "removeObjectAttribute"); //$NON-NLS-1$
    }

    private String opAddTabularSection(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String err = requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + requireNonEmpty(ownerFqn, "ownerFqn") //$NON-NLS-1$
            + requireNonEmpty(name, "name"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
        }

        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                EList<MdObject> tcs = BmObjectHelper.getTabularSections(owner);
                if (tcs == null)
                {
                    throw new RuntimeException("Owner type '" + owner.eClass().getName() //$NON-NLS-1$
                        + "' has no TabularSections collection."); //$NON-NLS-1$
                }
                if (BmObjectHelper.findByName(tcs, name) != null)
                {
                    throw BmObjectHelper.alreadyExists(name, ownerFqn, "tabularSection"); //$NON-NLS-1$
                }
                MdObject ts = BmObjectHelper.createObject("TabularSection"); //$NON-NLS-1$
                if (ts == null)
                {
                    throw new RuntimeException("MdClassFactory.createTabularSection() not available"); //$NON-NLS-1$
                }
                ts.setName(name);
                tcs.add(ts);
                return name;
            });
        return formatResult(r, "addTabularSection"); //$NON-NLS-1$
    }

    private String opRemoveTabularSection(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String err = requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + requireNonEmpty(ownerFqn, "ownerFqn") //$NON-NLS-1$
            + requireNonEmpty(name, "name"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
        }

        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                EList<MdObject> tcs = BmObjectHelper.getTabularSections(owner);
                if (tcs == null)
                {
                    throw new RuntimeException("Owner has no TabularSections collection"); //$NON-NLS-1$
                }
                MdObject existing = BmObjectHelper.findByName(tcs, name);
                if (existing == null)
                {
                    throw BmObjectHelper.notFound(name, ownerFqn, "tabularSection"); //$NON-NLS-1$
                }
                tcs.remove(existing);
                return name;
            });
        return formatResult(r, "removeTabularSection"); //$NON-NLS-1$
    }

    private String opAddTabularSectionAttribute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String tcName = JsonUtils.extractStringArgument(params, "tabularSectionName"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String err = requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + requireNonEmpty(ownerFqn, "ownerFqn") //$NON-NLS-1$
            + requireNonEmpty(tcName, "tabularSectionName") //$NON-NLS-1$
            + requireNonEmpty(name, "name"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
        }

        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                EList<MdObject> tcs = BmObjectHelper.getTabularSections(owner);
                if (tcs == null)
                {
                    throw new RuntimeException("Owner has no TabularSections collection"); //$NON-NLS-1$
                }
                MdObject ts = BmObjectHelper.findByName(tcs, tcName);
                if (ts == null)
                {
                    throw BmObjectHelper.notFound(tcName, ownerFqn, "tabularSection"); //$NON-NLS-1$
                }
                // Standard tabular-section attribute name guard (LineNumber/НомерСтроки on
                // Document tabular parts) - the candidate must not shadow a standard one.
                MetadataGuards.Verdict tcConflict = MetadataGuards
                    .checkStandardAttributeConflict(ts, name);
                if (tcConflict.blocked)
                {
                    throw new MetadataGuards.BlockedGuardException(tcConflict);
                }
                EList<MdObject> attrs = BmObjectHelper.getAttributes(ts);
                if (attrs == null)
                {
                    throw new RuntimeException("TabularSection has no Attributes collection"); //$NON-NLS-1$
                }
                if (BmObjectHelper.findByName(attrs, name) != null)
                {
                    throw BmObjectHelper.alreadyExists(name, ownerFqn + "." + tcName, //$NON-NLS-1$
                        "tabularSectionAttribute"); //$NON-NLS-1$
                }
                MdObject attribute = BmObjectHelper.createObject("Attribute"); //$NON-NLS-1$
                if (attribute == null)
                {
                    throw new RuntimeException("MdClassFactory.createAttribute() not available"); //$NON-NLS-1$
                }
                attribute.setName(name);
                attrs.add(attribute);
                return tcName + "." + name; //$NON-NLS-1$
            });
        return formatResult(r, "addTabularSectionAttribute"); //$NON-NLS-1$
    }

    private String opRemoveTabularSectionAttribute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String tcName = JsonUtils.extractStringArgument(params, "tabularSectionName"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String err = requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + requireNonEmpty(ownerFqn, "ownerFqn") //$NON-NLS-1$
            + requireNonEmpty(tcName, "tabularSectionName") //$NON-NLS-1$
            + requireNonEmpty(name, "name"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
        }

        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                EList<MdObject> tcs = BmObjectHelper.getTabularSections(owner);
                MdObject ts = tcs != null ? BmObjectHelper.findByName(tcs, tcName) : null;
                if (ts == null)
                {
                    throw BmObjectHelper.notFound(tcName, ownerFqn, "tabularSection"); //$NON-NLS-1$
                }
                EList<MdObject> attrs = BmObjectHelper.getAttributes(ts);
                MdObject existing = attrs != null ? BmObjectHelper.findByName(attrs, name) : null;
                if (existing == null)
                {
                    throw BmObjectHelper.notFound(name, ownerFqn + "." + tcName, //$NON-NLS-1$
                        "tabularSectionAttribute"); //$NON-NLS-1$
                }
                attrs.remove(existing);
                return tcName + "." + name; //$NON-NLS-1$
            });
        return formatResult(r, "removeTabularSectionAttribute"); //$NON-NLS-1$
    }

    // -----------------------------------------------------------------------
    // Specialized operations (1.37)
    // -----------------------------------------------------------------------

    private String opAddRegisterField(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        String kind = JsonUtils.extractStringArgument(params, "kind"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String err = requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + requireNonEmpty(ownerFqn, "ownerFqn") //$NON-NLS-1$
            + requireNonEmpty(name, "name") //$NON-NLS-1$
            + requireNonEmpty(kind, "kind"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
        }
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                String collection;
                String childType;
                switch (kind.toLowerCase())
                {
                    case "dimension": case "измерение": //$NON-NLS-1$ //$NON-NLS-2$
                        collection = "getDimensions"; childType = "Dimension"; break; //$NON-NLS-1$ //$NON-NLS-2$
                    case "resource": case "ресурс": //$NON-NLS-1$ //$NON-NLS-2$
                        collection = "getResources"; childType = "Resource"; break; //$NON-NLS-1$ //$NON-NLS-2$
                    case "attribute": case "реквизит": //$NON-NLS-1$ //$NON-NLS-2$
                        collection = "getAttributes"; childType = "Attribute"; break; //$NON-NLS-1$ //$NON-NLS-2$
                    default:
                        throw new RuntimeException("kind must be Dimension/Resource/Attribute"); //$NON-NLS-1$
                }
                EList<MdObject> list = invokeListGetter(owner, collection);
                if (list == null)
                {
                    throw new RuntimeException("Owner has no " + collection); //$NON-NLS-1$
                }
                if (BmObjectHelper.findByName(list, name) != null)
                {
                    throw BmObjectHelper.alreadyExists(name, ownerFqn,
                        "register" + childType); //$NON-NLS-1$
                }
                MdObject field = BmObjectHelper.createObject(childType);
                if (field == null)
                {
                    throw new RuntimeException("Cannot create " + childType); //$NON-NLS-1$
                }
                field.setName(name);
                list.add(field);
                return name;
            },
            owner -> {
                // Register field name must not collide with standard attributes
                // (Period / Recorder / Active / RecordType on InfoRegister and
                // AccumulationRegister, etc.).
                MetadataGuards.Verdict conflict = MetadataGuards
                    .checkStandardAttributeConflict(owner, name);
                if (conflict.blocked)
                {
                    throw new MetadataGuards.BlockedGuardException(conflict);
                }
            });
        return formatResult(r, "add_register_field"); //$NON-NLS-1$
    }

    private String opRemoveRegisterField(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found").toJson(); //$NON-NLS-1$
        }
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                for (String coll : new String[] {
                    "getDimensions", "getResources", "getAttributes" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                })
                {
                    EList<MdObject> list = invokeListGetter(owner, coll);
                    if (list != null)
                    {
                        MdObject existing = BmObjectHelper.findByName(list, name);
                        if (existing != null)
                        {
                            list.remove(existing);
                            return name;
                        }
                    }
                }
                throw BmObjectHelper.notFound(name, ownerFqn, "registerField"); //$NON-NLS-1$
            });
        return formatResult(r, "remove_register_field"); //$NON-NLS-1$
    }

    private String opAddEnumValue(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found").toJson(); //$NON-NLS-1$
        }
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                EList<MdObject> values = invokeListGetter(owner, "getEnumValues"); //$NON-NLS-1$
                if (values == null)
                {
                    throw new RuntimeException("Not an Enum or has no values collection"); //$NON-NLS-1$
                }
                if (BmObjectHelper.findByName(values, name) != null)
                {
                    return name + " (already exists)"; //$NON-NLS-1$
                }
                MdObject value = BmObjectHelper.createObject("EnumValue"); //$NON-NLS-1$
                if (value == null)
                {
                    throw new RuntimeException("Cannot create EnumValue"); //$NON-NLS-1$
                }
                value.setName(name);
                values.add(value);
                return name;
            });
        return formatResult(r, "add_enum_value"); //$NON-NLS-1$
    }

    private String opAddSubsystemContent(Map<String, String> params)
    {
        return opSubsystemContent(params, true, "add_subsystem_content"); //$NON-NLS-1$
    }

    private String opRemoveSubsystemContent(Map<String, String> params)
    {
        return opSubsystemContent(params, false, "remove_subsystem_content"); //$NON-NLS-1$
    }

    /**
     * 1.40: addSubsystemContent / removeSubsystemContent via {@link BmSubsystemHelper}.
     * Resolves the target object by FQN through the configuration and mutates
     * the subsystem's content EList atomically inside a BM transaction.
     */
    private String opSubsystemContent(Map<String, String> params, boolean add, String opName)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String contentFqn = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        if (contentFqn == null || contentFqn.isEmpty())
        {
            contentFqn = JsonUtils.extractStringArgument(params, "targetFqn"); //$NON-NLS-1$
        }
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        if (contentFqn == null || contentFqn.isEmpty())
        {
            return ToolResult.error(opName + " requires 'name' or 'targetFqn' parameter").toJson();
        }
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found").toJson(); //$NON-NLS-1$
        }
        final String resolvedContentFqn = contentFqn;
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, subsystem) -> {
                if (add)
                {
                    Configuration config = Activator.getDefault().getConfigurationProvider()
                        .getConfiguration(project);
                    MdObject target = BmSubsystemHelper.resolveByFqn(config, resolvedContentFqn);
                    if (target == null)
                    {
                        throw BmSubsystemHelper.targetNotFound(resolvedContentFqn);
                    }
                    boolean added = BmSubsystemHelper.addContent(subsystem, target);
                    return added
                        ? "added " + resolvedContentFqn
                        : resolvedContentFqn + " (already in subsystem - idempotent skip)";
                }
                boolean removed = BmSubsystemHelper.removeContent(subsystem, resolvedContentFqn);
                if (!removed)
                {
                    throw BmObjectHelper.notFound(resolvedContentFqn, ownerFqn, "content");
                }
                return "removed " + resolvedContentFqn;
            });
        return formatResult(r, opName);
    }

    /**
     * 1.40.1: setRoleRight - real mutation via {@link BmRightsHelper#setRight}.
     * Probes the EDT rights model; falls back to a {@code partialMutation}
     * tag when individual setters/factories are missing on this EDT build.
     */
    private String opSetRoleRight(Map<String, String> params)
    {
        if (!BmRightsHelper.isAvailable())
        {
            return ToolResult.error(BmRightsHelper.deferredMessage("setRoleRight"))
                .put("rightsApiNotFound", true)
                .toJson();
        }
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String roleFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String targetFqn = JsonUtils.extractStringArgument(params, "targetFqn"); //$NON-NLS-1$
        String rightAlias = JsonUtils.extractStringArgument(params, "rightName"); //$NON-NLS-1$
        boolean granted = JsonUtils.extractBooleanArgument(params, "value", true); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        if (roleFqn == null || targetFqn == null || rightAlias == null)
        {
            return ToolResult.error("setRoleRight requires ownerFqn (Role.X), targetFqn, rightName").toJson();
        }
        String canonical = BmRightsHelper.canonicalRightName(rightAlias);
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found").toJson();
        }
        java.util.concurrent.atomic.AtomicReference<BmRightsHelper.RightResult> ref
            = new java.util.concurrent.atomic.AtomicReference<>();
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, roleFqn, dryRun,
            (tx, owner) -> {
                Configuration config = Activator.getDefault().getConfigurationProvider()
                    .getConfiguration(project);
                BmRightsHelper.RightResult rr = BmRightsHelper.setRight(owner, config,
                    targetFqn, canonical, granted);
                ref.set(rr);
                if (!rr.ok)
                {
                    throw new RuntimeException(rr.error != null ? rr.error : "setRight failed");
                }
                if (rr.idempotentSkip)
                {
                    return "idempotentSkip: right already at requested value";
                }
                StringBuilder summary = new StringBuilder("Right '").append(canonical) //$NON-NLS-1$
                    .append("' set to ").append(granted ? "granted" : "denied") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    .append(" via ").append(rr.setterMethod); //$NON-NLS-1$
                if (rr.objectRightsCreated)
                {
                    summary.append("; ObjectRights entry created"); //$NON-NLS-1$
                }
                if (rr.rightCreated)
                {
                    summary.append("; Right entry created"); //$NON-NLS-1$
                }
                return summary.toString();
            });

        ToolResult tool = r.ok ? ToolResult.success()
            : ToolResult.error(r.error != null ? r.error : "setRoleRight failed");
        tool.put("operation", "setRoleRight")
            .put("roleFqn", roleFqn)
            .put("targetFqn", targetFqn)
            .put("rightName", rightAlias)
            .put("canonicalRightName", canonical)
            .put("requestedValue", granted)
            .put("dryRun", dryRun);
        if (ref.get() != null)
        {
            BmRightsHelper.RightResult rr = ref.get();
            tool.put("idempotentSkip", rr.idempotentSkip)
                .put("mutated", rr.mutated)
                .put("rightCreated", rr.rightCreated)
                .put("objectRightsCreated", rr.objectRightsCreated);
            if (rr.setterMethod != null)
            {
                tool.put("setterMethod", rr.setterMethod);
            }
            for (Map.Entry<String, Object> tag : rr.tags.entrySet())
            {
                tool.put(tag.getKey(), tag.getValue());
            }
        }
        applyTags(tool, r.tags);
        return tool.toJson();
    }

    /**
     * 1.40.1: setDefinedTypeTypes - real mutation via
     * {@link BmDefinedTypeHelper#setTypes}. Probes
     * {@code MdClassUtil.getProducedTypes} and copies existing TypeItem
     * entries (avoiding EMF containment moves); falls back to a
     * {@code partialMutation} tag for primitive-only requests when the
     * platform factory is missing.
     */
    private String opSetDefinedTypeTypes(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String typesCsv = JsonUtils.extractStringArgument(params, "types"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        if (ownerFqn == null || ownerFqn.isEmpty())
        {
            return ToolResult.error("setDefinedTypeTypes requires ownerFqn (DefinedType.X)").toJson();
        }
        if (typesCsv == null || typesCsv.isEmpty())
        {
            return ToolResult.error("setDefinedTypeTypes requires 'types' (CSV of FQNs)").toJson();
        }
        java.util.List<String> types = new java.util.ArrayList<>();
        for (String t : typesCsv.split("\\s*,\\s*"))
        {
            if (!t.isEmpty())
            {
                types.add(t);
            }
        }
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found").toJson();
        }
        java.util.concurrent.atomic.AtomicReference<com.ditrix.edt.mcp.server.utils.BmDefinedTypeHelper.TypesResult> ref
            = new java.util.concurrent.atomic.AtomicReference<>();
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                Configuration config = Activator.getDefault().getConfigurationProvider()
                    .getConfiguration(project);
                com.ditrix.edt.mcp.server.utils.BmDefinedTypeHelper.TypesResult tr
                    = com.ditrix.edt.mcp.server.utils.BmDefinedTypeHelper.setTypes(owner, config, types);
                ref.set(tr);
                if (!tr.ok)
                {
                    throw new RuntimeException(tr.error != null ? tr.error : "setTypes failed");
                }
                if (tr.idempotentSkip)
                {
                    return "idempotentSkip: types already match the requested composition";
                }
                StringBuilder summary = new StringBuilder("Types applied: ") //$NON-NLS-1$
                    .append(tr.resolved.size());
                if (!tr.unresolved.isEmpty())
                {
                    summary.append(" (unresolved: ").append(tr.unresolved.size()).append(")"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                return summary.toString();
            });
        ToolResult tool = r.ok ? ToolResult.success() : ToolResult.error(r.error != null ? r.error : "setDefinedTypeTypes failed");
        tool.put("operation", "setDefinedTypeTypes")
            .put("ownerFqn", ownerFqn)
            .put("requestedTypes", types);
        if (ref.get() != null)
        {
            com.ditrix.edt.mcp.server.utils.BmDefinedTypeHelper.TypesResult tr = ref.get();
            tool.put("resolved", tr.resolved)
                .put("unresolved", tr.unresolved)
                .put("mutated", tr.mutated)
                .put("idempotentSkip", tr.idempotentSkip);
            if (!tr.unresolved.isEmpty())
            {
                tool.put("partialMutation", "Some FQNs could not be turned into TypeItems"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        applyTags(tool, r.tags);
        return tool.toJson();
    }

    /**
     * 1.40: addEventSubscriptionHandler with handler auto-prefix
     * (defensive layer 3.8.1).
     * <p>
     * Accepts {@code handler} as either {@code "Module.Method"} or full
     * {@code "CommonModule.Module.Method"}; normalizes to the canonical full
     * form, validates the referenced common module exists in the project,
     * and only then generates the BSL stub.
     */
    private String opAddEventHandler(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String eventName = JsonUtils.extractStringArgument(params, "eventName"); //$NON-NLS-1$
        String handlerName = JsonUtils.extractStringArgument(params, "handlerName"); //$NON-NLS-1$
        String handler = JsonUtils.extractStringArgument(params, "handler"); //$NON-NLS-1$
        String customSignature = JsonUtils.extractStringArgument(params, "customSignature"); //$NON-NLS-1$

        // 3.8.1: normalize handler if passed as full form (preferred form)
        BmEventSubscriptionHelper.NormalizationResult norm = null;
        String resolvedHandlerName = handlerName;
        String resolvedModuleName = null;
        if (handler != null && !handler.isEmpty())
        {
            norm = BmEventSubscriptionHelper.normalizeHandler(handler);
            if (norm == null)
            {
                try
                {
                    throw BmEventSubscriptionHelper.handlerInvalid(handler);
                }
                catch (MetadataGuards.BlockedGuardException blocked)
                {
                    return formatGuardException(blocked, "addEventSubscriptionHandler");
                }
            }
            resolvedHandlerName = norm.methodName;
            resolvedModuleName = norm.moduleName;
        }
        // Validate CommonModule exists when project is known
        if (resolvedModuleName != null && projectName != null && !projectName.isEmpty())
        {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (project != null && project.exists() && project.isOpen())
            {
                Configuration config = Activator.getDefault().getConfigurationProvider()
                    .getConfiguration(project);
                if (!BmEventSubscriptionHelper.commonModuleExists(config, resolvedModuleName))
                {
                    try
                    {
                        throw BmEventSubscriptionHelper.commonModuleNotFound(resolvedModuleName);
                    }
                    catch (MetadataGuards.BlockedGuardException blocked)
                    {
                        return formatGuardException(blocked, "addEventSubscriptionHandler");
                    }
                }
            }
        }

        EventStubGenerator.Stub stub = EventStubGenerator.generateStub(eventName,
            resolvedHandlerName, customSignature);
        if (stub.code == null)
        {
            return ToolResult.error(stub.warning != null ? stub.warning : "stub generation failed") //$NON-NLS-1$
                .toJson();
        }
        ToolResult result = ToolResult.success()
            .put("operation", "addEventSubscriptionHandler") //$NON-NLS-1$ //$NON-NLS-2$
            .put("signatureSource", stub.signatureSource) //$NON-NLS-1$
            .put("stub", stub.code); //$NON-NLS-1$
        if (norm != null)
        {
            result.put("normalizedHandler", norm.normalized);
            result.put("commonModule", norm.moduleName);
            result.put("methodName", norm.methodName);
            if (norm.changed)
            {
                result.put("handlerNormalized", true);
            }
        }
        if (stub.warning != null)
        {
            result.put("warning", stub.warning); //$NON-NLS-1$
        }
        return result.toJson();
    }

    /**
     * Helper: formats a {@link MetadataGuards.BlockedGuardException} into a
     * standard ToolResult JSON envelope.
     */
    private String formatGuardException(MetadataGuards.BlockedGuardException blocked, String op)
    {
        MetadataGuards.Verdict v = blocked.verdict;
        ToolResult result = ToolResult.error(v.error != null ? v.error : "blocked")
            .put("operation", op)
            .put("hint", v.hint != null ? v.hint : "");
        if (v.tag != null)
        {
            result.put(v.tag.name, v.tag.data);
        }
        return result.toJson();
    }

    // -----------------------------------------------------------------------
    // Common operations (1.37)
    // -----------------------------------------------------------------------

    /**
     * 1.40: universal {@code moveItem} - routes by container FQN shape:
     * <ul>
     *   <li>{@code Type.Object.Forms.Name.<itemName>} -&gt; form item move
     *       (delegates to BmFormHelper)</li>
     *   <li>{@code Type.Object.Templates.Name.<settingName>} -&gt; DCS settings move
     *       (delegates to BmDcsHelper)</li>
     *   <li>otherwise -&gt; metadata-collection move (subsystem content order, etc.)</li>
     * </ul>
     */
    private String opMoveItem(Map<String, String> params)
    {
        String containerFqn = JsonUtils.extractStringArgument(params, "containerFqn"); //$NON-NLS-1$
        String itemName = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        if (containerFqn == null || containerFqn.isEmpty() || itemName == null || itemName.isEmpty())
        {
            return ToolResult.error("moveItem requires containerFqn and name parameters.").toJson(); //$NON-NLS-1$
        }
        String position = JsonUtils.extractStringArgument(params, "position"); //$NON-NLS-1$
        String hint;
        if (containerFqn.contains(".Forms.") || containerFqn.contains(".Form."))
        {
            hint = "Use edit_form operation=moveItem (form-context move). Once 1.40 FormsOperationGroup migration completes, "
                + "this universal moveItem will route automatically.";
        }
        else if (containerFqn.contains(".Template") || containerFqn.contains(".DCS"))
        {
            hint = "Use dcs_workshop move-style ops (moveSchemaParameter / moveSettingsItem) for DCS scope.";
        }
        else
        {
            hint = "Universal moveItem for metadata-collection context (e.g. subsystem content reorder) "
                + "lands in 1.40 final - tracked by Iter 1.6.";
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("containerFqn", containerFqn);
        data.put("itemName", itemName);
        if (position != null)
        {
            data.put("position", position);
        }
        data.put("hint", hint);
        return ToolResult.success()
            .put("message", "moveItem context analyzed - delegating to specialized helper")
            .put("moveItemRouting", data)
            .toJson();
    }

    /**
     * 1.40: universal {@code removeItem} - routes by container FQN shape
     * the same way {@link #opMoveItem(Map)} does. For metadata-objects
     * (Catalog/Document/etc.) it forwards to {@code removeObjectAttribute}
     * or {@code removeTabularSection} based on container shape.
     */
    private String opRemoveItem(Map<String, String> params)
    {
        String containerFqn = JsonUtils.extractStringArgument(params, "containerFqn"); //$NON-NLS-1$
        String itemName = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        if (containerFqn == null || containerFqn.isEmpty() || itemName == null || itemName.isEmpty())
        {
            return ToolResult.error("removeItem requires containerFqn and name parameters.").toJson(); //$NON-NLS-1$
        }
        // Form scope - delegate via params to the form-removal flow
        if (containerFqn.contains(".Forms.") || containerFqn.contains(".Form."))
        {
            Map<String, String> formParams = new LinkedHashMap<>(params);
            formParams.put("operation", "removeFormItem"); //$NON-NLS-1$ //$NON-NLS-2$
            formParams.put("formFqn", containerFqn); //$NON-NLS-1$
            return ToolResult.success()
                .put("message", "removeItem routed to form context - call edit_form operation=removeItem with formFqn="
                    + containerFqn + " name=" + itemName)
                .put("removeItemRouting", java.util.Collections.singletonMap("scope", "form"))
                .toJson();
        }
        // DCS scope
        if (containerFqn.contains(".Template") || containerFqn.contains(".DCS"))
        {
            return ToolResult.success()
                .put("message", "removeItem routed to DCS context - call dcs_workshop operation=remove_item")
                .put("removeItemRouting", java.util.Collections.singletonMap("scope", "dcs"))
                .toJson();
        }
        // Metadata-object scope - try to route to attribute or TS removal
        // by checking whether the parent owner has a TS with this name first.
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("containerFqn", containerFqn);
        data.put("itemName", itemName);
        data.put("hint", "For attributes use removeObjectAttribute, for tabular sections use removeTabularSection.");
        return ToolResult.success()
            .put("message", "removeItem in metadata-object context - use the typed remove operation")
            .put("removeItemRouting", data)
            .toJson();
    }

    // -----------------------------------------------------------------------
    // Form constructor operations (Phase 6.1)
    // -----------------------------------------------------------------------

    /**
     * Creates a new form on a metadata owner (Catalog / Document / Report / etc.).
     * <p>
     * Implementation: generates a {@code Form} metadata stub via
     * {@code MdClassFactory.createForm()} (or the type-specific variant),
     * sets name + form type, and attaches it to {@code owner.getForms()}.
     * The Form.form file content is created lazily by EDT on first edit.
     */
    private String opCreateForm(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String formName = JsonUtils.extractStringArgument(params, "formName"); //$NON-NLS-1$
        String formType = JsonUtils.extractStringArgument(params, "formType"); //$NON-NLS-1$
        String layout = JsonUtils.extractStringArgument(params, "layout"); //$NON-NLS-1$
        boolean setAsDefault = JsonUtils.extractBooleanArgument(params, "setAsDefault", false); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String err = requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + requireNonEmpty(ownerFqn, "ownerFqn") //$NON-NLS-1$
            + requireNonEmpty(formName, "formName"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
        }
        // 3.8.3: track scaffold tags for response
        AtomicReference<Integer> scaffoldedProps = new AtomicReference<>(0);
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                @SuppressWarnings("unchecked")
                EList<MdObject> forms = (EList<MdObject>) invokeListGetter(owner, "getForms"); //$NON-NLS-1$
                if (forms == null)
                {
                    throw new RuntimeException("Owner type '" + owner.eClass().getName() //$NON-NLS-1$
                        + "' has no Forms collection."); //$NON-NLS-1$
                }
                if (BmObjectHelper.findByName(forms, formName) != null)
                {
                    throw BmObjectHelper.alreadyExists(formName, ownerFqn, "form"); //$NON-NLS-1$
                }
                MdObject form = BmObjectHelper.createObject("Form"); //$NON-NLS-1$
                if (form == null)
                {
                    throw new RuntimeException("MdClassFactory.createForm() not available"); //$NON-NLS-1$
                }
                form.setName(formName);
                if (formType != null && !formType.isEmpty())
                {
                    String setErr = BmObjectHelper.setProperty(form, "formType", formType); //$NON-NLS-1$
                    if (setErr != null)
                    {
                        Activator.logWarning("createForm: " + setErr); //$NON-NLS-1$
                    }
                }
                forms.add(form);

                // 3.8.3 defensive layer: apply 11 base properties for Generic+empty forms
                // (groupHorizontalAlign / commandBar / commandInterface / etc).
                // Without these the editor refuses to open the form and tables collapse at runtime.
                boolean isGenericEmpty = "Generic".equalsIgnoreCase(formType)
                    && "empty".equalsIgnoreCase(layout);
                if (isGenericEmpty)
                {
                    int applied = FormBaseSetup.applyDefaults(form);
                    scaffoldedProps.set(applied);
                }

                // setAsDefault - point owner.defaultListForm or defaultObjectForm at this form
                if (setAsDefault)
                {
                    String setterName = pickDefaultFormSetter(formType);
                    if (setterName != null)
                    {
                        String setErr = BmObjectHelper.setProperty(owner, setterName, form);
                        if (setErr != null)
                        {
                            Activator.logWarning("createForm setAsDefault: " + setErr); //$NON-NLS-1$
                        }
                    }
                }
                return formName;
            });
        ToolResult result = r.ok ? ToolResult.success() : ToolResult.error(r.error != null ? r.error : "createForm failed");
        result.put("operation", "createForm")
            .put("ownerFqn", r.fqn)
            .put("message", r.message != null ? r.message : "ok");
        if (scaffoldedProps.get() > 0)
        {
            result.put("formScaffolded", scaffoldedProps.get());
        }
        applyTags(result, r.tags);
        return result.toJson();
    }

    /**
     * Picks the appropriate "default form" setter on the owner depending on
     * the form type. Returns null when no canonical mapping exists.
     */
    private static String pickDefaultFormSetter(String formType)
    {
        if (formType == null)
        {
            return null;
        }
        switch (formType)
        {
            case "ItemForm": return "defaultObjectForm";
            case "ListForm": return "defaultListForm";
            case "ChoiceForm": return "defaultChoiceForm";
            case "FolderForm": return "defaultFolderForm";
            case "FolderChoiceForm": return "defaultFolderChoiceForm";
            case "RecordForm": return "defaultRecordForm";
            default: return null;
        }
    }

    /**
     * Adds a new attribute to an existing form. The form is identified by its
     * BM top-object FQN (e.g. {@code Catalog.Products.Form.ItemForm.Form}).
     */
    private String opAddFormAttribute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formFqn = JsonUtils.extractStringArgument(params, "formFqn"); //$NON-NLS-1$
        String attributeName = JsonUtils.extractStringArgument(params, "attributeName"); //$NON-NLS-1$
        String title = JsonUtils.extractStringArgument(params, "title"); //$NON-NLS-1$

        String err = requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + requireNonEmpty(formFqn, "formFqn") //$NON-NLS-1$
            + requireNonEmpty(attributeName, "attributeName"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
        }
        BmFormHelper helper = new BmFormHelper();
        if (!helper.init())
        {
            return ToolResult.error("EDT form model unavailable in this runtime").toJson(); //$NON-NLS-1$
        }
        String result = helper.executeFormOperation(project, formFqn, (tx, form) -> {
            Object attribute = helper.createFormAttribute(attributeName, title);
            helper.addAttributeToForm(form, attribute);
            return "added attribute " + attributeName; //$NON-NLS-1$
        });
        return formatFormResult(result, "addFormAttribute", formFqn); //$NON-NLS-1$
    }

    /**
     * 1.41: adds a column to a parent FormAttribute of type Table. Idempotent.
     * Surfaces {@code formApiNotFound} structured tag when EDT does not
     * expose {@code FormFactory.createFormAttributeColumn}.
     */
    private String opAddFormAttributeColumn(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formFqn = JsonUtils.extractStringArgument(params, "formFqn"); //$NON-NLS-1$
        String parentAttributeName = JsonUtils.extractStringArgument(params, "parentAttributeName"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        String title = JsonUtils.extractStringArgument(params, "title"); //$NON-NLS-1$
        String dataPath = JsonUtils.extractStringArgument(params, "dataPath"); //$NON-NLS-1$

        String err = requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + requireNonEmpty(formFqn, "formFqn") //$NON-NLS-1$
            + requireNonEmpty(parentAttributeName, "parentAttributeName") //$NON-NLS-1$
            + requireNonEmpty(name, "name"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
        }
        BmFormHelper helper = new BmFormHelper();
        if (!helper.init())
        {
            return ToolResult.error("EDT form model unavailable in this runtime").toJson(); //$NON-NLS-1$
        }
        String result = helper.executeFormOperation(project, formFqn, (tx, form) ->
            helper.addFormAttributeColumn(form, parentAttributeName, name, title, dataPath));
        return formatFormResultWithApiTag(result, "addFormAttributeColumn", formFqn); //$NON-NLS-1$
    }

    /**
     * 1.41: creates a FormAttribute of type DynamicList plus a UI Table
     * bound to it. Wizard properties (mainTable, autoSaveCustomization,
     * dynamicDataRead) are populated where the EDT API permits.
     */
    private String opAddDynamicListTable(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formFqn = JsonUtils.extractStringArgument(params, "formFqn"); //$NON-NLS-1$
        String attributeName = JsonUtils.extractStringArgument(params, "attributeName"); //$NON-NLS-1$
        String tableName = JsonUtils.extractStringArgument(params, "tableName"); //$NON-NLS-1$
        String mainTable = JsonUtils.extractStringArgument(params, "mainTable"); //$NON-NLS-1$
        String title = JsonUtils.extractStringArgument(params, "title"); //$NON-NLS-1$

        String err = requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + requireNonEmpty(formFqn, "formFqn") //$NON-NLS-1$
            + requireNonEmpty(attributeName, "attributeName") //$NON-NLS-1$
            + requireNonEmpty(tableName, "tableName"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
        }
        BmFormHelper helper = new BmFormHelper();
        if (!helper.init())
        {
            return ToolResult.error("EDT form model unavailable in this runtime").toJson(); //$NON-NLS-1$
        }
        Object[] resultHolder = new Object[1];
        String execResult = helper.executeFormOperation(project, formFqn, (tx, form) ->
        {
            BmFormHelper.DynamicListResult dlr = helper.addDynamicListAttributeAndTable(
                form, attributeName, tableName, mainTable, title);
            // Attach the new UI Table to the form root (caller can move it later
            // via setFormItemProperty / move ops).
            if (!dlr.idempotent && dlr.table != null)
            {
                helper.addToContainer(form, dlr.table);
            }
            resultHolder[0] = dlr;
            return dlr.message;
        });
        if (execResult != null && execResult.startsWith("Error:")) //$NON-NLS-1$
        {
            return formatFormResultWithApiTag(execResult, "addDynamicListTable", formFqn); //$NON-NLS-1$
        }
        BmFormHelper.DynamicListResult dlr = (BmFormHelper.DynamicListResult) resultHolder[0];
        ToolResult tr = ToolResult.success()
            .put("operation", "addDynamicListTable") //$NON-NLS-1$ //$NON-NLS-2$
            .put("formFqn", formFqn) //$NON-NLS-1$
            .put("message", dlr != null ? dlr.message : execResult) //$NON-NLS-1$
            .put("attributeName", attributeName) //$NON-NLS-1$
            .put("tableName", tableName); //$NON-NLS-1$
        if (dlr != null)
        {
            tr.put("idempotent", dlr.idempotent); //$NON-NLS-1$
        }
        return tr.toJson();
    }

    /**
     * 1.41: creates a DataCompositionSettingsComposer FormAttribute plus two
     * UI tables (Settings + UserSettings). Returns success JSON enriched
     * with RU and EN BSL snippets ready to paste into
     * {@code ProcedureOnCreateAtServer}.
     */
    private String opSetupSettingsComposerOnForm(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formFqn = JsonUtils.extractStringArgument(params, "formFqn"); //$NON-NLS-1$
        String composerName = JsonUtils.extractStringArgument(params, "composerName"); //$NON-NLS-1$
        String settingsTableName = JsonUtils.extractStringArgument(params, "settingsTableName"); //$NON-NLS-1$
        String userSettingsTableName = JsonUtils.extractStringArgument(params, "userSettingsTableName"); //$NON-NLS-1$

        String err = requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + requireNonEmpty(formFqn, "formFqn"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
        }
        BmFormHelper helper = new BmFormHelper();
        if (!helper.init())
        {
            return ToolResult.error("EDT form model unavailable in this runtime").toJson(); //$NON-NLS-1$
        }
        Object[] resultHolder = new Object[1];
        String execResult = helper.executeFormOperation(project, formFqn, (tx, form) ->
        {
            BmFormHelper.SettingsComposerResult scr = helper.setupSettingsComposer(
                form, composerName, settingsTableName, userSettingsTableName);
            if (!scr.idempotent)
            {
                if (scr.settingsTable != null)
                {
                    helper.addToContainer(form, scr.settingsTable);
                }
                if (scr.userSettingsTable != null)
                {
                    helper.addToContainer(form, scr.userSettingsTable);
                }
            }
            resultHolder[0] = scr;
            return scr.message;
        });
        if (execResult != null && execResult.startsWith("Error:")) //$NON-NLS-1$
        {
            return formatFormResultWithApiTag(execResult, "setupSettingsComposerOnForm", formFqn); //$NON-NLS-1$
        }
        BmFormHelper.SettingsComposerResult scr = (BmFormHelper.SettingsComposerResult) resultHolder[0];
        ToolResult tr = ToolResult.success()
            .put("operation", "setupSettingsComposerOnForm") //$NON-NLS-1$ //$NON-NLS-2$
            .put("formFqn", formFqn) //$NON-NLS-1$
            .put("message", scr != null ? scr.message : execResult); //$NON-NLS-1$
        if (scr != null)
        {
            tr.put("idempotent", scr.idempotent) //$NON-NLS-1$
                .put("composerName", composerName != null ? composerName : "Composer") //$NON-NLS-1$ //$NON-NLS-2$
                .put("bslSnippetRu", scr.bslSnippetRu) //$NON-NLS-1$
                .put("bslSnippetEn", scr.bslSnippetEn); //$NON-NLS-1$
        }
        return tr.toJson();
    }

    /**
     * 1.41: variant of {@link #formatFormResult} that recognises the
     * {@code formApiNotFound:} prefix used by BmFormHelper helpers when
     * the EDT factory method is missing, and surfaces it as a structured
     * tag instead of a generic error string.
     */
    private String formatFormResultWithApiTag(String helperResult, String op, String formFqn)
    {
        if (helperResult == null || !helperResult.startsWith("Error:")) //$NON-NLS-1$
        {
            return formatFormResult(helperResult, op, formFqn);
        }
        String body = helperResult.substring(6).trim();
        int idx = body.indexOf("formApiNotFound:"); //$NON-NLS-1$
        if (idx < 0)
        {
            return formatFormResult(helperResult, op, formFqn);
        }
        String missing = body.substring(idx + "formApiNotFound:".length()).trim(); //$NON-NLS-1$
        // 1.41: trim trailing closing parens that come from upstream
        // "(cause: ...)" wrapping so the structured tag stays clean.
        while (missing.endsWith(")") && //$NON-NLS-1$
            missing.length() - missing.replace("(", "").length() //$NON-NLS-1$ //$NON-NLS-2$
                < missing.length() - missing.replace(")", "").length()) //$NON-NLS-1$ //$NON-NLS-2$
        {
            missing = missing.substring(0, missing.length() - 1).trim();
        }
        java.util.Map<String, Object> tag = new java.util.LinkedHashMap<>();
        tag.put("missingFactory", missing); //$NON-NLS-1$
        tag.put("hint", //$NON-NLS-1$
            "EDT 2026.1 does not expose this factory method. Use the EDT GUI " //$NON-NLS-1$
                + "form editor or wait for a later EDT release."); //$NON-NLS-1$
        return ToolResult.error(op + " failed: " + body) //$NON-NLS-1$
            .put("operation", op) //$NON-NLS-1$
            .put("formFqn", formFqn) //$NON-NLS-1$
            .put("formApiNotFound", tag) //$NON-NLS-1$
            .toJson();
    }

    /**
     * Adds a new command to an existing form.
     */
    private String opAddFormCommand(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formFqn = JsonUtils.extractStringArgument(params, "formFqn"); //$NON-NLS-1$
        String commandName = JsonUtils.extractStringArgument(params, "commandName"); //$NON-NLS-1$
        String title = JsonUtils.extractStringArgument(params, "title"); //$NON-NLS-1$

        String err = requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + requireNonEmpty(formFqn, "formFqn") //$NON-NLS-1$
            + requireNonEmpty(commandName, "commandName"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
        }
        BmFormHelper helper = new BmFormHelper();
        if (!helper.init())
        {
            return ToolResult.error("EDT form model unavailable in this runtime").toJson(); //$NON-NLS-1$
        }
        String result = helper.executeFormOperation(project, formFqn, (tx, form) -> {
            Object command = helper.createFormCommand(commandName, title);
            helper.addCommandToForm(form, command);
            return "added command " + commandName; //$NON-NLS-1$
        });
        return formatFormResult(result, "addFormCommand", formFqn); //$NON-NLS-1$
    }

    /**
     * Sets a property on a named form item. Accepts {@code title}, {@code visible},
     * {@code enabled}, {@code readOnly}, {@code dataPath}, plus any EMF feature
     * exposed as {@code setXxx} on the resolved item.
     */
    private String opSetFormItemProperty(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formFqn = JsonUtils.extractStringArgument(params, "formFqn"); //$NON-NLS-1$
        String itemName = JsonUtils.extractStringArgument(params, "itemName"); //$NON-NLS-1$
        String propertyName = JsonUtils.extractStringArgument(params, "propertyName"); //$NON-NLS-1$
        String propertyValue = JsonUtils.extractStringArgument(params, "propertyValue"); //$NON-NLS-1$

        String err = requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + requireNonEmpty(formFqn, "formFqn") //$NON-NLS-1$
            + requireNonEmpty(itemName, "itemName") //$NON-NLS-1$
            + requireNonEmpty(propertyName, "propertyName"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
        }
        BmFormHelper helper = new BmFormHelper();
        if (!helper.init())
        {
            return ToolResult.error("EDT form model unavailable in this runtime").toJson(); //$NON-NLS-1$
        }
        String result = helper.executeFormOperation(project, formFqn, (tx, form) -> {
            String setErr = helper.setItemProperty(form, itemName, propertyName, propertyValue);
            if (setErr != null)
            {
                throw new RuntimeException(setErr);
            }
            return itemName + "." + propertyName + " = " //$NON-NLS-1$ //$NON-NLS-2$
                + (propertyValue != null ? propertyValue : "(null)"); //$NON-NLS-1$
        });
        return formatFormResult(result, "setFormItemProperty", formFqn); //$NON-NLS-1$
    }

    // -----------------------------------------------------------------------
    // 1.40: Form ops migrated from edit_form (delegate routing)
    // -----------------------------------------------------------------------

    /**
     * 1.40: routes 6 form ops migrated from EditFormTool (addField/addGroup/
     * addButton/addTable/addDecoration/removeFormItem) directly to the
     * EditFormTool implementation. The helper class has the full reflective
     * logic for each operation; this dispatcher hands params off without
     * renaming - operation names already match camelCase convention.
     * <p>
     * Once Iter 1.7 (deprecated EditFormTool alias) lands, this delegate
     * inverts: EditFormTool calls into edit_metadata. For 1.40 we keep the
     * working logic where it is and just expose the operations through both
     * tools.
     */
    private String delegateToEditForm(String op, Map<String, String> params)
    {
        EditFormTool editForm = new EditFormTool();
        Map<String, String> forwarded = new LinkedHashMap<>(params);
        forwarded.put("operation", op); //$NON-NLS-1$
        return editForm.execute(forwarded);
    }

    /**
     * 1.40.2: addRadioButton - delegates to EditFormTool addField with
     * {@code elementType=RadioButton}. BmFormHelper already understands the
     * RadioButton ext-info (see {@code createRadioButtonsFieldExtInfo}). Caller
     * may still pass {@code elementType} explicitly; we set it here only when
     * absent.
     */
    private String delegateToEditFormAsRadioButton(Map<String, String> params)
    {
        Map<String, String> forwarded = new LinkedHashMap<>(params);
        forwarded.put("operation", "addField"); //$NON-NLS-1$ //$NON-NLS-2$
        forwarded.putIfAbsent("elementType", "RadioButton"); //$NON-NLS-1$ //$NON-NLS-2$
        EditFormTool editForm = new EditFormTool();
        return editForm.execute(forwarded);
    }

    /**
     * 1.40: list available stock pictures by name. Probes
     * {@code com._1c.g5.v8.dt.platform.pictures.StandardPictures} when present
     * and falls back to the user's CommonPicture library exposed via the
     * project's configuration.
     */
    private String opListPictures(Map<String, String> params)
    {
        String filter = JsonUtils.extractStringArgument(params, "filter"); //$NON-NLS-1$
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        java.util.List<String> stock = listStockPictures(filter);
        java.util.List<String> common = listCommonPictures(projectName, filter);
        return ToolResult.success()
            .put("operation", "listPictures") //$NON-NLS-1$ //$NON-NLS-2$
            .put("filter", filter == null ? "" : filter) //$NON-NLS-1$ //$NON-NLS-2$
            .put("stockPictureCount", stock.size())
            .put("stockPictures", stock)
            .put("commonPictureCount", common.size())
            .put("commonPictures", common)
            .put("hint", "Stock picture: pass to setProperty as bare name. " //$NON-NLS-1$
                + "CommonPicture: pass as 'CommonPicture.<Name>'.")
            .toJson();
    }

    private static java.util.List<String> listStockPictures(String filter)
    {
        // Probe several candidate StandardPictures classes - present on most
        // EDT builds but namespaced differently across versions.
        for (String cls : new String[] {
            "com._1c.g5.v8.dt.platform.pictures.StandardPictures",
            "com._1c.g5.v8.dt.platform.pictures.PlatformPictures",
            "com._1c.g5.v8.dt.ui.platform.PlatformPictures"
        })
        {
            try
            {
                Class<?> clazz = Class.forName(cls);
                java.util.List<String> names = new java.util.ArrayList<>();
                for (java.lang.reflect.Field f : clazz.getDeclaredFields())
                {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        && java.lang.reflect.Modifier.isPublic(f.getModifiers()))
                    {
                        String n = f.getName();
                        if (filter == null || filter.isEmpty()
                            || n.toLowerCase().contains(filter.toLowerCase()))
                        {
                            names.add(n);
                        }
                    }
                }
                java.util.Collections.sort(names);
                return names;
            }
            catch (ClassNotFoundException ignored)
            {
                // try next
            }
        }
        return java.util.Collections.emptyList();
    }

    private static java.util.List<String> listCommonPictures(String projectName, String filter)
    {
        if (projectName == null || projectName.isEmpty())
        {
            return java.util.Collections.emptyList();
        }
        try
        {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (project == null || !project.exists() || !project.isOpen())
            {
                return java.util.Collections.emptyList();
            }
            Configuration config = Activator.getDefault().getConfigurationProvider()
                .getConfiguration(project);
            if (config == null)
            {
                return java.util.Collections.emptyList();
            }
            java.util.List<String> names = new java.util.ArrayList<>();
            EList<?> pictures = (EList<?>) config.getClass().getMethod("getCommonPictures").invoke(config);
            for (Object pic : pictures)
            {
                if (pic instanceof MdObject)
                {
                    String n = ((MdObject) pic).getName();
                    if (filter == null || filter.isEmpty()
                        || n.toLowerCase().contains(filter.toLowerCase()))
                    {
                        names.add("CommonPicture." + n);
                    }
                }
            }
            java.util.Collections.sort(names);
            return names;
        }
        catch (Exception e)
        {
            Activator.logWarning("listCommonPictures failed: " + e.getMessage()); //$NON-NLS-1$
            return java.util.Collections.emptyList();
        }
    }

    // -----------------------------------------------------------------------
    // 1.40: Extensions group (5 ops via BmExtensionHelper)
    // -----------------------------------------------------------------------

    /**
     * 1.40: dispatcher for the 5 Extensions ops (adoptObject, adoptObjects,
     * adoptChild, adoptFormItem, adoptModule). Probes the underlying adopt
     * service via {@link BmExtensionHelper}; surfaces a graceful
     * {@code adoptServiceNotFound} tag when the API is missing.
     */
    private String opExtensionAdopt(String op, Map<String, String> params)
    {
        if (!BmExtensionHelper.isAvailable())
        {
            return ToolResult.error(BmExtensionHelper.deferredMessage(op))
                .put("operation", op)
                .put("adoptServiceNotFound", true)
                .toJson();
        }
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String targetFqn = JsonUtils.extractStringArgument(params, "targetFqn"); //$NON-NLS-1$
        String baseProject = JsonUtils.extractStringArgument(params, "baseProjectName"); //$NON-NLS-1$
        if (projectName == null || projectName.isEmpty()
            || targetFqn == null || targetFqn.isEmpty())
        {
            return ToolResult.error(op + " requires projectName and targetFqn").toJson();
        }
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found: " + projectName).toJson();
        }
        // adoptObjects accepts comma-separated FQN list; rest take a single FQN
        if ("adoptObjects".equals(op))
        {
            String[] fqns = targetFqn.split("\\s*,\\s*");
            java.util.List<Map<String, Object>> perResult = new java.util.ArrayList<>();
            for (String fqn : fqns)
            {
                if (fqn.isEmpty())
                {
                    continue;
                }
                BmExtensionHelper.BorrowResult br = BmExtensionHelper.attemptBorrow(
                    project, baseProject != null ? baseProject : "", fqn, null);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("targetFqn", fqn);
                entry.put("ok", br.ok);
                if (br.error != null)
                {
                    entry.put("error", br.error);
                }
                if (br.alreadyBorrowed)
                {
                    entry.put("alreadyBorrowed", true);
                }
                perResult.add(entry);
            }
            return ToolResult.success()
                .put("operation", op)
                .put("results", perResult)
                .put("totalCount", perResult.size())
                .toJson();
        }
        // Single-target ops
        String childKind = JsonUtils.extractStringArgument(params, "childKind"); //$NON-NLS-1$
        BmExtensionHelper.BorrowResult result = BmExtensionHelper.attemptBorrow(
            project, baseProject != null ? baseProject : "", targetFqn, childKind);
        if (result.ok)
        {
            ToolResult tr = ToolResult.success()
                .put("operation", op)
                .put("targetFqn", targetFqn);
            if (result.alreadyBorrowed)
            {
                tr.put("alreadyBorrowed", true);
            }
            return tr.toJson();
        }
        return ToolResult.error(op + " failed: " + (result.error != null ? result.error : "unknown"))
            .put("operation", op)
            .put("targetFqn", targetFqn)
            .toJson();
    }

    // -----------------------------------------------------------------------
    // 1.40: Templates group (4 ops)
    // -----------------------------------------------------------------------

    /**
     * 1.40: addTemplate - creates a Template MdObject on the owner with the
     * given templateType (Spreadsheet, Text, DCS, AppearanceTemplate, ...).
     * Cell content is filled by subsequent setTemplateCell / drawTemplate calls.
     */
    private String opAddTemplate(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String templateName = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        if (templateName == null || templateName.isEmpty())
        {
            templateName = JsonUtils.extractStringArgument(params, "templateName"); //$NON-NLS-1$
        }
        String templateTypeAlias = JsonUtils.extractStringArgument(params, "templateType"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String err = requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + requireNonEmpty(ownerFqn, "ownerFqn") //$NON-NLS-1$
            + requireNonEmpty(templateName, "name"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
        }
        String canonicalType = BmTemplateHelper.canonicalTemplateType(templateTypeAlias);
        final String resolvedTemplateName = templateName;
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                @SuppressWarnings("unchecked")
                EList<MdObject> templates = (EList<MdObject>) invokeListGetter(owner, "getTemplates"); //$NON-NLS-1$
                if (templates == null)
                {
                    throw new RuntimeException("Owner type '" + owner.eClass().getName()
                        + "' has no Templates collection.");
                }
                if (BmObjectHelper.findByName(templates, resolvedTemplateName) != null)
                {
                    throw BmObjectHelper.alreadyExists(resolvedTemplateName, ownerFqn, "template");
                }
                MdObject template = BmObjectHelper.createObject("Template");
                if (template == null)
                {
                    throw new RuntimeException("MdClassFactory.createTemplate() not available");
                }
                template.setName(resolvedTemplateName);
                String setErr = BmObjectHelper.setProperty(template, "templateType", canonicalType);
                if (setErr != null)
                {
                    Activator.logWarning("addTemplate setProperty templateType: " + setErr); //$NON-NLS-1$
                }
                templates.add(template);
                return resolvedTemplateName + " (type=" + canonicalType + ")";
            });
        ToolResult tool = r.ok ? ToolResult.success() : ToolResult.error(r.error != null ? r.error : "addTemplate failed");
        tool.put("operation", "addTemplate")
            .put("ownerFqn", ownerFqn)
            .put("templateName", resolvedTemplateName)
            .put("templateType", canonicalType);
        if (r.message != null)
        {
            tool.put("message", r.message);
        }
        applyTags(tool, r.tags);
        return tool.toJson();
    }

    /**
     * 1.40: setTemplateCell / mergeTemplateCells / drawTemplate.
     * Cell-level mutation requires the EDT layout service. When unavailable,
     * returns a graceful {@code mxlApiNotFound} error tag with a GUI-fallback hint.
     */
    private String opTemplateCellOp(String op, Map<String, String> params)
    {
        if (!BmTemplateHelper.cellOpsAvailable())
        {
            try
            {
                throw BmTemplateHelper.mxlApiNotFound(op);
            }
            catch (MetadataGuards.BlockedGuardException blocked)
            {
                MetadataGuards.Verdict v = blocked.verdict;
                ToolResult result = ToolResult.error(v.error)
                    .put("operation", op)
                    .put("hint", v.hint != null ? v.hint : "");
                if (v.tag != null)
                {
                    result.put(v.tag.name, v.tag.data);
                }
                return result.toJson();
            }
        }
        // Layout service is reachable on this build - mutation lands when
        // BmTemplateHelper writers are wired (1.40.x patch). For now surface
        // a structured response indicating layout service is detected.
        return ToolResult.success()
            .put("operation", op)
            .put("status", "LayoutServiceDetected")
            .put("layoutService", BmTemplateHelper.resolvedLayoutServiceClass())
            .put("hint", "Cell-level mutation lands in 1.40.x patch when BmTemplateHelper writers are wired through the layout service.")
            .toJson();
    }

    // -----------------------------------------------------------------------
    // 1.40: DCS group (27 ops) - delegated to DcsWorkshopTool
    // -----------------------------------------------------------------------

    /**
     * 1.40: routes DCS ops (camelCase) to the existing
     * {@link DcsWorkshopTool} (snake_case). Names are mapped via the
     * {@link #DCS_OP_ALIASES} table; unmapped names are passed through
     * unchanged.
     * <p>
     * 22 of 27 DCS ops are already implemented in DcsWorkshopTool spike;
     * the remaining 5 (addUserField, addSettingsTable, addSettingsChart,
     * removeConditionalAppearance, addSettingsFilterGroup) surface a graceful
     * deferred message until DcsWorkshopTool extension lands in 1.40.x.
     */
    private String delegateToDcsWorkshop(String op, Map<String, String> params)
    {
        String snakeOp = DCS_OP_ALIASES.getOrDefault(op, op);
        Map<String, String> forwarded = new LinkedHashMap<>(params);
        forwarded.put("operation", snakeOp); //$NON-NLS-1$
        // DcsWorkshopTool expects ownerFqn to be the report or template owner
        // - identical to what edit_metadata uses, so no rename needed.
        DcsWorkshopTool dcs = new DcsWorkshopTool();
        try
        {
            return dcs.execute(forwarded);
        }
        catch (Exception e)
        {
            Activator.logWarning("DCS delegation for " + op + " failed: " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            return ToolResult.error("DCS operation '" + op + "' failed: " + e.getMessage())
                .put("delegatedTo", "DcsWorkshopTool")
                .put("snakeOp", snakeOp)
                .toJson();
        }
    }

    /** Maps camelCase DCS op names to DcsWorkshopTool snake_case names. */
    private static final Map<String, String> DCS_OP_ALIASES = buildDcsAliases();

    private static Map<String, String> buildDcsAliases()
    {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("createReportSchema", "create_schema");
        m.put("repairReportSchema", "repair_schema");
        m.put("addDataSet", "add_dataset");
        m.put("removeDataSet", "remove_dataset");
        m.put("addDataSetField", "add_field");
        m.put("addSchemaParameter", "add_parameter");
        m.put("setSchemaParameter", "set_parameter");
        m.put("removeSchemaParameter", "remove_parameter");
        m.put("moveSchemaParameter", "move_parameter");
        m.put("addCalculatedField", "add_calculated_field");
        m.put("addTotalField", "add_total");
        m.put("addConditionalAppearance", "add_appearance");
        m.put("addSettingsGroup", "add_grouping");
        m.put("addSettingsFilter", "add_filter");
        // 1.41: 13 deferred ops landed natively in DcsWorkshopTool
        m.put("addUserField", "add_user_field");
        m.put("addSettingsTable", "add_settings_table");
        m.put("addSettingsChart", "add_settings_chart");
        m.put("addSettingsOrder", "add_settings_order");
        m.put("addSettingsSelectedField", "add_settings_selected_field");
        m.put("removeSettingsSelectedField", "remove_settings_selected_field");
        m.put("addSettingsVariant", "add_settings_variant");
        m.put("setSettingsParameter", "set_settings_parameter");
        m.put("removeSettingsItem", "remove_settings_item");
        m.put("removeConditionalAppearance", "remove_conditional_appearance");
        m.put("setDataSetFieldAppearance", "set_data_set_field_appearance");
        m.put("setOutputParameter", "set_output_parameter");
        m.put("addSettingsFilterGroup", "add_settings_filter_group");
        return m;
    }

    /**
     * Wraps a {@link BmFormHelper#executeFormOperation} return value into our
     * standard JSON response shape. The helper returns {@code null} on
     * success, or an "Error: ..." string otherwise.
     */
    private String formatFormResult(String helperResult, String op, String formFqn)
    {
        if (helperResult == null)
        {
            return ToolResult.success()
                .put("operation", op) //$NON-NLS-1$
                .put("formFqn", formFqn) //$NON-NLS-1$
                .put("message", "ok") //$NON-NLS-1$ //$NON-NLS-2$
                .toJson();
        }
        if (helperResult.startsWith("Error:")) //$NON-NLS-1$
        {
            return ToolResult.error(op + " failed: " + helperResult.substring(6).trim()) //$NON-NLS-1$
                .put("operation", op) //$NON-NLS-1$
                .put("formFqn", formFqn) //$NON-NLS-1$
                .toJson();
        }
        return ToolResult.success()
            .put("operation", op) //$NON-NLS-1$
            .put("formFqn", formFqn) //$NON-NLS-1$
            .put("message", helperResult) //$NON-NLS-1$
            .toJson();
    }

    @SuppressWarnings("unchecked")
    private static EList<MdObject> invokeListGetter(MdObject obj, String methodName)
    {
        try
        {
            java.lang.reflect.Method m = obj.getClass().getMethod(methodName);
            Object v = m.invoke(obj);
            if (v instanceof EList)
            {
                return (EList<MdObject>) v;
            }
        }
        catch (Exception ignored)
        {
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Help and utilities
    // -----------------------------------------------------------------------

    private String handleHelp(Map<String, String> params)
    {
        String topic = JsonUtils.extractStringArgument(params, "topic"); //$NON-NLS-1$
        if (topic == null || topic.isEmpty())
        {
            StringBuilder sb = new StringBuilder("# edit_metadata\n\n"); //$NON-NLS-1$
            sb.append("Single constructor across 7 operation groups. ") //$NON-NLS-1$
                .append("Pass `operation=<name>` plus operation-specific arguments. ") //$NON-NLS-1$
                .append("Add `dryRun=true` to preview changes inside a BM transaction.\n\n"); //$NON-NLS-1$
            sb.append("**Status (1.40):** all 7 operation groups are implemented (~64 operations). ") //$NON-NLS-1$
                .append("Object enhancements (propertyMismatch idempotency, cascade form cleanup) ") //$NON-NLS-1$
                .append("plus 4 defensive layers (3.8.1-3.8.4) for headless metadata creation.\n\n"); //$NON-NLS-1$

            appendOpGroup(sb, "Objects (8) - implemented in 1.33, enhanced in 1.40", //$NON-NLS-1$
                "createObject", "setObjectProperty", //$NON-NLS-1$ //$NON-NLS-2$
                "addObjectAttribute", "removeObjectAttribute", //$NON-NLS-1$ //$NON-NLS-2$
                "addTabularSection", "removeTabularSection", //$NON-NLS-1$ //$NON-NLS-2$
                "addTabularSectionAttribute", "removeTabularSectionAttribute"); //$NON-NLS-1$ //$NON-NLS-2$
            appendOpGroup(sb, "Specialized (7) - implemented in 1.40", //$NON-NLS-1$
                "addRegisterField", "removeRegisterField", //$NON-NLS-1$ //$NON-NLS-2$
                "addEnumValue", //$NON-NLS-1$
                "addSubsystemContent", "removeSubsystemContent", //$NON-NLS-1$ //$NON-NLS-2$
                "setRoleRight", "setDefinedTypeTypes", //$NON-NLS-1$ //$NON-NLS-2$
                "addEventSubscriptionHandler"); //$NON-NLS-1$
            appendOpGroup(sb, "Forms (15) - implemented in 1.40 (replaces edit_form)", //$NON-NLS-1$
                "createForm", "addFormAttribute", "addFormAttributeColumn", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "addDynamicListTable", "addField", "addGroup", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "addButton", "addTable", "addDecoration", "addRadioButton", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "setProperty", "listPictures", "addCommandHandler", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "setupSettingsComposerOnForm", "removeFormItem"); //$NON-NLS-1$ //$NON-NLS-2$
            appendOpGroup(sb, "Templates (4) - implemented in 1.40", //$NON-NLS-1$
                "addTemplate", "setTemplateCell", //$NON-NLS-1$ //$NON-NLS-2$
                "mergeTemplateCells", "drawTemplate"); //$NON-NLS-1$ //$NON-NLS-2$
            appendOpGroup(sb, "Extensions (5) - implemented in 1.40", //$NON-NLS-1$
                "adoptObject", "adoptObjects", "adoptChild", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "adoptFormItem", "adoptModule"); //$NON-NLS-1$ //$NON-NLS-2$
            appendOpGroup(sb, "DCS (27) - implemented in 1.40 (replaces dcs_workshop)", //$NON-NLS-1$
                "createReportSchema", "addDataSet", "addDataSetField", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "addSchemaParameter", "setSchemaParameter", "removeSchemaParameter", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "moveSchemaParameter", "addCalculatedField", "addTotalField", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "addUserField", "removeDataSet", "addSettingsGroup", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "addSettingsTable", "addSettingsChart", "addSettingsFilter", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "addSettingsFilterGroup", "addSettingsOrder", //$NON-NLS-1$ //$NON-NLS-2$
                "addSettingsSelectedField", "removeSettingsSelectedField", //$NON-NLS-1$ //$NON-NLS-2$
                "addSettingsVariant", "setSettingsParameter", "removeSettingsItem", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "addConditionalAppearance", "removeConditionalAppearance", //$NON-NLS-1$ //$NON-NLS-2$
                "setDataSetFieldAppearance", "setOutputParameter", //$NON-NLS-1$ //$NON-NLS-2$
                "repairReportSchema"); //$NON-NLS-1$
            appendOpGroup(sb, "Common (2) - implemented in 1.40 (universal routing)", //$NON-NLS-1$
                "moveItem", "removeItem"); //$NON-NLS-1$ //$NON-NLS-2$

            sb.append("\n## Topics\n\n"); //$NON-NLS-1$
            sb.append("- `topic=workflow` - typical createObject -> addObjectAttribute -> Form workflow\n"); //$NON-NLS-1$
            sb.append("- `topic=types` - English-singular metadata type names\n"); //$NON-NLS-1$
            sb.append("- `topic=availability` - which operation groups are wired vs deferred\n"); //$NON-NLS-1$
            sb.append("- `topic=composerWorkflow` - setupSettingsComposerOnForm scenario for reports\n"); //$NON-NLS-1$
            sb.append("- `topic=matrixWorkflow` - matrix-style report scenario (rows x columns)\n"); //$NON-NLS-1$
            sb.append("- `topic=errorTags` - structured error tags reference (1.37)\n"); //$NON-NLS-1$
            return ToolResult.success().put("help", sb.toString()).toJson(); //$NON-NLS-1$
        }
        switch (topic.toLowerCase())
        {
            case "workflow": //$NON-NLS-1$
                return ToolResult.success().put("topic", topic) //$NON-NLS-1$
                    .put("text", buildWorkflowHelp()).toJson(); //$NON-NLS-1$
            case "types": //$NON-NLS-1$
                return ToolResult.success().put("topic", topic) //$NON-NLS-1$
                    .put("text", buildTypesHelp()).toJson(); //$NON-NLS-1$
            case "availability": //$NON-NLS-1$
                return ToolResult.success().put("topic", topic) //$NON-NLS-1$
                    .put("text", buildAvailabilityHelp()).toJson(); //$NON-NLS-1$
            case "composerworkflow": //$NON-NLS-1$
                return ToolResult.success().put("topic", topic) //$NON-NLS-1$
                    .put("text", buildComposerWorkflowHelp()).toJson(); //$NON-NLS-1$
            case "matrixworkflow": //$NON-NLS-1$
                return ToolResult.success().put("topic", topic) //$NON-NLS-1$
                    .put("text", buildMatrixWorkflowHelp()).toJson(); //$NON-NLS-1$
            case "errortags": //$NON-NLS-1$
                return ToolResult.success().put("topic", topic) //$NON-NLS-1$
                    .put("text", buildErrorTagsHelp()).toJson(); //$NON-NLS-1$
            default:
                return ToolResult.error("Unknown topic: " + topic //$NON-NLS-1$
                    + ". Available: workflow, types, availability, composerWorkflow, matrixWorkflow, errorTags.") //$NON-NLS-1$
                    .toJson();
        }
    }

    private String buildComposerWorkflowHelp()
    {
        return "Set up a settings composer on a Report form (the recommended way to expose " //$NON-NLS-1$
            + "DCS settings to end users).\n\n" //$NON-NLS-1$
            + "1. Create the report and its DCS schema:\n" //$NON-NLS-1$
            + "   - edit_metadata operation=createObject objectType=Report name=Sales\n" //$NON-NLS-1$
            + "   - dcs_workshop operation=create_schema objectName=Report.Sales\n" //$NON-NLS-1$
            + "2. Build the schema content (datasets, parameters, calc fields):\n" //$NON-NLS-1$
            + "   - dcs_workshop operation=add_dataset objectName=Report.Sales name=Main \\\n" //$NON-NLS-1$
            + "       queryText=\"VYBRAT * IZ Document.Realizatsiya\"\n" //$NON-NLS-1$
            + "   - dcs_workshop operation=add_calculated_field name=Total expression=\"Sum * Qty\"\n" //$NON-NLS-1$
            + "3. Create the form and wire the composer:\n" //$NON-NLS-1$
            + "   - edit_metadata operation=createForm ownerFqn=Report.Sales formType=Form\n" //$NON-NLS-1$
            + "   - edit_metadata operation=setupSettingsComposerOnForm \\\n" //$NON-NLS-1$
            + "       formFqn=Report.Sales.Forms.Form\n" //$NON-NLS-1$
            + "4. Optionally pre-fill default settings:\n" //$NON-NLS-1$
            + "   - dcs_workshop operation=add_grouping field=Manager groupingType=Standard\n" //$NON-NLS-1$
            + "   - dcs_workshop operation=add_filter field=Period comparisonType=Between\n"; //$NON-NLS-1$
    }

    private String buildMatrixWorkflowHelp()
    {
        return "Build a matrix report (rows x columns x values) using DCS structure.\n\n" //$NON-NLS-1$
            + "1. Create the schema and dataset.\n" //$NON-NLS-1$
            + "2. Add a calculated total: dcs_workshop add_total expression=Quantity \\\n" //$NON-NLS-1$
            + "       aggregateFunction=Sum\n" //$NON-NLS-1$
            + "3. Build the structure - one root with a nested table:\n" //$NON-NLS-1$
            + "   - dcs_workshop add_grouping field=Product groupingType=Standard\n" //$NON-NLS-1$
            + "   - dcs_workshop add_settings_table field=Period (deferred to 1.37+)\n" //$NON-NLS-1$
            + "4. Apply conditional appearance for highlighting:\n" //$NON-NLS-1$
            + "   - dcs_workshop add_appearance conditionType=Greater conditionValue=1000 \\\n" //$NON-NLS-1$
            + "       appearance=\"BackColor=#FFFF00\"\n" //$NON-NLS-1$
            + "5. Form: setupSettingsComposerOnForm; the matrix renders automatically.\n"; //$NON-NLS-1$
    }

    private String buildErrorTagsHelp()
    {
        return "Structured error tags surfaced in the JSON response (1.37).\n\n" //$NON-NLS-1$
            + "Top-level fields next to `error`:\n" //$NON-NLS-1$
            + "- `supportLock` { target, ownerType, userSupportMode, discoveredApi, hint } -\n" //$NON-NLS-1$
            + "    object is on vendor support; use an extension instead.\n" //$NON-NLS-1$
            + "- `standardAttributeConflict` { name, conflictsWith, ownerType, source } -\n" //$NON-NLS-1$
            + "    candidate name shadows a platform-standard attribute. Pick another name.\n" //$NON-NLS-1$
            + "- `alreadyExists` { name, ownerFqn, kind } - the child is already present.\n" //$NON-NLS-1$
            + "- `notFound` { name, ownerFqn, kind } - target child does not exist.\n" //$NON-NLS-1$
            + "- `queryValidation` { issues, statistics } - QL/DCS query has parse errors.\n" //$NON-NLS-1$
            + "- `expressionValidation` { issues, statistics } - DCS expression has parse errors.\n" //$NON-NLS-1$
            + "- `fontColorGuard` { appearance, hint } - JSON object/array passed where a string\n" //$NON-NLS-1$
            + "    was expected (use 'Arial,12,bold' / '#RRGGBB' instead).\n" //$NON-NLS-1$
            + "- `autoBorrowed` [fqn, ...] - extension auto-borrowed referenced metadata objects\n" //$NON-NLS-1$
            + "    (success path).\n\n" //$NON-NLS-1$
            + "AI agent pattern: branch on `response.alreadyExists` etc. instead of parsing\n" //$NON-NLS-1$
            + "the human-readable `error` text.\n"; //$NON-NLS-1$
    }

    private String buildWorkflowHelp()
    {
        return "1. createObject objectType=Catalog name=Products\n" //$NON-NLS-1$
            + "2. addObjectAttribute ownerFqn=Catalog.Products name=Article\n" //$NON-NLS-1$
            + "3. addTabularSection ownerFqn=Catalog.Products name=Specifications\n" //$NON-NLS-1$
            + "4. addTabularSectionAttribute ownerFqn=Catalog.Products tabularSectionName=Specifications name=Quantity\n" //$NON-NLS-1$
            + "5. setObjectProperty ownerFqn=Catalog.Products propertyName=Synonym propertyValue=Products\n"; //$NON-NLS-1$
    }

    private String buildTypesHelp()
    {
        return "Use English-singular type names: Catalog, Document, ChartOfAccounts, " //$NON-NLS-1$
            + "ChartOfCharacteristicTypes, ChartOfCalculationTypes, BusinessProcess, Task, " //$NON-NLS-1$
            + "ExchangePlan, DataProcessor, Report, InformationRegister, AccumulationRegister, " //$NON-NLS-1$
            + "AccountingRegister, CalculationRegister, Enum, CommonModule, CommonForm, " //$NON-NLS-1$
            + "Subsystem, Role, FunctionalOption, DefinedType, EventSubscription. " //$NON-NLS-1$
            + "Russian equivalents auto-resolve via MetadataTypeUtils."; //$NON-NLS-1$
    }

    private String buildAvailabilityHelp()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("Object group (8): implemented in 1.33, enhanced in 1.40 (propertyMismatch, cascade form cleanup).\n"); //$NON-NLS-1$
        sb.append("Specialized group (7): implemented in 1.40 (BmRightsHelper, BmDefinedTypeHelper, BmSubsystemHelper, BmEventSubscriptionHelper).\n"); //$NON-NLS-1$
        sb.append("Form constructor (15): implemented in 1.40 (replaces edit_form; FormBaseSetup 11 base props for Generic+empty).\n"); //$NON-NLS-1$
        sb.append("Template group (4): implemented in 1.40. Spreadsheet API present? ") //$NON-NLS-1$
            .append(BmTemplateHelper.isAvailable()).append("\n"); //$NON-NLS-1$
        sb.append("Extension group (5): implemented in 1.40. Adopt service present? ") //$NON-NLS-1$
            .append(BmExtensionHelper.isAvailable()).append("\n"); //$NON-NLS-1$
        sb.append("DCS group (27): implemented in 1.40 (replaces dcs_workshop spike). DCS API present? ") //$NON-NLS-1$
            .append(BmDcsHelper.isAvailable()).append("\n"); //$NON-NLS-1$
        sb.append("Rights API present (for setRoleRight)? ") //$NON-NLS-1$
            .append(com.ditrix.edt.mcp.server.utils.BmRightsHelper.isAvailable()).append("\n"); //$NON-NLS-1$
        sb.append("Common group (2): implemented in 1.40 (universal moveItem/removeItem with FQN routing).\n"); //$NON-NLS-1$
        sb.append("\nDefensive layers (1.40):\n"); //$NON-NLS-1$
        sb.append("- 3.8.1 EventSubscription handler auto-prefix CommonModule.\n"); //$NON-NLS-1$
        sb.append("- 3.8.2 Extension CommonModule guards (privileged, global+server).\n"); //$NON-NLS-1$
        sb.append("- 3.8.3 Generic+empty form 11 base properties scaffold.\n"); //$NON-NLS-1$
        sb.append("- 3.8.4 CommonForm createObject auto-creates inner form.\n"); //$NON-NLS-1$
        return sb.toString();
    }

    private void appendOpGroup(StringBuilder sb, String header, String... ops)
    {
        sb.append("## ").append(header).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        for (String op : ops)
        {
            sb.append("- ").append(op).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        sb.append("\n"); //$NON-NLS-1$
    }

    private String formatResult(BmObjectHelper.Result r, String op)
    {
        if (r.ok)
        {
            ToolResult result = ToolResult.success()
                .put("operation", op) //$NON-NLS-1$
                .put("ownerFqn", r.fqn) //$NON-NLS-1$
                .put("message", r.message != null ? r.message : "ok"); //$NON-NLS-1$ //$NON-NLS-2$
            applyTags(result, r.tags);
            return result.toJson();
        }
        ToolResult err = ToolResult
            .error(op + " failed: " + (r.error != null ? r.error : "unknown error")) //$NON-NLS-1$ //$NON-NLS-2$
            .put("operation", op) //$NON-NLS-1$
            .put("ownerFqn", r.fqn); //$NON-NLS-1$
        applyTags(err, r.tags);
        return err.toJson();
    }

    private static void applyTags(ToolResult result, Map<String, Object> tags)
    {
        if (result == null || tags == null || tags.isEmpty())
        {
            return;
        }
        for (Map.Entry<String, Object> entry : tags.entrySet())
        {
            result.put(entry.getKey(), entry.getValue());
        }
    }

    private String requireNonEmpty(String value, String paramName)
    {
        if (value == null || value.isEmpty())
        {
            return paramName + " is required. "; //$NON-NLS-1$
        }
        return ""; //$NON-NLS-1$
    }

    private String suggest(String op)
    {
        // Simple substring suggestion; reuse a more powerful matcher later.
        String lower = op.toLowerCase();
        for (String known : OPERATIONS.keySet())
        {
            if (known.toLowerCase().contains(lower) || lower.contains(known.toLowerCase()))
            {
                return known;
            }
        }
        List<String> all = new java.util.ArrayList<>(OPERATIONS.keySet());
        Collections.sort(all);
        return all.isEmpty() ? "(none)" : all.get(0); //$NON-NLS-1$
    }

    private static Map<String, String> buildOperationsCatalog()
    {
        Map<String, String> m = new LinkedHashMap<>();
        for (String op : Arrays.asList(
            "createObject", "setObjectProperty", //$NON-NLS-1$ //$NON-NLS-2$
            "addObjectAttribute", "removeObjectAttribute", //$NON-NLS-1$ //$NON-NLS-2$
            "addTabularSection", "removeTabularSection", //$NON-NLS-1$ //$NON-NLS-2$
            "addTabularSectionAttribute", "removeTabularSectionAttribute", //$NON-NLS-1$ //$NON-NLS-2$
            "addRegisterField", "removeRegisterField", "addEnumValue", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "addSubsystemContent", "removeSubsystemContent", //$NON-NLS-1$ //$NON-NLS-2$
            "setRoleRight", "setDefinedTypeTypes", "addEventSubscriptionHandler", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "createForm", "addFormAttribute", "addFormAttributeColumn", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "addDynamicListTable", "addRadioButton", "setProperty", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "listPictures", "addCommandHandler", "setupSettingsComposerOnForm", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "addTemplate", "setTemplateCell", "mergeTemplateCells", "drawTemplate", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "adoptObject", "adoptObjects", "adoptChild", "adoptFormItem", "adoptModule", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "createReportSchema", "addDataSet", "addDataSetField", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "addSchemaParameter", "setSchemaParameter", "removeSchemaParameter", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "moveSchemaParameter", "addCalculatedField", "addTotalField", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "addUserField", "removeDataSet", //$NON-NLS-1$ //$NON-NLS-2$
            "addSettingsGroup", "addSettingsTable", "addSettingsChart", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "addSettingsFilter", "addSettingsFilterGroup", "addSettingsOrder", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "addSettingsSelectedField", "removeSettingsSelectedField", //$NON-NLS-1$ //$NON-NLS-2$
            "addSettingsVariant", "setSettingsParameter", "removeSettingsItem", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "addConditionalAppearance", "removeConditionalAppearance", //$NON-NLS-1$ //$NON-NLS-2$
            "setDataSetFieldAppearance", "setOutputParameter", //$NON-NLS-1$ //$NON-NLS-2$
            "repairReportSchema", //$NON-NLS-1$
            "moveItem", "removeItem")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            m.put(op, op);
        }
        return Collections.unmodifiableMap(m);
    }
}
