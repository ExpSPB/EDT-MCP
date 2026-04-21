/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BmFormHelper;
import com.ditrix.edt.mcp.server.utils.FrontMatter;

/**
 * MCP tool for creating and removing form elements via BM API.
 * <p>
 * Supports operations: addField, addGroup, addButton, addTable, addDecoration,
 * removeItem, help.
 * <p>
 * Uses {@link BmFormHelper} for all form manipulation - reflection-based access
 * to EDT's internal EMF model, executed inside BM read-write transactions.
 */
public class EditFormTool implements IMcpTool
{
    public static final String NAME = "edit_form"; //$NON-NLS-1$

    private static final String OP_ADD_FIELD = "addField"; //$NON-NLS-1$
    private static final String OP_ADD_GROUP = "addGroup"; //$NON-NLS-1$
    private static final String OP_ADD_BUTTON = "addButton"; //$NON-NLS-1$
    private static final String OP_ADD_TABLE = "addTable"; //$NON-NLS-1$
    private static final String OP_ADD_DECORATION = "addDecoration"; //$NON-NLS-1$
    private static final String OP_REMOVE_ITEM = "removeItem"; //$NON-NLS-1$
    private static final String OP_HELP = "help"; //$NON-NLS-1$

    /** Lazy-initialized singleton helper */
    private BmFormHelper helper;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Edit a 1C managed form: add or remove elements " + //$NON-NLS-1$
            "(fields, groups, buttons, tables, decorations) via BM API. " + //$NON-NLS-1$
            "Operations: addField, addGroup, addButton, addTable, addDecoration, " + //$NON-NLS-1$
            "removeItem, help. Use 'help' operation for detailed usage."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("formFqn", //$NON-NLS-1$
                "BM top-object FQN of the form, ending with '.Form' " //$NON-NLS-1$
                + "(the Form.form file segment). " //$NON-NLS-1$
                + "Example: 'Catalog.Products.Form.ItemForm.Form'. " //$NON-NLS-1$
                + "On error the tool lists matching FQNs from the BM namespace " //$NON-NLS-1$
                + "(borrowed forms in extensions may use a different prefix). " //$NON-NLS-1$
                + "Required.", true) //$NON-NLS-1$
            .stringProperty("operation", //$NON-NLS-1$
                "Operation: addField, addGroup, addButton, addTable, addDecoration, " + //$NON-NLS-1$
                "removeItem, help (required)", true) //$NON-NLS-1$
            .stringProperty("name", //$NON-NLS-1$
                "Element name (required for add/remove operations)") //$NON-NLS-1$
            .stringProperty("title", //$NON-NLS-1$
                "Element title/caption") //$NON-NLS-1$
            .stringProperty("elementType", //$NON-NLS-1$
                "For addField: InputField, CheckBox, RadioButton, Label, Image. " + //$NON-NLS-1$
                "For addGroup: UsualGroup, Pages, Page, Column, CommandBar. " + //$NON-NLS-1$
                "For addDecoration: Label or Picture.") //$NON-NLS-1$
            .stringProperty("dataPath", //$NON-NLS-1$
                "Data path for field binding (e.g. 'Object.Name')") //$NON-NLS-1$
            .stringProperty("parentName", //$NON-NLS-1$
                "Parent container name (default: root form)") //$NON-NLS-1$
            .stringProperty("beforeName", //$NON-NLS-1$
                "Insert before this element name") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formFqn = JsonUtils.extractStringArgument(params, "formFqn"); //$NON-NLS-1$
        String operation = JsonUtils.extractStringArgument(params, "operation"); //$NON-NLS-1$

        // Handle help operation early (no project/form needed)
        if (OP_HELP.equalsIgnoreCase(operation))
        {
            return buildHelpResponse();
        }

        // Validate required params
        if (projectName == null || projectName.isEmpty())
        {
            return buildError("projectName is required"); //$NON-NLS-1$
        }
        if (formFqn == null || formFqn.isEmpty())
        {
            return buildError("formFqn is required. " + //$NON-NLS-1$
                "Example: 'Catalog.Products.Form.ItemForm.Form' " + //$NON-NLS-1$
                "(note the trailing '.Form' segment from the Form.form file)"); //$NON-NLS-1$
        }
        if (operation == null || operation.isEmpty())
        {
            return buildError("operation is required. " + //$NON-NLS-1$
                "Options: addField, addGroup, addButton, addTable, addDecoration, removeItem, help"); //$NON-NLS-1$
        }

        // Execute on UI thread (BM API requires it in some EDT versions)
        AtomicReference<String> resultRef = new AtomicReference<>();
        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() ->
        {
            try
            {
                resultRef.set(executeInternal(projectName, formFqn, operation, params));
            }
            catch (Exception e)
            {
                Activator.logError("Error in edit_form", e); //$NON-NLS-1$
                resultRef.set(buildError(e.getMessage()));
            }
        });

        return resultRef.get();
    }

    private String executeInternal(String projectName, String formFqn, String operation,
        Map<String, String> params)
    {
        // Initialize helper (lazy singleton)
        if (helper == null)
        {
            helper = new BmFormHelper();
        }
        if (!helper.init())
        {
            return buildError("BmFormHelper initialization failed. " + //$NON-NLS-1$
                "Form model classes not available in this EDT version."); //$NON-NLS-1$
        }

        // Find project
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return buildError("Project not found: " + projectName); //$NON-NLS-1$
        }

        // Extract common params
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        String title = JsonUtils.extractStringArgument(params, "title"); //$NON-NLS-1$
        String elementType = JsonUtils.extractStringArgument(params, "elementType"); //$NON-NLS-1$
        String dataPath = JsonUtils.extractStringArgument(params, "dataPath"); //$NON-NLS-1$
        String parentName = JsonUtils.extractStringArgument(params, "parentName"); //$NON-NLS-1$
        String beforeName = JsonUtils.extractStringArgument(params, "beforeName"); //$NON-NLS-1$

        // Validate name for add/remove operations
        if (!OP_HELP.equalsIgnoreCase(operation)
            && (name == null || name.isEmpty()))
        {
            return buildError("'name' parameter is required for operation: " + operation); //$NON-NLS-1$
        }

        // Execute inside BM transaction
        String error = helper.executeFormOperation(project, formFqn, (tx, form) ->
        {
            // Include BaseForm (if any) in the ID scan so that new IDs do not
            // collide with those inherited from the main configuration form.
            Object baseForm = helper.findBaseForm(tx, formFqn);
            helper.resetIdCounter(form, baseForm);

            switch (operation.toLowerCase())
            {
                case "addfield": //$NON-NLS-1$
                    return executeAddField(form, name, title, elementType, dataPath,
                        parentName, beforeName);
                case "addgroup": //$NON-NLS-1$
                    return executeAddGroup(form, name, title, elementType,
                        parentName, beforeName);
                case "addbutton": //$NON-NLS-1$
                    return executeAddButton(form, name, title,
                        parentName, beforeName);
                case "addtable": //$NON-NLS-1$
                    return executeAddTable(form, name, title, dataPath,
                        parentName, beforeName);
                case "adddecoration": //$NON-NLS-1$
                    return executeAddDecoration(form, name, title, elementType,
                        parentName, beforeName);
                case "removeitem": //$NON-NLS-1$
                    return executeRemoveItem(form, name);
                default:
                    return "Error: Unknown operation: " + operation + //$NON-NLS-1$
                        ". Use 'help' to see available operations."; //$NON-NLS-1$
            }
        });

        // If executeFormOperation returned an error string
        if (error != null && error.startsWith("Error:")) //$NON-NLS-1$
        {
            return buildError(error);
        }

        // If the transaction action returned an error
        if (error != null && !error.isEmpty())
        {
            // Check if it's actually an error or a success message
            if (error.startsWith("Error:")) //$NON-NLS-1$
            {
                return buildError(error);
            }
            // It's a success message from the action
            return error;
        }

        // Default success
        return buildSuccess(projectName, formFqn, operation, name, title, elementType,
            dataPath, parentName);
    }

    // -----------------------------------------------------------------------
    // Operation implementations
    // -----------------------------------------------------------------------

    private String executeAddField(Object form, String name, String title,
        String fieldType, String dataPath, String parentName, String beforeName)
        throws Exception
    {
        if (fieldType == null || fieldType.isEmpty())
        {
            fieldType = "InputField"; //$NON-NLS-1$
        }
        if (title == null || title.isEmpty())
        {
            title = name;
        }

        Object field = helper.createFormField(name, title, fieldType);

        if (dataPath != null && !dataPath.isEmpty())
        {
            helper.setDataPath(field, dataPath);
        }

        Object container = resolveContainer(form, parentName);
        if (beforeName != null && !beforeName.isEmpty())
        {
            helper.addToContainerBefore(container, field, beforeName);
        }
        else
        {
            helper.addToContainer(container, field);
        }

        return buildSuccess("edit_form", name, "addField", //$NON-NLS-1$ //$NON-NLS-2$
            "Field '" + name + "' added to form successfully.\n" + //$NON-NLS-1$ //$NON-NLS-2$
            "- Name: " + name + "\n" + //$NON-NLS-1$ //$NON-NLS-2$
            "- Title: " + title + "\n" + //$NON-NLS-1$ //$NON-NLS-2$
            "- Type: " + fieldType + "\n" + //$NON-NLS-1$ //$NON-NLS-2$
            (dataPath != null ? "- DataPath: " + dataPath + "\n" : "") + //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "- Parent: " + (parentName != null ? parentName : "root")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String executeAddGroup(Object form, String name, String title,
        String groupType, String parentName, String beforeName) throws Exception
    {
        if (groupType == null || groupType.isEmpty())
        {
            groupType = "UsualGroup"; //$NON-NLS-1$
        }
        if (title == null || title.isEmpty())
        {
            title = name;
        }

        Object group = helper.createFormGroup(name, title, groupType);

        Object container = resolveContainer(form, parentName);
        if (beforeName != null && !beforeName.isEmpty())
        {
            helper.addToContainerBefore(container, group, beforeName);
        }
        else
        {
            helper.addToContainer(container, group);
        }

        return buildSuccess("edit_form", name, "addGroup", //$NON-NLS-1$ //$NON-NLS-2$
            "Group '" + name + "' added to form successfully.\n" + //$NON-NLS-1$ //$NON-NLS-2$
            "- Name: " + name + "\n" + //$NON-NLS-1$ //$NON-NLS-2$
            "- Title: " + title + "\n" + //$NON-NLS-1$ //$NON-NLS-2$
            "- Type: " + groupType + "\n" + //$NON-NLS-1$ //$NON-NLS-2$
            "- Parent: " + (parentName != null ? parentName : "root")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String executeAddButton(Object form, String name, String title,
        String parentName, String beforeName) throws Exception
    {
        if (title == null || title.isEmpty())
        {
            title = name;
        }

        String commandName = name + "Command"; //$NON-NLS-1$
        Object command = helper.createFormCommand(commandName, title);
        Object button = helper.createButton(name, title);

        helper.linkButtonToCommand(button, command);
        helper.addCommandToForm(form, command);

        Object container = resolveContainer(form, parentName);
        if (beforeName != null && !beforeName.isEmpty())
        {
            helper.addToContainerBefore(container, button, beforeName);
        }
        else
        {
            helper.addToContainer(container, button);
        }

        return buildSuccess("edit_form", name, "addButton", //$NON-NLS-1$ //$NON-NLS-2$
            "Button '" + name + "' added to form successfully.\n" + //$NON-NLS-1$ //$NON-NLS-2$
            "- Name: " + name + "\n" + //$NON-NLS-1$ //$NON-NLS-2$
            "- Title: " + title + "\n" + //$NON-NLS-1$ //$NON-NLS-2$
            "- Command: " + commandName + "\n" + //$NON-NLS-1$ //$NON-NLS-2$
            "- Parent: " + (parentName != null ? parentName : "root")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String executeAddTable(Object form, String name, String title,
        String dataPath, String parentName, String beforeName) throws Exception
    {
        if (title == null || title.isEmpty())
        {
            title = name;
        }

        Object table = helper.createTable(name, title);

        if (dataPath != null && !dataPath.isEmpty())
        {
            helper.setDataPath(table, dataPath);
        }

        Object container = resolveContainer(form, parentName);
        if (beforeName != null && !beforeName.isEmpty())
        {
            helper.addToContainerBefore(container, table, beforeName);
        }
        else
        {
            helper.addToContainer(container, table);
        }

        return buildSuccess("edit_form", name, "addTable", //$NON-NLS-1$ //$NON-NLS-2$
            "Table '" + name + "' added to form successfully.\n" + //$NON-NLS-1$ //$NON-NLS-2$
            "- Name: " + name + "\n" + //$NON-NLS-1$ //$NON-NLS-2$
            "- Title: " + title + "\n" + //$NON-NLS-1$ //$NON-NLS-2$
            (dataPath != null ? "- DataPath: " + dataPath + "\n" : "") + //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "- Parent: " + (parentName != null ? parentName : "root")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String executeAddDecoration(Object form, String name, String title,
        String decorationType, String parentName, String beforeName) throws Exception
    {
        if (decorationType == null || decorationType.isEmpty())
        {
            decorationType = "Label"; //$NON-NLS-1$
        }
        if (title == null || title.isEmpty())
        {
            title = name;
        }

        Object decoration = helper.createDecoration(name, title, decorationType);

        Object container = resolveContainer(form, parentName);
        if (beforeName != null && !beforeName.isEmpty())
        {
            helper.addToContainerBefore(container, decoration, beforeName);
        }
        else
        {
            helper.addToContainer(container, decoration);
        }

        return buildSuccess("edit_form", name, "addDecoration", //$NON-NLS-1$ //$NON-NLS-2$
            "Decoration '" + name + "' added to form successfully.\n" + //$NON-NLS-1$ //$NON-NLS-2$
            "- Name: " + name + "\n" + //$NON-NLS-1$ //$NON-NLS-2$
            "- Title: " + title + "\n" + //$NON-NLS-1$ //$NON-NLS-2$
            "- Type: " + decorationType + "\n" + //$NON-NLS-1$ //$NON-NLS-2$
            "- Parent: " + (parentName != null ? parentName : "root")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String executeRemoveItem(Object form, String name) throws Exception
    {
        boolean removed = helper.removeItemByName(form, name);
        if (!removed)
        {
            return buildError("Error: Element not found: " + name); //$NON-NLS-1$
        }

        return buildSuccess("edit_form", name, "removeItem", //$NON-NLS-1$ //$NON-NLS-2$
            "Element '" + name + "' removed from form successfully."); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // -----------------------------------------------------------------------
    // Helper methods
    // -----------------------------------------------------------------------

    /**
     * Resolves the container for adding elements.
     * If parentName is specified, searches for it recursively in the form.
     * Otherwise returns the form itself (root container).
     */
    private Object resolveContainer(Object form, String parentName) throws Exception
    {
        if (parentName == null || parentName.isEmpty())
        {
            return form;
        }
        Object container = helper.findItemByName(form, parentName);
        if (container == null)
        {
            throw new RuntimeException("Parent container not found: " + parentName); //$NON-NLS-1$
        }
        return container;
    }

    private String buildError(String message)
    {
        return FrontMatter.create()
            .put("tool", NAME) //$NON-NLS-1$
            .put("status", "error") //$NON-NLS-1$ //$NON-NLS-2$
            .wrapContent(message);
    }

    private String buildSuccess(String tool, String elementName, String operation, String body)
    {
        return FrontMatter.create()
            .put("tool", NAME) //$NON-NLS-1$
            .put("operation", operation) //$NON-NLS-1$
            .put("element", elementName) //$NON-NLS-1$
            .put("status", "success") //$NON-NLS-1$ //$NON-NLS-2$
            .wrapContent(body);
    }

    private String buildSuccess(String projectName, String formFqn, String operation,
        String name, String title, String elementType, String dataPath, String parentName)
    {
        StringBuilder body = new StringBuilder();
        body.append("Operation completed successfully.\n"); //$NON-NLS-1$
        body.append("- Operation: ").append(operation).append('\n'); //$NON-NLS-1$
        if (name != null)
        {
            body.append("- Name: ").append(name).append('\n'); //$NON-NLS-1$
        }
        if (title != null)
        {
            body.append("- Title: ").append(title).append('\n'); //$NON-NLS-1$
        }
        if (elementType != null)
        {
            body.append("- Type: ").append(elementType).append('\n'); //$NON-NLS-1$
        }
        if (dataPath != null)
        {
            body.append("- DataPath: ").append(dataPath).append('\n'); //$NON-NLS-1$
        }
        body.append("- Parent: ").append(parentName != null ? parentName : "root").append('\n'); //$NON-NLS-1$ //$NON-NLS-2$

        return FrontMatter.create()
            .put("tool", NAME) //$NON-NLS-1$
            .put("projectName", projectName) //$NON-NLS-1$
            .put("formFqn", formFqn) //$NON-NLS-1$
            .put("operation", operation) //$NON-NLS-1$
            .put("status", "success") //$NON-NLS-1$ //$NON-NLS-2$
            .wrapContent(body.toString());
    }

    private String buildHelpResponse()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("# edit_form - Form Element Operations\n\n"); //$NON-NLS-1$

        sb.append("## Common Parameters\n\n"); //$NON-NLS-1$
        sb.append("- **projectName** (required): EDT project name " //$NON-NLS-1$
            + "(for borrowed forms use the extension project name).\n"); //$NON-NLS-1$
        sb.append("- **formFqn** (required): BM top-object FQN of the form. " //$NON-NLS-1$
            + "Must end with '.Form' - this segment comes from the Form.form " //$NON-NLS-1$
            + "file name.\n"); //$NON-NLS-1$
        sb.append("  - Main config: `Catalog.Products.Form.ItemForm.Form`\n"); //$NON-NLS-1$
        sb.append("  - Common form: `CommonForm.MyForm.Form`\n"); //$NON-NLS-1$
        sb.append("  - If the FQN is not found, the error lists matching " //$NON-NLS-1$
            + "FQNs from the BM namespace (useful for borrowed forms where the " //$NON-NLS-1$
            + "canonical FQN may differ from the main configuration).\n\n"); //$NON-NLS-1$

        sb.append("## Preconditions\n\n"); //$NON-NLS-1$
        sb.append("- **Fields with dataPath**: the backing attribute must " //$NON-NLS-1$
            + "already exist on the metadata object. If you are binding to a " //$NON-NLS-1$
            + "new attribute, call `add_metadata_attribute` first - otherwise " //$NON-NLS-1$
            + "EDT will flag the dataPath as `form-data-path` MAJOR error.\n"); //$NON-NLS-1$
        sb.append("- **Extension attributes**: EDT prefixes attributes added " //$NON-NLS-1$
            + "in extensions with the extension's `namePrefix` (e.g. adding " //$NON-NLS-1$
            + "attribute `Price` in extension with prefix `Ais_` results in " //$NON-NLS-1$
            + "`Ais_Price`). Use the prefixed name in dataPath: " //$NON-NLS-1$
            + "`Object.Ais_Price`.\n"); //$NON-NLS-1$
        sb.append("- **BaseForm awareness**: borrowed forms only expose " //$NON-NLS-1$
            + "override items via `getItems()`; the tool automatically scans " //$NON-NLS-1$
            + "the `.BaseForm` top-object for ID collision avoidance.\n"); //$NON-NLS-1$
        sb.append("- **Persistence**: changes are written to the `.form` " //$NON-NLS-1$
            + "file via `forceExport` after each operation - no separate save " //$NON-NLS-1$
            + "step is required.\n\n"); //$NON-NLS-1$

        sb.append("## Operations\n\n"); //$NON-NLS-1$

        sb.append("### addField\n"); //$NON-NLS-1$
        sb.append("Add a field element to the form.\n"); //$NON-NLS-1$
        sb.append("- **name** (required): Element name\n"); //$NON-NLS-1$
        sb.append("- **title**: Display caption\n"); //$NON-NLS-1$
        sb.append("- **elementType**: InputField (default), CheckBox, RadioButton, Label, Image\n"); //$NON-NLS-1$
        sb.append("- **dataPath**: Data binding path (e.g. 'Object.Name')\n"); //$NON-NLS-1$
        sb.append("- **parentName**: Parent container (default: root form)\n"); //$NON-NLS-1$
        sb.append("- **beforeName**: Insert before this element\n\n"); //$NON-NLS-1$

        sb.append("### addGroup\n"); //$NON-NLS-1$
        sb.append("Add a group element to the form.\n"); //$NON-NLS-1$
        sb.append("- **name** (required): Element name\n"); //$NON-NLS-1$
        sb.append("- **title**: Display caption\n"); //$NON-NLS-1$
        sb.append("- **elementType**: UsualGroup (default), Pages, Page, Column, CommandBar\n"); //$NON-NLS-1$
        sb.append("- **parentName**: Parent container\n"); //$NON-NLS-1$
        sb.append("- **beforeName**: Insert before this element\n\n"); //$NON-NLS-1$

        sb.append("### addButton\n"); //$NON-NLS-1$
        sb.append("Add a button with linked command.\n"); //$NON-NLS-1$
        sb.append("- **name** (required): Button name (command: name + 'Command')\n"); //$NON-NLS-1$
        sb.append("- **title**: Button caption\n"); //$NON-NLS-1$
        sb.append("- **parentName**: Parent container\n"); //$NON-NLS-1$
        sb.append("- **beforeName**: Insert before this element\n\n"); //$NON-NLS-1$

        sb.append("### addTable\n"); //$NON-NLS-1$
        sb.append("Add a table element to the form.\n"); //$NON-NLS-1$
        sb.append("- **name** (required): Element name\n"); //$NON-NLS-1$
        sb.append("- **title**: Display caption\n"); //$NON-NLS-1$
        sb.append("- **dataPath**: Data binding path (e.g. 'Object.Products')\n"); //$NON-NLS-1$
        sb.append("- **parentName**: Parent container\n"); //$NON-NLS-1$
        sb.append("- **beforeName**: Insert before this element\n\n"); //$NON-NLS-1$

        sb.append("### addDecoration\n"); //$NON-NLS-1$
        sb.append("Add a decoration (label/picture) to the form.\n"); //$NON-NLS-1$
        sb.append("- **name** (required): Element name\n"); //$NON-NLS-1$
        sb.append("- **title**: Display text\n"); //$NON-NLS-1$
        sb.append("- **elementType**: Label (default) or Picture\n"); //$NON-NLS-1$
        sb.append("- **parentName**: Parent container\n"); //$NON-NLS-1$
        sb.append("- **beforeName**: Insert before this element\n\n"); //$NON-NLS-1$

        sb.append("### removeItem\n"); //$NON-NLS-1$
        sb.append("Remove an element by name.\n"); //$NON-NLS-1$
        sb.append("- **name** (required): Element name to remove\n\n"); //$NON-NLS-1$

        sb.append("## Examples\n\n"); //$NON-NLS-1$
        sb.append("```json\n"); //$NON-NLS-1$
        sb.append("// Add an input field bound to Object.Price\n"); //$NON-NLS-1$
        sb.append("{\n"); //$NON-NLS-1$
        sb.append("  \"projectName\": \"MyConfig\",\n"); //$NON-NLS-1$
        sb.append("  \"formFqn\": \"Catalog.Products.Form.ItemForm.Form\",\n"); //$NON-NLS-1$
        sb.append("  \"operation\": \"addField\",\n"); //$NON-NLS-1$
        sb.append("  \"name\": \"FieldPrice\",\n"); //$NON-NLS-1$
        sb.append("  \"title\": \"Price\",\n"); //$NON-NLS-1$
        sb.append("  \"elementType\": \"InputField\",\n"); //$NON-NLS-1$
        sb.append("  \"dataPath\": \"Object.Price\"\n"); //$NON-NLS-1$
        sb.append("}\n"); //$NON-NLS-1$
        sb.append("```\n"); //$NON-NLS-1$

        return FrontMatter.create()
            .put("tool", NAME) //$NON-NLS-1$
            .put("operation", OP_HELP) //$NON-NLS-1$
            .put("status", "success") //$NON-NLS-1$ //$NON-NLS-2$
            .wrapContent(sb.toString());
    }
}
