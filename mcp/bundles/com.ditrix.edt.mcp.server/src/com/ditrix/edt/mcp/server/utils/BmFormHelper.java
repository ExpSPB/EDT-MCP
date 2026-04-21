/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Utility class for form manipulation via reflection on EDT's internal EMF model.
 * <p>
 * All EDT form classes are loaded via {@link Class#forName(String)} to avoid
 * compile-time dependencies on {@code com._1c.g5.v8.dt.form.model} package.
 * EMF singleton factories ({@code FormFactory.eINSTANCE}, {@code MdClassFactory.eINSTANCE})
 * are obtained through reflection.
 * <p>
 * BM transactions use {@link Proxy} for {@code IBmSingleNamespaceTask}.
 * <p>
 * Version-guard: {@link #init()} catches {@link ClassNotFoundException}, logs a warning,
 * and returns {@code false} if EDT form model classes are not available.
 * <p>
 * Based on the proven RSV BmFormHelper pattern for real EDT compatibility.
 */
public class BmFormHelper
{
    // EDT form model classes (loaded via reflection)
    private Class<?> txIface;           // com._1c.g5.v8.bm.core.IBmTransaction
    private Class<?> ffClass;           // com._1c.g5.v8.dt.form.model.FormFactory
    private Class<?> mdFactoryClass;    // com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory
    private Class<?> formIface;         // com._1c.g5.v8.dt.form.model.Form
    private Class<?> formCommandIface;  // com._1c.g5.v8.dt.form.model.FormCommand
    private Class<?> formItemIface;     // com._1c.g5.v8.dt.form.model.FormItem
    private Class<?> formFieldIface;    // com._1c.g5.v8.dt.form.model.FormField
    private Class<?> formGroupIface;    // com._1c.g5.v8.dt.form.model.FormGroup
    private Class<?> buttonIface;       // com._1c.g5.v8.dt.form.model.Button
    private Class<?> tableIface;        // com._1c.g5.v8.dt.form.model.Table
    private Class<?> decorationIface;   // com._1c.g5.v8.dt.form.model.Decoration
    private Class<?> namedIface;        // com._1c.g5.v8.dt.mcore.NamedElement
    private Class<?> titledIface;       // com._1c.g5.v8.dt.form.model.Titled
    private Class<?> visibleIface;      // com._1c.g5.v8.dt.form.model.Visible
    private Class<?> containerIface;    // com._1c.g5.v8.dt.form.model.FormItemContainer
    private Class<?> commandIface;      // com._1c.g5.v8.dt.mcore.Command
    private Class<?> extTooltipHolderIface; // com._1c.g5.v8.dt.form.model.ExtendedTooltipHolder
    private Class<?> adjBoolClass;      // com._1c.g5.v8.dt.metadata.mdclass.AdjustableBoolean
    private Class<?> dtProjectIface;    // com._1c.g5.v8.dt.core.platform.IDtProject
    private Class<?> taskIface;         // com._1c.g5.v8.bm.integration.IBmSingleNamespaceTask

    // EMF singleton factories
    private Object formFactory;         // FormFactory.eINSTANCE
    private Object mdFactory;           // MdClassFactory.eINSTANCE

    private boolean initialized = false;
    private int idCounter = 0;

    /**
     * Functional interface for form transaction actions.
     * Executed inside a BM read-write transaction with access to the transaction
     * and the resolved form object.
     */
    @FunctionalInterface
    public interface FormTransactionAction
    {
        /**
         * Executes the form operation inside a BM transaction.
         *
         * @param transaction the BM transaction object
         * @param form the form object resolved by FQN
         * @return result of the operation (typically a description string)
         * @throws Exception if the operation fails
         */
        Object execute(Object transaction, Object form) throws Exception;
    }

    /**
     * Initializes all EDT form model classes and EMF factories via reflection.
     * <p>
     * This method is safe to call multiple times - subsequent calls return immediately
     * if already initialized. If any required class is not found (e.g. incompatible EDT
     * version), logs a warning and returns {@code false}.
     *
     * @return {@code true} if initialization succeeded, {@code false} otherwise
     */
    public boolean init()
    {
        if (initialized)
        {
            return true;
        }
        try
        {
            txIface = Class.forName("com._1c.g5.v8.bm.core.IBmTransaction"); //$NON-NLS-1$
            ffClass = Class.forName("com._1c.g5.v8.dt.form.model.FormFactory"); //$NON-NLS-1$
            mdFactoryClass = Class.forName("com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory"); //$NON-NLS-1$
            formIface = Class.forName("com._1c.g5.v8.dt.form.model.Form"); //$NON-NLS-1$
            formCommandIface = Class.forName("com._1c.g5.v8.dt.form.model.FormCommand"); //$NON-NLS-1$
            formItemIface = Class.forName("com._1c.g5.v8.dt.form.model.FormItem"); //$NON-NLS-1$
            formFieldIface = Class.forName("com._1c.g5.v8.dt.form.model.FormField"); //$NON-NLS-1$
            formGroupIface = Class.forName("com._1c.g5.v8.dt.form.model.FormGroup"); //$NON-NLS-1$
            buttonIface = Class.forName("com._1c.g5.v8.dt.form.model.Button"); //$NON-NLS-1$
            tableIface = Class.forName("com._1c.g5.v8.dt.form.model.Table"); //$NON-NLS-1$
            decorationIface = Class.forName("com._1c.g5.v8.dt.form.model.Decoration"); //$NON-NLS-1$
            namedIface = Class.forName("com._1c.g5.v8.dt.mcore.NamedElement"); //$NON-NLS-1$
            titledIface = Class.forName("com._1c.g5.v8.dt.form.model.Titled"); //$NON-NLS-1$
            visibleIface = Class.forName("com._1c.g5.v8.dt.form.model.Visible"); //$NON-NLS-1$
            containerIface = Class.forName("com._1c.g5.v8.dt.form.model.FormItemContainer"); //$NON-NLS-1$
            commandIface = Class.forName("com._1c.g5.v8.dt.mcore.Command"); //$NON-NLS-1$
            extTooltipHolderIface = Class.forName("com._1c.g5.v8.dt.form.model.ExtendedTooltipHolder"); //$NON-NLS-1$
            adjBoolClass = Class.forName("com._1c.g5.v8.dt.metadata.mdclass.AdjustableBoolean"); //$NON-NLS-1$
            dtProjectIface = Class.forName("com._1c.g5.v8.dt.core.platform.IDtProject"); //$NON-NLS-1$
            taskIface = Class.forName("com._1c.g5.v8.bm.integration.IBmSingleNamespaceTask"); //$NON-NLS-1$

            formFactory = ffClass.getField("eINSTANCE").get(null); //$NON-NLS-1$
            mdFactory = mdFactoryClass.getField("eINSTANCE").get(null); //$NON-NLS-1$

            initialized = true;
            return true;
        }
        catch (Exception e)
        {
            Activator.logWarning("BmFormHelper init failed: " + e.getMessage()); //$NON-NLS-1$
            return false;
        }
    }

    /**
     * Executes a form operation inside a BM read-write transaction.
     * <p>
     * Steps:
     * <ol>
     * <li>Get {@code IBmModelManager} via {@link Activator}</li>
     * <li>Get {@code IBmModel} for the project</li>
     * <li>Create a {@link Proxy} for {@code IBmSingleNamespaceTask}</li>
     * <li>Inside the proxy: resolve form by FQN via {@code transaction.getTopObjectByFqn()}</li>
     * <li>Call the action with transaction and form</li>
     * <li>Execute the task via {@code bmModel.executeReadWriteTask()} found by reflection</li>
     * </ol>
     *
     * @param project the workspace project
     * @param formFqn the BM top-object FQN of the form, including the trailing
     *            {@code .Form} segment that comes from the {@code Form.form}
     *            file name (e.g. "Catalog.Products.Form.ItemForm.Form"). Use
     *            the diagnostic hint returned on "form not found" to discover
     *            the canonical FQN for borrowed forms in extensions.
     * @param action the action to execute inside the transaction
     * @return result string from the action, or an error message
     */
    public String executeFormOperation(IProject project, String formFqn, FormTransactionAction action)
    {
        try
        {
            // Get BM model manager from Activator
            IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
            if (bmModelManager == null)
            {
                return "Error: IBmModelManager not available"; //$NON-NLS-1$
            }

            IBmModel bmModel = bmModelManager.getModel(project);
            if (bmModel == null)
            {
                return "Error: BM model not available for project: " + project.getName(); //$NON-NLS-1$
            }

            // Create proxy for IBmSingleNamespaceTask
            Object taskProxy = Proxy.newProxyInstance(
                taskIface.getClassLoader(),
                new Class<?>[] { taskIface },
                (proxy, method, args) ->
                {
                    if ("execute".equals(method.getName())) //$NON-NLS-1$
                    {
                        Object transaction = args[0];

                        // Resolve form by FQN: transaction.getTopObjectByFqn(formFqn)
                        Object form = txIface.getMethod("getTopObjectByFqn", String.class) //$NON-NLS-1$
                            .invoke(transaction, formFqn);
                        if (form == null)
                        {
                            return "Error: Form not found by FQN: " + formFqn //$NON-NLS-1$
                                + suggestSimilarFqns(transaction, formFqn);
                        }

                        return action.execute(transaction, form);
                    }
                    return null;
                });

            // executeReadWriteTask lives on IBmModelManager (com._1c.g5.v8.dt.core.platform),
            // not on IBmModel (com._1c.g5.v8.bm.integration). The overload we want:
            // executeReadWriteTask(IProject, IBmSingleNamespaceTask<T>)
            Method executeMethod = findExecuteMethod(bmModelManager, "executeReadWriteTask", IProject.class); //$NON-NLS-1$
            if (executeMethod == null)
            {
                // Fallback: try with generic parameter types
                executeMethod = findExecuteMethod(bmModelManager, "executeReadWriteTask", null); //$NON-NLS-1$
            }
            if (executeMethod == null)
            {
                return "Error: Cannot find executeReadWriteTask method on IBmModelManager"; //$NON-NLS-1$
            }

            Object result = executeMethod.invoke(bmModelManager, project, taskProxy);

            if (result instanceof String)
            {
                return (String) result;
            }

            return null; // Success, no error
        }
        catch (Exception e)
        {
            Activator.logError("BM form operation failed", e); //$NON-NLS-1$
            String message = "Error: BM API error: " + e.getMessage(); //$NON-NLS-1$
            if (e.getCause() != null)
            {
                message += " (cause: " + e.getCause().getMessage() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
            }
            return message;
        }
    }

    // -----------------------------------------------------------------------
    // Element creation methods
    // -----------------------------------------------------------------------

    /**
     * Creates a form field element with the specified properties.
     *
     * @param name field name
     * @param title field title (displayed to user)
     * @param fieldType field type (InputField, CheckBox, RadioButton, Label, Image)
     * @return the created field object
     * @throws Exception if creation fails
     */
    public Object createFormField(String name, String title, String fieldType) throws Exception
    {
        Object field = ffClass.getMethod("createFormField").invoke(formFactory); //$NON-NLS-1$
        setBasicProperties(field, name, nextId());
        setTitle(field, title);
        setVisibility(field);
        createExtendedTooltip(field, name + "\u0420\u0430\u0441\u0448\u0438\u0440\u0435\u043d\u043d\u0430\u044f\u041f\u043e\u0434\u0441\u043a\u0430\u0437\u043a\u0430", nextId()); //$NON-NLS-1$
        if (fieldType != null && !fieldType.isEmpty())
        {
            setFieldType(field, fieldType);
            setFieldExtInfo(field, fieldType);
        }
        return field;
    }

    /**
     * Creates a form group element with the specified properties.
     *
     * @param name group name
     * @param title group title
     * @param groupType group type (UsualGroup, Pages, Page, Column, CommandBar)
     * @return the created group object
     * @throws Exception if creation fails
     */
    public Object createFormGroup(String name, String title, String groupType) throws Exception
    {
        Object group = ffClass.getMethod("createFormGroup").invoke(formFactory); //$NON-NLS-1$
        setBasicProperties(group, name, nextId());
        setTitle(group, title);
        setVisibility(group);
        createExtendedTooltip(group, name + "\u0420\u0430\u0441\u0448\u0438\u0440\u0435\u043d\u043d\u0430\u044f\u041f\u043e\u0434\u0441\u043a\u0430\u0437\u043a\u0430", nextId()); //$NON-NLS-1$
        if (groupType != null && !groupType.isEmpty())
        {
            setGroupType(group, groupType);
            setGroupExtInfo(group, groupType);
        }
        return group;
    }

    /**
     * Creates a button element with the specified properties.
     *
     * @param name button name
     * @param title button title
     * @return the created button object
     * @throws Exception if creation fails
     */
    public Object createButton(String name, String title) throws Exception
    {
        Object button = ffClass.getMethod("createButton").invoke(formFactory); //$NON-NLS-1$
        setBasicProperties(button, name, nextId());
        setTitle(button, title);
        setVisibility(button);
        createExtendedTooltip(button, name + "\u0420\u0430\u0441\u0448\u0438\u0440\u0435\u043d\u043d\u0430\u044f\u041f\u043e\u0434\u0441\u043a\u0430\u0437\u043a\u0430", nextId()); //$NON-NLS-1$
        setRepresentation(button, "Auto"); //$NON-NLS-1$
        return button;
    }

    /**
     * Creates a table element with the specified properties.
     *
     * @param name table name
     * @param title table title
     * @return the created table object
     * @throws Exception if creation fails
     */
    public Object createTable(String name, String title) throws Exception
    {
        Object table = ffClass.getMethod("createTable").invoke(formFactory); //$NON-NLS-1$
        setBasicProperties(table, name, nextId());
        setTitle(table, title);
        setVisibility(table);
        createExtendedTooltip(table, name + "\u0420\u0430\u0441\u0448\u0438\u0440\u0435\u043d\u043d\u0430\u044f\u041f\u043e\u0434\u0441\u043a\u0430\u0437\u043a\u0430", nextId()); //$NON-NLS-1$
        return table;
    }

    /**
     * Creates a decoration element with the specified properties.
     *
     * @param name decoration name
     * @param title decoration title
     * @param decorationType decoration type ("Label" or "Picture")
     * @return the created decoration object
     * @throws Exception if creation fails
     */
    public Object createDecoration(String name, String title, String decorationType) throws Exception
    {
        Object decoration = ffClass.getMethod("createDecoration").invoke(formFactory); //$NON-NLS-1$
        setBasicProperties(decoration, name, nextId());
        setTitle(decoration, title);
        setVisibility(decoration);

        Class<?> decoTypeClass = Class.forName("com._1c.g5.v8.dt.form.model.ManagedFormDecorationType"); //$NON-NLS-1$
        Class<?> decoExtInfoClass = Class.forName("com._1c.g5.v8.dt.form.model.DecorationExtInfo"); //$NON-NLS-1$

        if ("Picture".equalsIgnoreCase(decorationType)) //$NON-NLS-1$
        {
            // Set type to PICTURE
            for (Object constant : decoTypeClass.getEnumConstants())
            {
                if ("PICTURE".equals(constant.toString())) //$NON-NLS-1$
                {
                    decorationIface.getMethod("setType", decoTypeClass).invoke(decoration, constant); //$NON-NLS-1$
                    break;
                }
            }
            Object extInfo = ffClass.getMethod("createPictureDecorationExtInfo").invoke(formFactory); //$NON-NLS-1$
            decorationIface.getMethod("setExtInfo", decoExtInfoClass).invoke(decoration, extInfo); //$NON-NLS-1$
        }
        else
        {
            // Default to LABEL
            for (Object constant : decoTypeClass.getEnumConstants())
            {
                if ("LABEL".equals(constant.toString())) //$NON-NLS-1$
                {
                    decorationIface.getMethod("setType", decoTypeClass).invoke(decoration, constant); //$NON-NLS-1$
                    break;
                }
            }
            Object extInfo = ffClass.getMethod("createLabelDecorationExtInfo").invoke(formFactory); //$NON-NLS-1$
            decorationIface.getMethod("setExtInfo", decoExtInfoClass).invoke(decoration, extInfo); //$NON-NLS-1$
        }

        decorationIface.getMethod("setAutoMaxWidth", Boolean.TYPE).invoke(decoration, true); //$NON-NLS-1$
        decorationIface.getMethod("setAutoMaxHeight", Boolean.TYPE).invoke(decoration, true); //$NON-NLS-1$
        return decoration;
    }

    /**
     * Creates a form command with the specified properties.
     *
     * @param name command name
     * @param title command title
     * @return the created command object
     * @throws Exception if creation fails
     */
    public Object createFormCommand(String name, String title) throws Exception
    {
        Object command = ffClass.getMethod("createFormCommand").invoke(formFactory); //$NON-NLS-1$
        namedIface.getMethod("setName", String.class).invoke(command, name); //$NON-NLS-1$
        formCommandIface.getMethod("setId", Integer.TYPE).invoke(command, nextId()); //$NON-NLS-1$
        setTitle(command, title);
        return command;
    }

    // -----------------------------------------------------------------------
    // Property setters
    // -----------------------------------------------------------------------

    /**
     * Sets name and ID on a form item.
     *
     * @param item the form item
     * @param name the name to set
     * @param id the ID to set
     * @throws Exception if setting fails
     */
    public void setBasicProperties(Object item, String name, int id) throws Exception
    {
        namedIface.getMethod("setName", String.class).invoke(item, name); //$NON-NLS-1$
        formItemIface.getMethod("setId", Integer.TYPE).invoke(item, id); //$NON-NLS-1$
    }

    /**
     * Sets the title on a titled element via {@code getTitle().put("ru", title)}.
     *
     * @param item the titled element
     * @param title the title text
     * @throws Exception if setting fails
     */
    public void setTitle(Object item, String title) throws Exception
    {
        if (title == null || title.isEmpty())
        {
            return;
        }
        Object titleMap = titledIface.getMethod("getTitle").invoke(item); //$NON-NLS-1$
        titleMap.getClass().getMethod("put", Object.class, Object.class) //$NON-NLS-1$
            .invoke(titleMap, "ru", title); //$NON-NLS-1$
    }

    /**
     * Sets visibility and enabled state on a visible element.
     * Sets {@code visible=true}, {@code enabled=true},
     * {@code userVisible=AdjustableBoolean(common=true)}.
     *
     * @param item the visible element
     * @throws Exception if setting fails
     */
    public void setVisibility(Object item) throws Exception
    {
        visibleIface.getMethod("setVisible", Boolean.TYPE).invoke(item, true); //$NON-NLS-1$
        visibleIface.getMethod("setEnabled", Boolean.TYPE).invoke(item, true); //$NON-NLS-1$

        Object adjBool = mdFactoryClass.getMethod("createAdjustableBoolean").invoke(mdFactory); //$NON-NLS-1$
        adjBoolClass.getMethod("setCommon", Boolean.TYPE).invoke(adjBool, true); //$NON-NLS-1$
        visibleIface.getMethod("setUserVisible", adjBoolClass).invoke(item, adjBool); //$NON-NLS-1$
    }

    /**
     * Creates and attaches an extended tooltip to a form element.
     * Required for every form element in EDT.
     *
     * @param parent the parent element
     * @param name tooltip name
     * @param id tooltip ID
     * @throws Exception if creation fails
     */
    public void createExtendedTooltip(Object parent, String name, int id) throws Exception
    {
        Object tooltip = ffClass.getMethod("createExtendedTooltip").invoke(formFactory); //$NON-NLS-1$
        namedIface.getMethod("setName", String.class).invoke(tooltip, name); //$NON-NLS-1$
        formItemIface.getMethod("setId", Integer.TYPE).invoke(tooltip, id); //$NON-NLS-1$

        // Set type to LABEL (ordinal 1)
        Class<?> decoClass = Class.forName("com._1c.g5.v8.dt.form.model.Decoration"); //$NON-NLS-1$
        Class<?> decoTypeClass = Class.forName("com._1c.g5.v8.dt.form.model.ManagedFormDecorationType"); //$NON-NLS-1$
        Method getMethod = decoTypeClass.getMethod("get", Integer.TYPE); //$NON-NLS-1$
        Object labelType = getMethod.invoke(null, 1);
        decoClass.getMethod("setType", decoTypeClass).invoke(tooltip, labelType); //$NON-NLS-1$

        decoClass.getMethod("setAutoMaxWidth", Boolean.TYPE).invoke(tooltip, true); //$NON-NLS-1$
        decoClass.getMethod("setAutoMaxHeight", Boolean.TYPE).invoke(tooltip, true); //$NON-NLS-1$

        // Create LabelDecorationExtInfo with horizontalAlign=Left
        Object extInfo = ffClass.getMethod("createLabelDecorationExtInfo").invoke(formFactory); //$NON-NLS-1$
        try
        {
            Class<?> labelExtClass = Class.forName("com._1c.g5.v8.dt.form.model.LabelDecorationExtInfo"); //$NON-NLS-1$
            Class<?> hAlignClass = Class.forName("com._1c.g5.v8.dt.form.model.ItemHorizontalAlignment"); //$NON-NLS-1$
            for (Object constant : hAlignClass.getEnumConstants())
            {
                if ("LEFT".equals(constant.toString())) //$NON-NLS-1$
                {
                    labelExtClass.getMethod("setHorizontalAlign", hAlignClass) //$NON-NLS-1$
                        .invoke(extInfo, constant);
                    break;
                }
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("Failed to set horizontalAlign on tooltip: " + e.getMessage()); //$NON-NLS-1$
        }

        Class<?> decoExtInfoClass = Class.forName("com._1c.g5.v8.dt.form.model.DecorationExtInfo"); //$NON-NLS-1$
        decoClass.getMethod("setExtInfo", decoExtInfoClass).invoke(tooltip, extInfo); //$NON-NLS-1$

        Class<?> extTooltipClass = Class.forName("com._1c.g5.v8.dt.form.model.ExtendedTooltip"); //$NON-NLS-1$
        extTooltipHolderIface.getMethod("setExtendedTooltip", extTooltipClass) //$NON-NLS-1$
            .invoke(parent, tooltip);
    }

    /**
     * Sets the data path binding on a form item.
     *
     * @param item the form item (must implement DataItem)
     * @param dataPath the data path string (e.g. "Object.Name"), segments split by "."
     * @throws Exception if setting fails
     */
    public void setDataPath(Object item, String dataPath) throws Exception
    {
        if (dataPath == null || dataPath.isEmpty())
        {
            return;
        }

        Object pathObj = ffClass.getMethod("createDataPath").invoke(formFactory); //$NON-NLS-1$
        Class<?> abstractDataPathClass = Class.forName("com._1c.g5.v8.dt.form.model.AbstractDataPath"); //$NON-NLS-1$
        Object segments = abstractDataPathClass.getMethod("getSegments").invoke(pathObj); //$NON-NLS-1$

        for (String segment : dataPath.split("\\.")) //$NON-NLS-1$
        {
            segments.getClass().getMethod("add", Object.class).invoke(segments, segment); //$NON-NLS-1$
        }

        Class<?> dataItemClass = Class.forName("com._1c.g5.v8.dt.form.model.DataItem"); //$NON-NLS-1$
        dataItemClass.getMethod("setDataPath", abstractDataPathClass).invoke(item, pathObj); //$NON-NLS-1$
    }

    // -----------------------------------------------------------------------
    // Container operations
    // -----------------------------------------------------------------------

    /**
     * Adds an item to a container's items list.
     *
     * @param container the container (form or group)
     * @param item the item to add
     * @throws Exception if adding fails
     */
    public void addToContainer(Object container, Object item) throws Exception
    {
        Object items = containerIface.getMethod("getItems").invoke(container); //$NON-NLS-1$
        items.getClass().getMethod("add", Object.class).invoke(items, item); //$NON-NLS-1$
    }

    /**
     * Adds an item to a container before the element with the specified name.
     * If the element is not found, adds to the end.
     *
     * @param container the container
     * @param item the item to add
     * @param beforeName the name of the element to insert before
     * @throws Exception if adding fails
     */
    public void addToContainerBefore(Object container, Object item, String beforeName) throws Exception
    {
        Object items = containerIface.getMethod("getItems").invoke(container); //$NON-NLS-1$
        int size = (Integer) items.getClass().getMethod("size").invoke(items); //$NON-NLS-1$
        int insertIndex = size; // Default: end of list

        for (int i = 0; i < size; i++)
        {
            Object existing = items.getClass().getMethod("get", Integer.TYPE).invoke(items, i); //$NON-NLS-1$
            try
            {
                String existingName = (String) namedIface.getMethod("getName").invoke(existing); //$NON-NLS-1$
                if (beforeName.equals(existingName))
                {
                    insertIndex = i;
                    break;
                }
            }
            catch (Exception e)
            {
                // Element may not be named, skip
            }
        }

        items.getClass().getMethod("add", Integer.TYPE, Object.class) //$NON-NLS-1$
            .invoke(items, insertIndex, item);
    }

    /**
     * Links a button to a command by setting the button's commandName property.
     *
     * @param button the button object
     * @param command the command object
     * @throws Exception if linking fails
     */
    public void linkButtonToCommand(Object button, Object command) throws Exception
    {
        buttonIface.getMethod("setCommandName", commandIface).invoke(button, command); //$NON-NLS-1$
    }

    /**
     * Adds an attribute to a form's attributes list.
     *
     * @param form the form object
     * @param attr the attribute to add
     * @throws Exception if adding fails
     */
    public void addAttributeToForm(Object form, Object attr) throws Exception
    {
        Object attributes = formIface.getMethod("getAttributes").invoke(form); //$NON-NLS-1$
        attributes.getClass().getMethod("add", Object.class).invoke(attributes, attr); //$NON-NLS-1$
    }

    /**
     * Adds a command to a form's commands list.
     *
     * @param form the form object
     * @param cmd the command to add
     * @throws Exception if adding fails
     */
    public void addCommandToForm(Object form, Object cmd) throws Exception
    {
        Object commands = formIface.getMethod("getFormCommands").invoke(form); //$NON-NLS-1$
        commands.getClass().getMethod("add", Object.class).invoke(commands, cmd); //$NON-NLS-1$
    }

    // -----------------------------------------------------------------------
    // Search and removal
    // -----------------------------------------------------------------------

    /**
     * Finds an item by name recursively in a container hierarchy.
     *
     * @param container the container to search in
     * @param name the item name to find
     * @return the found item, or {@code null} if not found
     * @throws Exception if search fails
     */
    public Object findItemByName(Object container, String name) throws Exception
    {
        Object items = containerIface.getMethod("getItems").invoke(container); //$NON-NLS-1$
        int size = (Integer) items.getClass().getMethod("size").invoke(items); //$NON-NLS-1$

        for (int i = 0; i < size; i++)
        {
            Object item = items.getClass().getMethod("get", Integer.TYPE).invoke(items, i); //$NON-NLS-1$
            try
            {
                String itemName = (String) namedIface.getMethod("getName").invoke(item); //$NON-NLS-1$
                if (name.equals(itemName))
                {
                    return item;
                }
            }
            catch (Exception e)
            {
                // Element may not be named
            }
            // Recurse into child containers
            if (containerIface.isInstance(item))
            {
                Object found = findItemByName(item, name);
                if (found != null)
                {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * Removes an item by name from a container (non-recursive, direct children only).
     *
     * @param container the container to remove from
     * @param name the item name to remove
     * @return {@code true} if the item was found and removed
     * @throws Exception if removal fails
     */
    public boolean removeItemByName(Object container, String name) throws Exception
    {
        Object items = containerIface.getMethod("getItems").invoke(container); //$NON-NLS-1$
        int size = (Integer) items.getClass().getMethod("size").invoke(items); //$NON-NLS-1$

        for (int i = 0; i < size; i++)
        {
            Object item = items.getClass().getMethod("get", Integer.TYPE).invoke(items, i); //$NON-NLS-1$
            try
            {
                String itemName = (String) namedIface.getMethod("getName").invoke(item); //$NON-NLS-1$
                if (name.equals(itemName))
                {
                    items.getClass().getMethod("remove", Integer.TYPE).invoke(items, i); //$NON-NLS-1$
                    return true;
                }
            }
            catch (Exception e)
            {
                // Element may not be named
            }
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // ID management
    // -----------------------------------------------------------------------

    /**
     * Resets the ID counter to the maximum ID found in the form.
     * Must be called before adding new elements to avoid ID collisions.
     *
     * @param form the form object
     * @throws Exception if scanning fails
     */
    public void resetIdCounter(Object form) throws Exception
    {
        idCounter = findMaxId(form);
    }

    /**
     * Returns the next available ID and increments the counter.
     *
     * @return next ID
     */
    public int nextId()
    {
        return ++idCounter;
    }

    /**
     * Finds the maximum ID across all items and commands in the form.
     *
     * @param form the form object
     * @return the maximum ID found
     * @throws Exception if scanning fails
     */
    public int findMaxId(Object form) throws Exception
    {
        int maxId = findMaxIdRecursive(form);

        // Also check commands
        Object commands = formIface.getMethod("getFormCommands").invoke(form); //$NON-NLS-1$
        int cmdSize = (Integer) commands.getClass().getMethod("size").invoke(commands); //$NON-NLS-1$
        for (int i = 0; i < cmdSize; i++)
        {
            Object cmd = commands.getClass().getMethod("get", Integer.TYPE).invoke(commands, i); //$NON-NLS-1$
            int cmdId = (Integer) formCommandIface.getMethod("getId").invoke(cmd); //$NON-NLS-1$
            if (cmdId > maxId)
            {
                maxId = cmdId;
            }
        }

        return Math.max(maxId, 0);
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private int findMaxIdRecursive(Object container) throws Exception
    {
        int maxId = 0;
        Object items = containerIface.getMethod("getItems").invoke(container); //$NON-NLS-1$
        int size = (Integer) items.getClass().getMethod("size").invoke(items); //$NON-NLS-1$

        for (int i = 0; i < size; i++)
        {
            Object item = items.getClass().getMethod("get", Integer.TYPE).invoke(items, i); //$NON-NLS-1$
            try
            {
                int itemId = (Integer) formItemIface.getMethod("getId").invoke(item); //$NON-NLS-1$
                if (itemId > maxId)
                {
                    maxId = itemId;
                }
            }
            catch (Exception e)
            {
                // Item may not have getId
            }
            if (containerIface.isInstance(item))
            {
                int childMax = findMaxIdRecursive(item);
                if (childMax > maxId)
                {
                    maxId = childMax;
                }
            }
        }
        return maxId;
    }

    private void setFieldType(Object field, String fieldType) throws Exception
    {
        Class<?> fieldTypeClass = Class.forName("com._1c.g5.v8.dt.form.model.ManagedFormFieldType"); //$NON-NLS-1$
        Object matched = null;

        // Try direct match first
        for (Object constant : fieldTypeClass.getEnumConstants())
        {
            String name = constant.toString();
            if (fieldType.equalsIgnoreCase(name)
                || fieldType.replace("Field", "").equalsIgnoreCase( //$NON-NLS-1$ //$NON-NLS-2$
                    name.replace("_FIELD", "").replace("_", ""))) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            {
                matched = constant;
                break;
            }
        }

        // Try constructed name
        if (matched == null)
        {
            String constructed = fieldType.toUpperCase().replace("FIELD", "_FIELD"); //$NON-NLS-1$ //$NON-NLS-2$
            if (!constructed.endsWith("_FIELD")) //$NON-NLS-1$
            {
                constructed = constructed + "_FIELD"; //$NON-NLS-1$
            }
            for (Object constant : fieldTypeClass.getEnumConstants())
            {
                if (constant.toString().equals(constructed))
                {
                    matched = constant;
                    break;
                }
            }
        }

        if (matched != null)
        {
            formFieldIface.getMethod("setType", fieldTypeClass).invoke(field, matched); //$NON-NLS-1$
        }
    }

    private void setFieldExtInfo(Object field, String fieldType) throws Exception
    {
        String lower = fieldType.toLowerCase();
        Object extInfo;

        if (lower.contains("checkbox") || lower.contains("check_box")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            extInfo = ffClass.getMethod("createCheckBoxFieldExtInfo").invoke(formFactory); //$NON-NLS-1$
        }
        else if (lower.contains("radio") || lower.contains("radiobutton")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            extInfo = ffClass.getMethod("createRadioButtonsFieldExtInfo").invoke(formFactory); //$NON-NLS-1$
        }
        else if (lower.contains("label")) //$NON-NLS-1$
        {
            extInfo = ffClass.getMethod("createLabelFieldExtInfo").invoke(formFactory); //$NON-NLS-1$
        }
        else if (lower.contains("image") || lower.contains("picture")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            extInfo = ffClass.getMethod("createImageFieldExtInfo").invoke(formFactory); //$NON-NLS-1$
        }
        else
        {
            extInfo = ffClass.getMethod("createInputFieldExtInfo").invoke(formFactory); //$NON-NLS-1$
        }

        if (extInfo != null)
        {
            Class<?> fieldExtInfoClass = Class.forName("com._1c.g5.v8.dt.form.model.FieldExtInfo"); //$NON-NLS-1$
            formFieldIface.getMethod("setExtInfo", fieldExtInfoClass).invoke(field, extInfo); //$NON-NLS-1$
        }
    }

    private void setGroupType(Object group, String groupType) throws Exception
    {
        Class<?> groupTypeClass = Class.forName("com._1c.g5.v8.dt.form.model.ManagedFormGroupType"); //$NON-NLS-1$
        for (Object constant : groupTypeClass.getEnumConstants())
        {
            if (groupType.equalsIgnoreCase(constant.toString()))
            {
                formGroupIface.getMethod("setType", groupTypeClass).invoke(group, constant); //$NON-NLS-1$
                return;
            }
        }
    }

    private void setGroupExtInfo(Object group, String groupType) throws Exception
    {
        String lower = groupType.toLowerCase();
        Object extInfo;

        if (lower.contains("pages") && !lower.contains("page")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            extInfo = ffClass.getMethod("createPagesGroupExtInfo").invoke(formFactory); //$NON-NLS-1$
        }
        else if (lower.contains("page")) //$NON-NLS-1$
        {
            extInfo = ffClass.getMethod("createPageGroupExtInfo").invoke(formFactory); //$NON-NLS-1$
        }
        else if (lower.contains("column")) //$NON-NLS-1$
        {
            extInfo = ffClass.getMethod("createColumnGroupExtInfo").invoke(formFactory); //$NON-NLS-1$
        }
        else if (lower.contains("command") || lower.contains("commandbar")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            extInfo = ffClass.getMethod("createCommandBarExtInfo").invoke(formFactory); //$NON-NLS-1$
        }
        else if (lower.contains("button")) //$NON-NLS-1$
        {
            extInfo = ffClass.getMethod("createButtonGroupExtInfo").invoke(formFactory); //$NON-NLS-1$
        }
        else if (lower.contains("popup")) //$NON-NLS-1$
        {
            extInfo = ffClass.getMethod("createPopupGroupExtInfo").invoke(formFactory); //$NON-NLS-1$
        }
        else
        {
            extInfo = ffClass.getMethod("createUsualGroupExtInfo").invoke(formFactory); //$NON-NLS-1$
        }

        if (extInfo != null)
        {
            Class<?> groupExtInfoClass = Class.forName("com._1c.g5.v8.dt.form.model.GroupExtInfo"); //$NON-NLS-1$
            formGroupIface.getMethod("setExtInfo", groupExtInfoClass).invoke(group, extInfo); //$NON-NLS-1$
        }
    }

    private void setRepresentation(Object button, String representation) throws Exception
    {
        try
        {
            Class<?> reprClass = Class.forName("com._1c.g5.v8.dt.mcore.ButtonRepresentation"); //$NON-NLS-1$
            for (Object constant : reprClass.getEnumConstants())
            {
                if (representation.equalsIgnoreCase(constant.toString()))
                {
                    buttonIface.getMethod("setRepresentation", reprClass) //$NON-NLS-1$
                        .invoke(button, constant);
                    return;
                }
            }
        }
        catch (Exception e)
        {
            // Non-fatal - representation is optional
        }
    }

    /**
     * Builds a diagnostic suffix listing BM top-object FQNs that contain the
     * object name or form name from the requested FQN. Helps the caller discover
     * the actual FQN used by the BM namespace (e.g. for borrowed forms in
     * extensions where the FQN format may differ from the main configuration).
     */
    private String suggestSimilarFqns(Object transaction, String requestedFqn)
    {
        try
        {
            String[] parts = requestedFqn.split("\\."); //$NON-NLS-1$
            String objectName = parts.length >= 2 ? parts[1] : null;
            String formName = parts.length >= 1 ? parts[parts.length - 1] : null;

            @SuppressWarnings("unchecked")
            Iterator<Object> iter = (Iterator<Object>) txIface.getMethod("getTopObjectIterator") //$NON-NLS-1$
                .invoke(transaction);

            List<String> objectMatches = new ArrayList<>();
            List<String> formMatches = new ArrayList<>();
            int scanned = 0;
            while (iter.hasNext() && objectMatches.size() + formMatches.size() < 30)
            {
                Object obj = iter.next();
                scanned++;
                String fqn = (String) obj.getClass().getMethod("bmGetFqn").invoke(obj); //$NON-NLS-1$
                if (fqn == null)
                {
                    continue;
                }
                if (objectName != null && fqn.contains(objectName) && objectMatches.size() < 15)
                {
                    objectMatches.add(fqn);
                }
                else if (formName != null && fqn.contains(formName) && formMatches.size() < 15)
                {
                    formMatches.add(fqn);
                }
            }

            StringBuilder hint = new StringBuilder();
            if (!objectMatches.isEmpty())
            {
                hint.append("\n\nTop-objects containing '").append(objectName).append("':\n"); //$NON-NLS-1$ //$NON-NLS-2$
                for (String fqn : objectMatches)
                {
                    hint.append("  - ").append(fqn).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            if (!formMatches.isEmpty())
            {
                hint.append("\n\nTop-objects containing '").append(formName).append("':\n"); //$NON-NLS-1$ //$NON-NLS-2$
                for (String fqn : formMatches)
                {
                    hint.append("  - ").append(fqn).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            if (hint.length() == 0)
            {
                hint.append("\n\nNo matching top-objects found (scanned ").append(scanned).append(")."); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return hint.toString();
        }
        catch (Exception e)
        {
            return "\n\n(diagnostic scan failed: " + e.getMessage() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Finds a method by name on an object where the first parameter is assignable
     * from the given class and the method has exactly 2 parameters.
     *
     * @param target the object to search on
     * @param methodName the method name
     * @param firstParamType the expected first parameter type, or {@code null} to skip the check
     * @return the found method, or {@code null}
     */
    private Method findExecuteMethod(Object target, String methodName, Class<?> firstParamType)
    {
        for (Method method : target.getClass().getMethods())
        {
            if (methodName.equals(method.getName()) && method.getParameterCount() == 2)
            {
                if (firstParamType == null
                    || method.getParameterTypes()[0].isAssignableFrom(firstParamType))
                {
                    return method;
                }
            }
        }
        return null;
    }
}
