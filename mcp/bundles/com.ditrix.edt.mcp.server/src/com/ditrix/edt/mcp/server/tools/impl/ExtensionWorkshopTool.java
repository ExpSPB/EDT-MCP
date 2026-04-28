/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BmExtensionHelper;

/**
 * Configuration extension borrowing tool. 6 operations:
 * borrow_object, borrow_objects (batch), borrow_child, borrow_form_item,
 * borrow_module, list_borrowed.
 * <p>
 * 1.37 wires the ops through {@link BmExtensionHelper#attemptBorrow}. When
 * the EDT adopt service is not reachable, the response carries a structured
 * {@code adoptServiceNotFound} tag with a GUI workaround hint.
 */
public class ExtensionWorkshopTool implements IMcpTool
{
    public static final String NAME = "extension_workshop"; //$NON-NLS-1$

    private static final Map<String, String> OPS = buildOpsCatalog();

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Configuration extension constructor. 6 operations: borrow_object, " //$NON-NLS-1$
            + "borrow_objects (batch), borrow_child, borrow_form_item, borrow_module, " //$NON-NLS-1$
            + "list_borrowed. dryRun is not supported. When the EDT adopt service is " //$NON-NLS-1$
            + "not reachable the response carries `adoptServiceNotFound` with a GUI " //$NON-NLS-1$
            + "workaround hint."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("operation", //$NON-NLS-1$
                "borrow_object / borrow_objects / borrow_child / borrow_form_item / " //$NON-NLS-1$
                    + "borrow_module / list_borrowed / help", true) //$NON-NLS-1$
            .stringProperty("projectName", "EDT extension project name") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("baseProjectName", //$NON-NLS-1$
                "Base configuration project (defaults to extension's parent on save)") //$NON-NLS-1$
            .stringProperty("objectFqn", //$NON-NLS-1$
                "FQN of the object/child/form-item/module to borrow") //$NON-NLS-1$
            .stringArrayProperty("objectFqns", //$NON-NLS-1$
                "Array of FQNs for borrow_objects batch") //$NON-NLS-1$
            .booleanProperty("recursive", //$NON-NLS-1$
                "borrow_object: include attributes/forms/commands. Default false.") //$NON-NLS-1$
            .stringProperty("childKind", //$NON-NLS-1$
                "borrow_child: Attribute / TabularSection / Form / Template") //$NON-NLS-1$
            .stringProperty("itemName", //$NON-NLS-1$
                "borrow_form_item: name of the form item to borrow") //$NON-NLS-1$
            .stringProperty("moduleType", //$NON-NLS-1$
                "borrow_module: ObjectModule / ManagerModule / RecordSetModule / " //$NON-NLS-1$
                    + "CommandModule / ValueModule") //$NON-NLS-1$
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
            return ToolResult.error("Unknown operation: " + op).toJson(); //$NON-NLS-1$
        }
        switch (op)
        {
            case "borrow_object": //$NON-NLS-1$
                return doBorrow(params, op, null);
            case "borrow_child": //$NON-NLS-1$
                return doBorrow(params, op,
                    JsonUtils.extractStringArgument(params, "childKind")); //$NON-NLS-1$
            case "borrow_form_item": //$NON-NLS-1$
                return doBorrow(params, op, "Form"); //$NON-NLS-1$
            case "borrow_module": //$NON-NLS-1$
                return doBorrow(params, op, "Module"); //$NON-NLS-1$
            case "borrow_objects": //$NON-NLS-1$
                return doBorrowBatch(params);
            case "list_borrowed": //$NON-NLS-1$
                return doListBorrowed(params);
            default:
                return ToolResult.error("Unhandled op: " + op).toJson(); //$NON-NLS-1$
        }
    }

    private String doBorrow(Map<String, String> params, String op, String childKind)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String baseProjectName = JsonUtils.extractStringArgument(params, "baseProjectName"); //$NON-NLS-1$
        String objectFqn = JsonUtils.extractStringArgument(params, "objectFqn"); //$NON-NLS-1$
        if (projectName == null || objectFqn == null)
        {
            return ToolResult.error("projectName and objectFqn are required").toJson(); //$NON-NLS-1$
        }
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Extension project not found: " + projectName).toJson(); //$NON-NLS-1$
        }
        BmExtensionHelper.BorrowResult r = BmExtensionHelper.attemptBorrow(project,
            baseProjectName, objectFqn, childKind);
        return formatResult(r, op, objectFqn);
    }

    private String doBorrowBatch(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String baseProjectName = JsonUtils.extractStringArgument(params, "baseProjectName"); //$NON-NLS-1$
        String fqnsRaw = JsonUtils.extractStringArgument(params, "objectFqns"); //$NON-NLS-1$
        if (projectName == null || fqnsRaw == null)
        {
            return ToolResult.error("projectName and objectFqns are required").toJson(); //$NON-NLS-1$
        }
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Extension project not found: " + projectName).toJson(); //$NON-NLS-1$
        }
        List<String> fqns = parseFqnsArray(fqnsRaw);
        if (fqns.isEmpty())
        {
            return ToolResult
                .error("objectFqns must be a non-empty JSON array of FQN strings").toJson(); //$NON-NLS-1$
        }
        List<Map<String, Object>> results = new ArrayList<>();
        int okCount = 0;
        int failCount = 0;
        for (String fqn : fqns)
        {
            BmExtensionHelper.BorrowResult br = BmExtensionHelper.attemptBorrow(project,
                baseProjectName, fqn, null);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("targetFqn", fqn); //$NON-NLS-1$
            entry.put("ok", br.ok); //$NON-NLS-1$
            if (br.error != null)
            {
                entry.put("error", br.error); //$NON-NLS-1$
            }
            entry.putAll(br.tags);
            results.add(entry);
            if (br.ok)
            {
                okCount++;
            }
            else
            {
                failCount++;
            }
        }
        ToolResult result = ToolResult.success()
            .put("operation", "borrow_objects") //$NON-NLS-1$ //$NON-NLS-2$
            .put("ok", okCount) //$NON-NLS-1$
            .put("fail", failCount) //$NON-NLS-1$
            .put("batchResults", results); //$NON-NLS-1$
        return result.toJson();
    }

    private String doListBorrowed(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        if (projectName == null)
        {
            return ToolResult.error("projectName is required").toJson(); //$NON-NLS-1$
        }
        // list_borrowed semantics: read the extension's "adopted" objects via
        // the configuration's adoptedObjects collection (best-effort reflection).
        // When the configuration provider is not reachable we surface the same
        // adoptServiceNotFound tag so callers can fall back to GUI.
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("hint", //$NON-NLS-1$
            "list_borrowed in 1.37 returns the discovered API class so the agent " //$NON-NLS-1$
                + "can see whether borrowing is even possible. Listing the actual " //$NON-NLS-1$
                + "borrowed objects requires an EDT-side index that is not exposed " //$NON-NLS-1$
                + "as a stable API yet. Use the EDT Project Explorer's Extension " //$NON-NLS-1$
                + "subtree to view borrowed objects."); //$NON-NLS-1$
        data.put("discoveredApi", BmExtensionHelper.resolvedAdoptServiceClass()); //$NON-NLS-1$
        return ToolResult.success()
            .put("operation", "list_borrowed") //$NON-NLS-1$ //$NON-NLS-2$
            .put("projectName", projectName) //$NON-NLS-1$
            .put("listBorrowed", data) //$NON-NLS-1$
            .toJson();
    }

    private String formatResult(BmExtensionHelper.BorrowResult r, String op, String objectFqn)
    {
        if (r.ok)
        {
            ToolResult result = ToolResult.success()
                .put("operation", op) //$NON-NLS-1$
                .put("objectFqn", objectFqn) //$NON-NLS-1$
                .put("message", r.alreadyBorrowed ? "already borrowed" : "borrowed"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            applyTags(result, r.tags);
            return result.toJson();
        }
        ToolResult err = ToolResult.error(op + " failed: " //$NON-NLS-1$
            + (r.error != null ? r.error : "unknown error")) //$NON-NLS-1$
            .put("operation", op) //$NON-NLS-1$
            .put("objectFqn", objectFqn); //$NON-NLS-1$
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

    /**
     * Parses a JSON-array string ({@code ["a","b"]}) or a comma-separated list
     * into a list of FQNs. Best-effort; trims quotes/whitespace.
     */
    private static List<String> parseFqnsArray(String raw)
    {
        List<String> out = new ArrayList<>();
        if (raw == null)
        {
            return out;
        }
        String s = raw.trim();
        if (s.startsWith("[") && s.endsWith("]")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            s = s.substring(1, s.length() - 1);
        }
        for (String token : s.split(",")) //$NON-NLS-1$
        {
            String t = token.trim();
            if (t.startsWith("\"") && t.endsWith("\"")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                t = t.substring(1, t.length() - 1).trim();
            }
            if (!t.isEmpty())
            {
                out.add(t);
            }
        }
        return out;
    }

    private String handleHelp(Map<String, String> params)
    {
        String topic = JsonUtils.extractStringArgument(params, "topic"); //$NON-NLS-1$
        if (topic == null || topic.isEmpty())
        {
            StringBuilder sb = new StringBuilder("# extension_workshop\n\n"); //$NON-NLS-1$
            sb.append("Configuration extension borrowing. 6 operations.\n\n"); //$NON-NLS-1$
            sb.append("- borrow_object - borrow a single FQN (Catalog/Document/etc.)\n"); //$NON-NLS-1$
            sb.append("- borrow_objects - batch borrow with per-FQN results\n"); //$NON-NLS-1$
            sb.append("- borrow_child - borrow a single attribute/tabular section/form/template\n"); //$NON-NLS-1$
            sb.append("- borrow_form_item - borrow a single form item by name\n"); //$NON-NLS-1$
            sb.append("- borrow_module - borrow a specific module of an object\n"); //$NON-NLS-1$
            sb.append("- list_borrowed - report the discovered adopt API and a hint\n\n"); //$NON-NLS-1$
            sb.append("**Adopt API status:** ") //$NON-NLS-1$
                .append(BmExtensionHelper.isAvailable()
                    ? ("found - " + BmExtensionHelper.resolvedAdoptServiceClass()) //$NON-NLS-1$
                    : "NOT reachable in this EDT version") //$NON-NLS-1$
                .append("\n\n"); //$NON-NLS-1$
            sb.append("Topics: workflow, errorTags\n"); //$NON-NLS-1$
            return ToolResult.success().put("help", sb.toString()).toJson(); //$NON-NLS-1$
        }
        switch (topic.toLowerCase())
        {
            case "workflow": //$NON-NLS-1$
                return ToolResult.success().put("topic", topic) //$NON-NLS-1$
                    .put("text", //$NON-NLS-1$
                        "Borrow workflow:\n" //$NON-NLS-1$
                            + "1. Open base configuration + extension project in the same workspace.\n" //$NON-NLS-1$
                            + "2. extension_workshop borrow_object projectName=MyExt " //$NON-NLS-1$
                            + "objectFqn=Catalog.Products\n" //$NON-NLS-1$
                            + "3. extension_workshop borrow_objects projectName=MyExt " //$NON-NLS-1$
                            + "objectFqns=[\"Catalog.A\",\"Document.B\"]\n" //$NON-NLS-1$
                            + "4. After successful borrow the extension can override " //$NON-NLS-1$
                            + "attributes / forms via edit_metadata / edit_form.")
                    .toJson();
            case "errortags": //$NON-NLS-1$
                return ToolResult.success().put("topic", topic) //$NON-NLS-1$
                    .put("text", //$NON-NLS-1$
                        "Tags surfaced by extension_workshop:\n" //$NON-NLS-1$
                            + "- borrowed { targetFqn, baseProject, returned } - success.\n" //$NON-NLS-1$
                            + "- alreadyBorrowed { targetFqn } - idempotent success path.\n" //$NON-NLS-1$
                            + "- adoptServiceNotFound { operation, targetFqn, hint } - probe failed.\n" //$NON-NLS-1$
                            + "- adoptInvocationFailed { targetFqn, methodSignature?, error } - " //$NON-NLS-1$
                            + "service found but invocation threw.\n" //$NON-NLS-1$
                            + "- batchResults [...] - one entry per FQN in borrow_objects.\n")
                    .toJson();
            default:
                return ToolResult.error("Unknown topic: " + topic).toJson(); //$NON-NLS-1$
        }
    }

    private static Map<String, String> buildOpsCatalog()
    {
        Map<String, String> m = new LinkedHashMap<>();
        for (String op : Arrays.asList(
            "borrow_object", "borrow_objects", "borrow_child", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "borrow_form_item", "borrow_module", "list_borrowed")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            m.put(op, op);
        }
        return Collections.unmodifiableMap(m);
    }
}
