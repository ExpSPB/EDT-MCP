/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.emf.ecore.EObject;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BmFormHelper;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Tool to extract a managed-form structure as JSON.
 * <p>
 * Complements {@code get_form_screenshot} which returns a PNG. This tool walks
 * the form's BM model via reflection (reusing {@link BmFormHelper}) and
 * produces a tree of {@code {name, type, title, items, properties}} that AI
 * agents can navigate without parsing raw XML.
 * <p>
 * Supports pagination for large forms:
 * <ul>
 *   <li>{@code depth} - maximum recursion depth (0 = unlimited)</li>
 *   <li>{@code subtree} - element name to start the walk from</li>
 *   <li>{@code maxElements} - hard cap on total emitted nodes</li>
 * </ul>
 */
public class GetFormStructureTool implements IMcpTool
{
    public static final String NAME = "get_form_structure"; //$NON-NLS-1$

    private static final int DEFAULT_DEPTH = 5;
    private static final int DEFAULT_MAX_ELEMENTS = 500;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Extract a managed form structure as a JSON tree. " //$NON-NLS-1$
            + "Walks Form.getItems() recursively and emits per-element " //$NON-NLS-1$
            + "{name, type, title, items, properties} including dataPath / commandName / " //$NON-NLS-1$
            + "kind / visible / enabled when available. Use depth/subtree/maxElements " //$NON-NLS-1$
            + "for large forms. Pair with get_form_screenshot for visual rendering."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("formPath", //$NON-NLS-1$
                "Metadata FQN of the form. " //$NON-NLS-1$
                    + "Examples: 'Catalog.Products.Forms.ItemForm', " //$NON-NLS-1$
                    + "'Document.SalesOrder.Forms.DocumentForm', 'CommonForm.MyForm'.", true) //$NON-NLS-1$
            .integerProperty("depth", //$NON-NLS-1$
                "Maximum recursion depth (0 = unlimited). Default: 5.") //$NON-NLS-1$
            .stringProperty("subtree", //$NON-NLS-1$
                "Name of a child element to start the walk from. " //$NON-NLS-1$
                    + "Returns the subtree rooted at the first match (depth-first). " //$NON-NLS-1$
                    + "Useful to drill into a single Group/Page on a large form.") //$NON-NLS-1$
            .integerProperty("maxElements", //$NON-NLS-1$
                "Hard cap on total emitted nodes. Default: 500. " //$NON-NLS-1$
                    + "When the cap is reached, the walk stops and 'truncated': true is set.") //$NON-NLS-1$
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
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formPath = JsonUtils.extractStringArgument(params, "formPath"); //$NON-NLS-1$
        int depth = JsonUtils.extractIntArgument(params, "depth", DEFAULT_DEPTH); //$NON-NLS-1$
        String subtree = JsonUtils.extractStringArgument(params, "subtree"); //$NON-NLS-1$
        int maxElements = JsonUtils.extractIntArgument(params, "maxElements", DEFAULT_MAX_ELEMENTS); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required").toJson(); //$NON-NLS-1$
        }
        if (formPath == null || formPath.isEmpty())
        {
            return ToolResult.error("formPath is required").toJson(); //$NON-NLS-1$
        }

        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
        }

        BmFormHelper helper = new BmFormHelper();
        if (!helper.init())
        {
            return ToolResult.error("BmFormHelper init failed - incompatible EDT version") //$NON-NLS-1$
                .toJson();
        }

        // Form FQN in BM uses the trailing ".Form" segment that comes from the
        // Form.form file name. Translate "Type.Object.Forms.Name" -> the
        // BM-canonical form FQN as documented in BmFormHelper.
        String fqn = toBmFormFqn(formPath);

        int finalDepth = depth;
        int finalMax = Math.max(1, maxElements);
        String finalSubtree = subtree;

        AtomicInteger counter = new AtomicInteger(0);
        StringBuilder errorRef = new StringBuilder();
        JsonObject[] resultRoot = new JsonObject[1];

        String operationError = helper.executeFormOperation(project, fqn, (transaction, form) -> {
            try
            {
                Object root = form;
                if (finalSubtree != null && !finalSubtree.isEmpty())
                {
                    Object found = findItemByName(form, finalSubtree);
                    if (found == null)
                    {
                        errorRef.append("Subtree element not found by name: " + finalSubtree); //$NON-NLS-1$
                        return null;
                    }
                    root = found;
                }
                resultRoot[0] = walk(root, 0, finalDepth, finalMax, counter);
            }
            catch (Throwable t)
            {
                errorRef.append("Walk failed: ").append(t.getClass().getSimpleName()) //$NON-NLS-1$
                    .append(": ").append(t.getMessage()); //$NON-NLS-1$
            }
            return null;
        });

        if (errorRef.length() > 0)
        {
            return ToolResult.error(errorRef.toString()).toJson();
        }
        if (operationError != null && !operationError.isEmpty())
        {
            return ToolResult.error(operationError).toJson();
        }
        if (resultRoot[0] == null)
        {
            return ToolResult.error("No structure produced").toJson(); //$NON-NLS-1$
        }

        JsonObject envelope = new JsonObject();
        envelope.addProperty("formPath", formPath); //$NON-NLS-1$
        envelope.addProperty("formFqn", fqn); //$NON-NLS-1$
        envelope.addProperty("emitted", counter.get()); //$NON-NLS-1$
        envelope.addProperty("limit", finalMax); //$NON-NLS-1$
        envelope.addProperty("depth", finalDepth); //$NON-NLS-1$
        if (counter.get() >= finalMax)
        {
            envelope.addProperty("truncated", true); //$NON-NLS-1$
        }
        envelope.add("root", resultRoot[0]); //$NON-NLS-1$
        return envelope.toString();
    }

    /**
     * Translates the user-facing form path to the BM-canonical FQN.
     * <p>
     * "Catalog.Products.Forms.ItemForm" -> "Catalog.Products.Form.ItemForm.Form"
     * "CommonForm.MyForm" -> "CommonForm.MyForm.Form"
     */
    private String toBmFormFqn(String formPath)
    {
        // CommonForm.X -> CommonForm.X.Form
        if (formPath.startsWith("CommonForm.")) //$NON-NLS-1$
        {
            return formPath.endsWith(".Form") ? formPath : formPath + ".Form"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        // Type.Object.Forms.Name -> Type.Object.Form.Name.Form
        String normalized = formPath.replace(".Forms.", ".Form."); //$NON-NLS-1$ //$NON-NLS-2$
        return normalized.endsWith(".Form") ? normalized : normalized + ".Form"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Recursively builds a JSON tree node for the given form item.
     * Honors the depth and global element-count limits.
     */
    private JsonObject walk(Object item, int currentDepth, int maxDepth, int maxElements,
        AtomicInteger counter)
    {
        if (counter.get() >= maxElements || item == null)
        {
            return null;
        }
        counter.incrementAndGet();

        JsonObject node = new JsonObject();
        node.addProperty("type", classNameOf(item)); //$NON-NLS-1$

        String name = invokeStringNoArg(item, "getName"); //$NON-NLS-1$
        if (name != null && !name.isEmpty())
        {
            node.addProperty("name", name); //$NON-NLS-1$
        }

        String title = extractTitle(item);
        if (title != null && !title.isEmpty())
        {
            node.addProperty("title", title); //$NON-NLS-1$
        }

        Integer id = invokeIntNoArg(item, "getId"); //$NON-NLS-1$
        if (id != null)
        {
            node.addProperty("id", id.intValue()); //$NON-NLS-1$
        }

        JsonObject properties = collectProperties(item);
        if (properties != null && properties.size() > 0)
        {
            node.add("properties", properties); //$NON-NLS-1$
        }

        // Descend into FormItemContainer.getItems() when within depth budget.
        boolean canDescend = maxDepth == 0 || currentDepth < maxDepth;
        if (canDescend)
        {
            List<Object> children = readChildItems(item);
            if (!children.isEmpty())
            {
                JsonArray itemsArr = new JsonArray();
                for (Object child : children)
                {
                    if (counter.get() >= maxElements)
                    {
                        break;
                    }
                    JsonObject childNode = walk(child, currentDepth + 1, maxDepth,
                        maxElements, counter);
                    if (childNode != null)
                    {
                        itemsArr.add(childNode);
                    }
                }
                if (itemsArr.size() > 0)
                {
                    node.add("items", itemsArr); //$NON-NLS-1$
                }
            }
        }

        return node;
    }

    /**
     * Collects optional per-element properties via best-effort reflection.
     * Each lookup is wrapped in try/catch so that missing methods on a particular
     * EClass do not abort the walk.
     */
    private JsonObject collectProperties(Object item)
    {
        JsonObject props = new JsonObject();

        Boolean visible = invokeBooleanNoArg(item, "isVisible"); //$NON-NLS-1$
        if (visible != null)
        {
            props.addProperty("visible", visible.booleanValue()); //$NON-NLS-1$
        }

        Boolean enabled = invokeBooleanNoArg(item, "isEnabled"); //$NON-NLS-1$
        if (enabled != null)
        {
            props.addProperty("enabled", enabled.booleanValue()); //$NON-NLS-1$
        }

        // FormField, ContextMenu, ColumnGroup etc. - the user-facing dataPath
        String dataPath = extractDataPath(item);
        if (dataPath != null && !dataPath.isEmpty())
        {
            props.addProperty("dataPath", dataPath); //$NON-NLS-1$
        }

        // FormGroup type / kind
        String kind = invokeStringFromEnumNoArg(item, "getKind"); //$NON-NLS-1$
        if (kind != null && !kind.isEmpty())
        {
            props.addProperty("kind", kind); //$NON-NLS-1$
        }

        // Button standard or user command name
        String commandName = extractCommandName(item);
        if (commandName != null && !commandName.isEmpty())
        {
            props.addProperty("commandName", commandName); //$NON-NLS-1$
        }

        // ChildrenGroup / ChildrenAlign / Representation - direction & rendering hints
        String childrenGroup = invokeStringFromEnumNoArg(item, "getChildrenGroup"); //$NON-NLS-1$
        if (childrenGroup != null && !childrenGroup.isEmpty())
        {
            props.addProperty("childrenGroup", childrenGroup); //$NON-NLS-1$
        }

        String representation = invokeStringFromEnumNoArg(item, "getRepresentation"); //$NON-NLS-1$
        if (representation != null && !representation.isEmpty())
        {
            props.addProperty("representation", representation); //$NON-NLS-1$
        }

        return props.size() > 0 ? props : null;
    }

    private String extractTitle(Object item)
    {
        // Form / FormItem / Decoration use getTitle() returning a localized string holder
        try
        {
            Method m = item.getClass().getMethod("getTitle"); //$NON-NLS-1$
            Object v = m.invoke(item);
            if (v == null)
            {
                return null;
            }
            // Try common holders: MdTextString.getValue() / .getKey() / toString()
            String candidate = invokeStringNoArg(v, "getValue"); //$NON-NLS-1$
            if (candidate != null && !candidate.isEmpty())
            {
                return candidate;
            }
            candidate = invokeStringNoArg(v, "getKey"); //$NON-NLS-1$
            if (candidate != null && !candidate.isEmpty())
            {
                return candidate;
            }
            return v.toString();
        }
        catch (NoSuchMethodException nsme)
        {
            return null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private String extractDataPath(Object item)
    {
        String[] candidates = { "getDataPath", "getDataPathString" }; //$NON-NLS-1$ //$NON-NLS-2$
        for (String name : candidates)
        {
            try
            {
                Method m = item.getClass().getMethod(name);
                Object v = m.invoke(item);
                if (v == null)
                {
                    continue;
                }
                if (v instanceof String)
                {
                    return (String) v;
                }
                String s = invokeStringNoArg(v, "getSegmentsAsString"); //$NON-NLS-1$
                if (s != null && !s.isEmpty())
                {
                    return s;
                }
                return v.toString();
            }
            catch (NoSuchMethodException nsme)
            {
                // try next candidate
            }
            catch (Exception ignored)
            {
                // ignore - dataPath is best-effort
            }
        }
        return null;
    }

    private String extractCommandName(Object item)
    {
        // Button.getCommandName / Button.getStandardCommand.getName / Button.getCommand.getName
        String name = invokeStringNoArg(item, "getCommandName"); //$NON-NLS-1$
        if (name != null && !name.isEmpty())
        {
            return name;
        }
        try
        {
            Method m = item.getClass().getMethod("getStandardCommand"); //$NON-NLS-1$
            Object cmd = m.invoke(item);
            if (cmd != null)
            {
                String n = invokeStringNoArg(cmd, "getName"); //$NON-NLS-1$
                if (n != null && !n.isEmpty())
                {
                    return n;
                }
            }
        }
        catch (NoSuchMethodException ignored)
        {
            // not a button or different EDT version
        }
        catch (Exception ignored)
        {
            // best-effort
        }
        return null;
    }

    /**
     * Returns the children of a FormItemContainer or empty list otherwise.
     */
    @SuppressWarnings("unchecked")
    private List<Object> readChildItems(Object item)
    {
        try
        {
            Method m = item.getClass().getMethod("getItems"); //$NON-NLS-1$
            Object v = m.invoke(item);
            if (v instanceof List)
            {
                return new ArrayList<>((List<Object>) v);
            }
        }
        catch (NoSuchMethodException ignored)
        {
            // not a container
        }
        catch (Exception ignored)
        {
            // best-effort
        }
        return new ArrayList<>();
    }

    /**
     * Depth-first search for a FormItem with matching getName().
     * Used to resolve the {@code subtree} parameter.
     */
    private Object findItemByName(Object root, String targetName)
    {
        if (root == null)
        {
            return null;
        }
        String name = invokeStringNoArg(root, "getName"); //$NON-NLS-1$
        if (targetName.equals(name))
        {
            return root;
        }
        for (Object child : readChildItems(root))
        {
            Object found = findItemByName(child, targetName);
            if (found != null)
            {
                return found;
            }
        }
        return null;
    }

    private String classNameOf(Object o)
    {
        if (o instanceof EObject)
        {
            return ((EObject) o).eClass().getName();
        }
        return o.getClass().getSimpleName();
    }

    private String invokeStringNoArg(Object target, String methodName)
    {
        try
        {
            Method m = target.getClass().getMethod(methodName);
            Object v = m.invoke(target);
            return v != null ? v.toString() : null;
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    private String invokeStringFromEnumNoArg(Object target, String methodName)
    {
        try
        {
            Method m = target.getClass().getMethod(methodName);
            Object v = m.invoke(target);
            return v != null ? v.toString() : null;
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    private Integer invokeIntNoArg(Object target, String methodName)
    {
        try
        {
            Method m = target.getClass().getMethod(methodName);
            Object v = m.invoke(target);
            if (v instanceof Number)
            {
                return Integer.valueOf(((Number) v).intValue());
            }
        }
        catch (Exception ignored)
        {
            // best-effort
        }
        return null;
    }

    private Boolean invokeBooleanNoArg(Object target, String methodName)
    {
        try
        {
            Method m = target.getClass().getMethod(methodName);
            Object v = m.invoke(target);
            if (v instanceof Boolean)
            {
                return (Boolean) v;
            }
        }
        catch (Exception ignored)
        {
            // best-effort
        }
        return null;
    }
}
