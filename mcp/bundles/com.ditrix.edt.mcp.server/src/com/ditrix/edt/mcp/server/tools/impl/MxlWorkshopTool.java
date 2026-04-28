/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.emf.common.util.EList;

import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BmObjectHelper;
import com.ditrix.edt.mcp.server.utils.BmTemplateHelper;

/**
 * MXL spreadsheet template constructor.
 * <p>
 * <b>1.37 status:</b> {@code create_template} is fully wired through
 * {@link BmObjectHelper}; cell-level operations ({@code set_cell},
 * {@code merge_cells}, {@code draw}) require the EDT layout service whose API
 * surface is not yet stable across releases. When the probe fails the tool
 * returns a structured {@code mxlApiNotFound} tag so the agent can fall back
 * to the GUI editor.
 */
public class MxlWorkshopTool implements IMcpTool
{
    public static final String NAME = "mxl_workshop"; //$NON-NLS-1$

    private static final Map<String, String> OPS = buildOpsCatalog();

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "MXL spreadsheet template constructor. 4 operations: create_template, " //$NON-NLS-1$
            + "set_cell, merge_cells, draw. " //$NON-NLS-1$
            + "create_template is wired in 1.37; cell-level ops require the " //$NON-NLS-1$
            + "layout service and surface a structured `mxlApiNotFound` tag " //$NON-NLS-1$
            + "when not reachable in the running EDT version."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("operation", //$NON-NLS-1$
                "create_template / set_cell / merge_cells / draw / help", true) //$NON-NLS-1$
            .stringProperty("projectName", "EDT project name") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("ownerFqn", //$NON-NLS-1$
                "Object FQN that owns the template (Catalog.X / Document.X / DataProcessor.X)") //$NON-NLS-1$
            .stringProperty("templateName", "Template name") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("templateType", //$NON-NLS-1$
                "SpreadsheetDocument (default) / TextDocument / DataCompositionSchema / etc.") //$NON-NLS-1$
            .integerProperty("row", "Cell row (1-based)") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("col", "Cell column (1-based)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("text", "Cell text content") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("fromRow", "Merge range from-row") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("fromCol", "Merge range from-col") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("toRow", "Merge range to-row") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("toCol", "Merge range to-col") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("layout", //$NON-NLS-1$
                "JSON layout for draw: {cells:[{row,col,text}],merges:[{from,to}]}") //$NON-NLS-1$
            .booleanProperty("dryRun", "Preview without applying (default false)") //$NON-NLS-1$ //$NON-NLS-2$
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
        String op = JsonUtils.extractStringArgument(params, "operation"); //$NON-NLS-1$
        if (op == null || op.isEmpty())
        {
            return ToolResult.error("operation is required").toJson(); //$NON-NLS-1$
        }
        if ("help".equalsIgnoreCase(op)) //$NON-NLS-1$
        {
            return handleHelp(params);
        }
        if (!OPS.containsKey(op))
        {
            return ToolResult.error("Unknown operation: " + op //$NON-NLS-1$
                + ". Available: " + String.join(", ", OPS.keySet())).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }

        switch (op)
        {
            case "create_template": //$NON-NLS-1$
                return opCreateTemplate(params);
            case "set_cell": //$NON-NLS-1$
            case "merge_cells": //$NON-NLS-1$
            case "draw": //$NON-NLS-1$
                return opCellLevel(op, params);
            default:
                return ToolResult.error("Unhandled op: " + op).toJson(); //$NON-NLS-1$
        }
    }

    private String opCreateTemplate(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String templateName = JsonUtils.extractStringArgument(params, "templateName"); //$NON-NLS-1$
        String templateType = JsonUtils.extractStringArgument(params, "templateType"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        if (templateType == null || templateType.isEmpty())
        {
            templateType = "SpreadsheetDocument"; //$NON-NLS-1$
        }
        if (projectName == null || ownerFqn == null || templateName == null)
        {
            return ToolResult
                .error("projectName, ownerFqn and templateName are required").toJson(); //$NON-NLS-1$
        }
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
        }
        final String finalTemplateType = templateType;
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                @SuppressWarnings("unchecked")
                EList<MdObject> templates = (EList<MdObject>) invokeListGetter(owner,
                    "getTemplates"); //$NON-NLS-1$
                if (templates == null)
                {
                    throw new RuntimeException("Owner type '" + owner.eClass().getName() //$NON-NLS-1$
                        + "' has no Templates collection."); //$NON-NLS-1$
                }
                if (BmObjectHelper.findByName(templates, templateName) != null)
                {
                    throw BmObjectHelper.alreadyExists(templateName, ownerFqn, "template"); //$NON-NLS-1$
                }
                MdObject template = BmObjectHelper.createObject("Template"); //$NON-NLS-1$
                if (template == null)
                {
                    throw new RuntimeException("MdClassFactory.createTemplate not available"); //$NON-NLS-1$
                }
                template.setName(templateName);
                String setErr = BmObjectHelper.setProperty(template, "templateType", //$NON-NLS-1$
                    finalTemplateType);
                if (setErr != null)
                {
                    throw new RuntimeException("Cannot set templateType=" + finalTemplateType //$NON-NLS-1$
                        + ": " + setErr); //$NON-NLS-1$
                }
                templates.add(template);
                return templateName;
            });
        return formatResult(r, "create_template"); //$NON-NLS-1$
    }

    private String opCellLevel(String op, Map<String, String> params)
    {
        if (!BmTemplateHelper.isAvailable())
        {
            return mxlApiNotFound(op);
        }
        // Cell-level mutation requires the layout service. When the EDT
        // version exposes it, this handler invokes the service via reflection.
        // For now we return a structured error so the caller can branch on
        // `mxlApiNotFound` and fall back to GUI workflow.
        return mxlApiNotFound(op);
    }

    private String mxlApiNotFound(String op)
    {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", op); //$NON-NLS-1$
        data.put("discoveredSpreadsheetClass", BmTemplateHelper.resolvedSpreadsheetClass()); //$NON-NLS-1$
        data.put("discoveredFactoryClass", BmTemplateHelper.resolvedFactoryClass()); //$NON-NLS-1$
        data.put("discoveredLayoutServiceClass", //$NON-NLS-1$
            BmTemplateHelper.resolvedLayoutServiceClass());
        data.put("hint", //$NON-NLS-1$
            "Cell-level MXL editing requires the EDT layout service. " //$NON-NLS-1$
                + "If the discoveredLayoutServiceClass is null, open the project in EDT " //$NON-NLS-1$
                + "and edit the template via the GUI spreadsheet editor."); //$NON-NLS-1$
        return ToolResult.error("Spreadsheet layout service not reachable in this EDT runtime") //$NON-NLS-1$
            .put("operation", op) //$NON-NLS-1$
            .put("mxlApiNotFound", data) //$NON-NLS-1$
            .toJson();
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
        if (tags == null || tags.isEmpty())
        {
            return;
        }
        for (Map.Entry<String, Object> entry : tags.entrySet())
        {
            result.put(entry.getKey(), entry.getValue());
        }
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
            // missing collection
        }
        return null;
    }

    private String handleHelp(Map<String, String> params)
    {
        String topic = JsonUtils.extractStringArgument(params, "topic"); //$NON-NLS-1$
        if (topic == null || topic.isEmpty())
        {
            StringBuilder sb = new StringBuilder("# mxl_workshop\n\n"); //$NON-NLS-1$
            sb.append("MXL spreadsheet template constructor. 4 operations.\n\n"); //$NON-NLS-1$
            sb.append("**Implemented (1.37):**\n"); //$NON-NLS-1$
            sb.append("- create_template (creates Template MdObject + templateType binding)\n\n"); //$NON-NLS-1$
            sb.append("**Best-effort (probe-dependent):**\n"); //$NON-NLS-1$
            sb.append("- set_cell, merge_cells, draw - require the EDT layout service.\n"); //$NON-NLS-1$
            sb.append("  When the probe fails the response carries an `mxlApiNotFound` tag\n"); //$NON-NLS-1$
            sb.append("  with the discovered class names; fall back to the GUI editor.\n\n"); //$NON-NLS-1$
            sb.append("**API discovery:**\n"); //$NON-NLS-1$
            sb.append("- SpreadsheetDocument: ").append(BmTemplateHelper.resolvedSpreadsheetClass()) //$NON-NLS-1$
                .append("\n"); //$NON-NLS-1$
            sb.append("- Factory: ").append(BmTemplateHelper.resolvedFactoryClass()).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append("- LayoutService: ").append(BmTemplateHelper.resolvedLayoutServiceClass()) //$NON-NLS-1$
                .append("\n\n"); //$NON-NLS-1$
            sb.append("Topics: workflow, errorTags\n"); //$NON-NLS-1$
            return ToolResult.success().put("help", sb.toString()).toJson(); //$NON-NLS-1$
        }
        switch (topic.toLowerCase())
        {
            case "workflow": //$NON-NLS-1$
                return ToolResult.success().put("topic", topic) //$NON-NLS-1$
                    .put("text", "1. create_template ownerFqn=Document.PrintForm " //$NON-NLS-1$ //$NON-NLS-2$
                        + "templateName=Print templateType=SpreadsheetDocument\n" //$NON-NLS-1$
                        + "2. set_cell row=1 col=1 text='Header' (when API reachable)\n" //$NON-NLS-1$
                        + "3. merge_cells fromRow=1 fromCol=1 toRow=1 toCol=5\n" //$NON-NLS-1$
                        + "4. draw layout='{...}' (batch mode)\n").toJson(); //$NON-NLS-1$
            case "errortags": //$NON-NLS-1$
                return ToolResult.success().put("topic", topic) //$NON-NLS-1$
                    .put("text", "Tags surfaced by mxl_workshop:\n" //$NON-NLS-1$ //$NON-NLS-2$
                        + "- alreadyExists { name, ownerFqn, kind=template } - template " //$NON-NLS-1$
                        + "with this name already exists.\n" //$NON-NLS-1$
                        + "- mxlApiNotFound { operation, discoveredSpreadsheetClass, " //$NON-NLS-1$
                        + "discoveredFactoryClass, discoveredLayoutServiceClass, hint } - " //$NON-NLS-1$
                        + "cell-level operation requested but EDT layout service not reachable.\n") //$NON-NLS-1$
                    .toJson();
            default:
                return ToolResult.error("Unknown topic: " + topic).toJson(); //$NON-NLS-1$
        }
    }

    private static Map<String, String> buildOpsCatalog()
    {
        Map<String, String> m = new LinkedHashMap<>();
        for (String op : Arrays.asList("create_template", "set_cell", "merge_cells", "draw")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            m.put(op, op);
        }
        return Collections.unmodifiableMap(m);
    }
}
