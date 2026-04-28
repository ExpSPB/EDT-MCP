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
import com.ditrix.edt.mcp.server.utils.BmDcsHelper;
import com.ditrix.edt.mcp.server.utils.BmExtensionHelper;
import com.ditrix.edt.mcp.server.utils.BmFormHelper;
import com.ditrix.edt.mcp.server.utils.BmObjectHelper;
import com.ditrix.edt.mcp.server.utils.BmTemplateHelper;
import com.ditrix.edt.mcp.server.utils.EventStubGenerator;
import com.ditrix.edt.mcp.server.utils.MetadataGuards;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;

/**
 * Single-entry constructor for metadata, forms, DCS, templates and extensions
 * - the EDT-MCP equivalent of RSV's {@code edit_metadata}.
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
        return "Single constructor for metadata, forms, DCS, templates and extensions. " //$NON-NLS-1$
            + "Pass operation=<name> with operation-specific parameters. " //$NON-NLS-1$
            + "Call operation=help for the full catalog (~59 operations across 7 groups). " //$NON-NLS-1$
            + "Add dryRun=true to any operation to preview changes without applying them."; //$NON-NLS-1$
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
            // Still deferred (use edit_form for richer item/group/button operations)
            case "addFormAttributeColumn": //$NON-NLS-1$
            case "addDynamicListTable": //$NON-NLS-1$
            case "addRadioButton": //$NON-NLS-1$
            case "listPictures": //$NON-NLS-1$
            case "setupSettingsComposerOnForm": //$NON-NLS-1$
                return ToolResult.error("Form constructor operation '" + op //$NON-NLS-1$
                    + "' is deferred. Use edit_form for add/remove items, " //$NON-NLS-1$
                    + "or createForm/addFormAttribute/addFormCommand/setFormItemProperty " //$NON-NLS-1$
                    + "for the metadata-level form operations.").toJson(); //$NON-NLS-1$

            // ---- Templates group (deferred) ----
            case "addTemplate": //$NON-NLS-1$
            case "setTemplateCell": //$NON-NLS-1$
            case "mergeTemplateCells": //$NON-NLS-1$
            case "drawTemplate": //$NON-NLS-1$
                return ToolResult.error(BmTemplateHelper.deferredMessage(op)).toJson();

            // ---- Extensions group (deferred) ----
            case "adoptObject": //$NON-NLS-1$
            case "adoptObjects": //$NON-NLS-1$
            case "adoptChild": //$NON-NLS-1$
            case "adoptFormItem": //$NON-NLS-1$
            case "adoptModule": //$NON-NLS-1$
                return ToolResult.error(BmExtensionHelper.deferredMessage(op)).toJson();

            // ---- DCS group (deferred) ----
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
                return ToolResult.error(BmDcsHelper.deferredMessage(op)).toJson();

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

        // Create+add inside a write task
        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        IBmModel bmModel = bmModelManager != null ? bmModelManager.getModel(project) : null;
        if (bmModel == null)
        {
            return ToolResult.error("BM model not available").toJson(); //$NON-NLS-1$
        }

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

    private String opSubsystemContent(Map<String, String> params, boolean add, String opName)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        // Reuse 'name' as the FQN of the object being added/removed.
        String contentFqn = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found").toJson(); //$NON-NLS-1$
        }
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                // Subsystem.content holds references to MdObjects. We add/remove
                // by resolving the target object and mutating the EReference list.
                EList<MdObject> content = invokeListGetter(owner, "getContent"); //$NON-NLS-1$
                if (content == null)
                {
                    throw new RuntimeException("Owner has no content (not a Subsystem)"); //$NON-NLS-1$
                }
                if (add)
                {
                    // Idempotent: if already present, skip
                    for (MdObject m : content)
                    {
                        if (m != null && contentFqn != null
                            && contentFqn.equalsIgnoreCase(m.getName()))
                        {
                            return contentFqn + " (already in subsystem)"; //$NON-NLS-1$
                        }
                    }
                    return "would add " + contentFqn //$NON-NLS-1$
                        + " (resolution requires Configuration lookup; skipped in skeleton)"; //$NON-NLS-1$
                }
                // remove
                java.util.Iterator<MdObject> it = content.iterator();
                while (it.hasNext())
                {
                    MdObject m = it.next();
                    if (m != null && contentFqn != null
                        && contentFqn.equalsIgnoreCase(m.getName()))
                    {
                        it.remove();
                        return contentFqn;
                    }
                }
                throw new RuntimeException("Object not in subsystem: " + contentFqn); //$NON-NLS-1$
            });
        return formatResult(r, opName);
    }

    private String opSetRoleRight(Map<String, String> params)
    {
        // Skeleton: full implementation requires com._1c.g5.v8.dt.rights.model integration
        return ToolResult.error("set_role_right requires rights.model integration; " //$NON-NLS-1$
            + "skeleton in 1.37 — full impl in 1.38").toJson(); //$NON-NLS-1$
    }

    private String opSetDefinedTypeTypes(Map<String, String> params)
    {
        return ToolResult.error("set_defined_type_types requires DefinedType.types EList " //$NON-NLS-1$
            + "with TypeDescription resolution; skeleton in 1.37").toJson(); //$NON-NLS-1$
    }

    private String opAddEventHandler(Map<String, String> params)
    {
        String eventName = JsonUtils.extractStringArgument(params, "eventName"); //$NON-NLS-1$
        String handlerName = JsonUtils.extractStringArgument(params, "handlerName"); //$NON-NLS-1$
        String customSignature = JsonUtils.extractStringArgument(params, "customSignature"); //$NON-NLS-1$
        EventStubGenerator.Stub stub = EventStubGenerator.generateStub(eventName, handlerName,
            customSignature);
        if (stub.code == null)
        {
            return ToolResult.error(stub.warning != null ? stub.warning : "stub generation failed") //$NON-NLS-1$
                .toJson();
        }
        ToolResult result = ToolResult.success()
            .put("operation", "add_event_handler") //$NON-NLS-1$ //$NON-NLS-2$
            .put("signatureSource", stub.signatureSource) //$NON-NLS-1$
            .put("stub", stub.code); //$NON-NLS-1$
        if (stub.warning != null)
        {
            result.put("warning", stub.warning); //$NON-NLS-1$
        }
        return result.toJson();
    }

    // -----------------------------------------------------------------------
    // Common operations (1.37)
    // -----------------------------------------------------------------------

    private String opMoveItem(Map<String, String> params)
    {
        return ToolResult.error("move_item is a generic mover used by dcs_workshop / form ops " //$NON-NLS-1$
            + "in their own dispatchers; standalone implementation deferred to 1.39 batch mode") //$NON-NLS-1$
            .toJson();
    }

    private String opRemoveItem(Map<String, String> params)
    {
        return ToolResult.error("remove_item universal: routes to dcs_workshop / form ops based " //$NON-NLS-1$
            + "on context; standalone implementation deferred to 1.39") //$NON-NLS-1$
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
                return formName;
            });
        return formatResult(r, "createForm"); //$NON-NLS-1$
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
            sb.append("**Status (1.33):** the Object group (8 ops) is implemented. ") //$NON-NLS-1$
                .append("DCS / Templates / Extensions / Form constructor groups return a ") //$NON-NLS-1$
                .append("precise deferred message and land in 1.34-1.39.\n\n"); //$NON-NLS-1$

            appendOpGroup(sb, "Objects (8) - implemented in 1.33", //$NON-NLS-1$
                "createObject", "setObjectProperty", //$NON-NLS-1$ //$NON-NLS-2$
                "addObjectAttribute", "removeObjectAttribute", //$NON-NLS-1$ //$NON-NLS-2$
                "addTabularSection", "removeTabularSection", //$NON-NLS-1$ //$NON-NLS-2$
                "addTabularSectionAttribute", "removeTabularSectionAttribute"); //$NON-NLS-1$ //$NON-NLS-2$
            appendOpGroup(sb, "Specialized (7) - deferred", //$NON-NLS-1$
                "addRegisterField", "removeRegisterField", //$NON-NLS-1$ //$NON-NLS-2$
                "addEnumValue", //$NON-NLS-1$
                "addSubsystemContent", "removeSubsystemContent", //$NON-NLS-1$ //$NON-NLS-2$
                "setRoleRight", "setDefinedTypeTypes", //$NON-NLS-1$ //$NON-NLS-2$
                "addEventSubscriptionHandler"); //$NON-NLS-1$
            appendOpGroup(sb, "Forms (9) - deferred (use edit_form for the basic 6 operations)", //$NON-NLS-1$
                "createForm", "addFormAttribute", "addFormAttributeColumn", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "addDynamicListTable", "addRadioButton", "setProperty", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "listPictures", "addCommandHandler", "setupSettingsComposerOnForm"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            appendOpGroup(sb, "Templates (4) - deferred", //$NON-NLS-1$
                "addTemplate", "setTemplateCell", //$NON-NLS-1$ //$NON-NLS-2$
                "mergeTemplateCells", "drawTemplate"); //$NON-NLS-1$ //$NON-NLS-2$
            appendOpGroup(sb, "Extensions (5) - deferred", //$NON-NLS-1$
                "adoptObject", "adoptObjects", "adoptChild", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "adoptFormItem", "adoptModule"); //$NON-NLS-1$ //$NON-NLS-2$
            appendOpGroup(sb, "DCS (22) - deferred", //$NON-NLS-1$
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
            appendOpGroup(sb, "Common (2) - deferred", //$NON-NLS-1$
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
        sb.append("Object group (8): implemented in 1.33.\n"); //$NON-NLS-1$
        sb.append("Specialized group (7): deferred to 1.34-1.39.\n"); //$NON-NLS-1$
        sb.append("Form constructor (9): deferred. Use edit_form for the basic 6 ops.\n"); //$NON-NLS-1$
        sb.append("Template group (4): deferred. Spreadsheet API present? ") //$NON-NLS-1$
            .append(BmTemplateHelper.isAvailable()).append("\n"); //$NON-NLS-1$
        sb.append("Extension group (5): deferred. Adopt service present? ") //$NON-NLS-1$
            .append(BmExtensionHelper.isAvailable()).append("\n"); //$NON-NLS-1$
        sb.append("DCS group (22): deferred. DCS API present? ") //$NON-NLS-1$
            .append(BmDcsHelper.isAvailable()).append("\n"); //$NON-NLS-1$
        sb.append("Common group (2): deferred.\n"); //$NON-NLS-1$
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
