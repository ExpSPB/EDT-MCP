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
 * Based on a proven BmFormHelper pattern for real EDT compatibility.
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

            // Error case: the proxy / action returns a String prefixed with
            // "Error:" to surface a fatal condition (form not found, etc.).
            if (result instanceof String && ((String) result).startsWith("Error:")) //$NON-NLS-1$
            {
                return (String) result;
            }

            // Persist BM changes to disk: forceExport(IDtProject, formFqn).
            // Without this step changes remain in the BM in-memory namespace
            // and the Form.form file on disk is never updated.
            persistFormChanges(bmModelManager, project, formFqn);

            // Success-with-message: the action returns a non-error String
            // describing what it did (e.g. "added attribute X"). Surface it
            // into the response while still persisting first.
            if (result instanceof String)
            {
                return (String) result;
            }
            return null; // Success, no message
        }
        catch (Exception e)
        {
            Activator.logError("BM form operation failed", e); //$NON-NLS-1$
            // 1.41: defensive unwrap so InvocationTargetException /
            // UndeclaredThrowableException do not strip the original
            // formApiNotFound: marker.
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root)
            {
                root = root.getCause();
            }
            String rootMsg = root.getMessage() != null ? root.getMessage()
                : root.getClass().getSimpleName();
            return "Error: BM API error: " + rootMsg; //$NON-NLS-1$
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

    /**
     * Creates a form attribute with the given name and optional title via
     * {@code FormFactory.createFormAttribute()}. The caller adds the result
     * to the form's attributes collection via {@link #addAttributeToForm}.
     *
     * @param name attribute name (mandatory)
     * @param title attribute title (optional)
     * @return the created FormAttribute object
     * @throws Exception if creation fails
     */
    public Object createFormAttribute(String name, String title) throws Exception
    {
        Object attribute = ffClass.getMethod("createFormAttribute").invoke(formFactory); //$NON-NLS-1$
        namedIface.getMethod("setName", String.class).invoke(attribute, name); //$NON-NLS-1$
        if (title != null && !title.isEmpty())
        {
            try
            {
                setTitle(attribute, title);
            }
            catch (Exception ignored)
            {
                // FormAttribute may not implement Titled in every EDT version
            }
        }
        return attribute;
    }

    // -----------------------------------------------------------------------
    // 1.41: Forms 3 deferred ops (addFormAttributeColumn,
    //       addDynamicListTable, setupSettingsComposer)
    // -----------------------------------------------------------------------

    /**
     * 1.41: probes a factory method on either {@link #formFactory} or
     * {@link #mdFactory} by name, returning the freshly-created EObject
     * or {@code null} when no candidate exists.
     */
    private Object probeFactoryCreate(String... methodCandidates)
    {
        for (String mname : methodCandidates)
        {
            try
            {
                return ffClass.getMethod(mname).invoke(formFactory);
            }
            catch (NoSuchMethodException ignored)
            {
                // try next candidate / factory
            }
            catch (Exception ignored)
            {
                // factory exists but threw - move on
            }
            try
            {
                return mdFactoryClass.getMethod(mname).invoke(mdFactory);
            }
            catch (NoSuchMethodException ignored)
            {
                // try next candidate
            }
            catch (Exception ignored)
            {
                // method exists but threw - move on
            }
        }
        return null;
    }

    /**
     * 1.41: searches the form's top-level attributes list for a FormAttribute
     * by name. Case-insensitive. Returns {@code null} when not found.
     */
    private Object findFormAttributeByName(Object form, String name) throws Exception
    {
        Object attributes = formIface.getMethod("getAttributes").invoke(form); //$NON-NLS-1$
        int size = (Integer) attributes.getClass().getMethod("size").invoke(attributes); //$NON-NLS-1$
        for (int i = 0; i < size; i++)
        {
            Object attr = attributes.getClass().getMethod("get", Integer.TYPE).invoke(attributes, i); //$NON-NLS-1$
            try
            {
                String attrName = (String) namedIface.getMethod("getName").invoke(attr); //$NON-NLS-1$
                if (name.equalsIgnoreCase(attrName))
                {
                    return attr;
                }
            }
            catch (Exception ignored)
            {
                // unnamed entry, skip
            }
        }
        return null;
    }

    /**
     * 1.41: invokes a single-parameter setter on a target by name. Used for
     * {@code setExtInfo} where the parameter type varies across attribute
     * subtypes (DynamicListExtInfo, DataCompositionSettingsComposerExtInfo,
     * etc.).
     */
    private void invokeSingleParamSetter(Object target, String setterName, Object value) throws Exception
    {
        for (Method m : target.getClass().getMethods())
        {
            if (m.getName().equals(setterName) && m.getParameterCount() == 1)
            {
                m.invoke(target, value);
                return;
            }
        }
        throw new NoSuchMethodException(setterName + "(...) on " + target.getClass().getName()); //$NON-NLS-1$
    }

    /**
     * 1.41: adds a column to a parent FormAttribute of type Table.
     * <p>
     * Idempotent: a column with the same name already attached to the
     * parent attribute returns a propertyMismatch-style notice instead of
     * duplicating. When the EDT factory does not expose
     * {@code createFormAttributeColumn}, throws an
     * {@link UnsupportedOperationException} prefixed with {@code formApiNotFound:}
     * so the caller can surface a structured error tag.
     *
     * @return descriptive success message
     */
    public String addFormAttributeColumn(Object form, String parentAttributeName,
        String name, String title, String dataPath) throws Exception
    {
        if (parentAttributeName == null || parentAttributeName.isEmpty())
        {
            throw new IllegalArgumentException("parentAttributeName is required"); //$NON-NLS-1$
        }
        if (name == null || name.isEmpty())
        {
            throw new IllegalArgumentException("name is required"); //$NON-NLS-1$
        }
        Object parent = findFormAttributeByName(form, parentAttributeName);
        if (parent == null)
        {
            throw new IllegalStateException("FormAttribute not found: " + parentAttributeName); //$NON-NLS-1$
        }

        // Idempotency: check existing columns
        Object columns;
        try
        {
            columns = parent.getClass().getMethod("getColumns").invoke(parent); //$NON-NLS-1$
        }
        catch (NoSuchMethodException e)
        {
            throw new UnsupportedOperationException(
                "formApiNotFound: parent FormAttribute has no getColumns() - " //$NON-NLS-1$
                    + "is the parent of type Table?"); //$NON-NLS-1$
        }
        int size = (Integer) columns.getClass().getMethod("size").invoke(columns); //$NON-NLS-1$
        for (int i = 0; i < size; i++)
        {
            Object existing = columns.getClass().getMethod("get", Integer.TYPE).invoke(columns, i); //$NON-NLS-1$
            try
            {
                String existingName = (String) namedIface.getMethod("getName").invoke(existing); //$NON-NLS-1$
                if (name.equalsIgnoreCase(existingName))
                {
                    return "addFormAttributeColumn idempotent: column '" + name //$NON-NLS-1$
                        + "' already exists in '" + parentAttributeName + "'"; //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            catch (Exception ignored)
            {
                // skip unnamed
            }
        }

        Object column = probeFactoryCreate("createFormAttributeColumn"); //$NON-NLS-1$
        if (column == null)
        {
            throw new UnsupportedOperationException(
                "formApiNotFound: createFormAttributeColumn (tried FormFactory, MdClassFactory)"); //$NON-NLS-1$
        }
        namedIface.getMethod("setName", String.class).invoke(column, name); //$NON-NLS-1$
        if (title != null && !title.isEmpty())
        {
            try
            {
                setTitle(column, title);
            }
            catch (Exception ignored)
            {
                // column may not implement Titled in every EDT version
            }
        }
        if (dataPath != null && !dataPath.isEmpty())
        {
            try
            {
                setDataPath(column, dataPath);
            }
            catch (Exception ignored)
            {
                // column may not be a DataItem
            }
        }
        columns.getClass().getMethod("add", Object.class).invoke(columns, column); //$NON-NLS-1$
        return "added column '" + name + "' to FormAttribute '" //$NON-NLS-1$ //$NON-NLS-2$
            + parentAttributeName + "'"; //$NON-NLS-1$
    }

    /**
     * 1.41: creates a FormAttribute of type DynamicList plus a UI Table item
     * bound to it. Sets common wizard properties on the dynamic-list ExtInfo:
     * mainTable, autoSaveCustomization=true, dynamicDataRead=true.
     * <p>
     * The caller is responsible for adding the result Table to a container
     * via {@link #addToContainer}; this method only returns it through the
     * {@link DynamicListResult} struct.
     */
    public DynamicListResult addDynamicListAttributeAndTable(Object form,
        String attributeName, String tableName, String mainTable, String title) throws Exception
    {
        if (attributeName == null || attributeName.isEmpty())
        {
            throw new IllegalArgumentException("attributeName is required"); //$NON-NLS-1$
        }
        if (tableName == null || tableName.isEmpty())
        {
            throw new IllegalArgumentException("tableName is required"); //$NON-NLS-1$
        }
        // Idempotency: existing attribute with the same name
        Object existing = findFormAttributeByName(form, attributeName);
        if (existing != null)
        {
            DynamicListResult r = new DynamicListResult();
            r.attribute = existing;
            r.idempotent = true;
            r.message = "addDynamicListTable idempotent: FormAttribute '" //$NON-NLS-1$
                + attributeName + "' already exists"; //$NON-NLS-1$
            return r;
        }

        Object attribute = createFormAttribute(attributeName, title);

        Object extInfo = probeFactoryCreate(
            "createDynamicListExtInfo", //$NON-NLS-1$
            "createDynamicListAttributeExtInfo"); //$NON-NLS-1$
        if (extInfo == null)
        {
            throw new UnsupportedOperationException(
                "formApiNotFound: createDynamicListExtInfo " //$NON-NLS-1$
                    + "(tried FormFactory and MdClassFactory)"); //$NON-NLS-1$
        }
        invokeSingleParamSetter(attribute, "setExtInfo", extInfo); //$NON-NLS-1$

        // Wizard defaults: best-effort, ignore individual failures
        if (mainTable != null && !mainTable.isEmpty())
        {
            try
            {
                invokeSingleParamSetter(extInfo, "setMainTable", mainTable); //$NON-NLS-1$
            }
            catch (Exception ignored)
            {
                // setter may not exist, leave default
            }
        }
        for (String[] booleanProp : new String[][] {
            { "setAutoSaveCustomization", "true" }, //$NON-NLS-1$ //$NON-NLS-2$
            { "setDynamicDataRead", "true" }, //$NON-NLS-1$ //$NON-NLS-2$
            { "setCustomQuery", "false" } //$NON-NLS-1$ //$NON-NLS-2$
        })
        {
            try
            {
                Method setter = null;
                for (Method m : extInfo.getClass().getMethods())
                {
                    if (m.getName().equals(booleanProp[0]) && m.getParameterCount() == 1
                        && (m.getParameterTypes()[0] == Boolean.TYPE
                            || m.getParameterTypes()[0] == Boolean.class))
                    {
                        setter = m;
                        break;
                    }
                }
                if (setter != null)
                {
                    setter.invoke(extInfo, Boolean.parseBoolean(booleanProp[1]));
                }
            }
            catch (Exception ignored)
            {
                // best-effort
            }
        }

        addAttributeToForm(form, attribute);

        // Create the bound UI Table
        Object table = createTable(tableName, title);
        try
        {
            setDataPath(table, attributeName);
        }
        catch (Exception ignored)
        {
            // best-effort: dataPath wiring may need explicit setup later
        }

        DynamicListResult r = new DynamicListResult();
        r.attribute = attribute;
        r.table = table;
        r.idempotent = false;
        r.message = "added DynamicList FormAttribute '" + attributeName //$NON-NLS-1$
            + "' and UI Table '" + tableName + "'" //$NON-NLS-1$ //$NON-NLS-2$
            + (mainTable != null ? " (mainTable=" + mainTable + ")" : ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return r;
    }

    /**
     * 1.41: result of {@link #addDynamicListAttributeAndTable}.
     */
    public static final class DynamicListResult
    {
        public Object attribute;
        public Object table;
        public boolean idempotent;
        public String message;
    }

    /**
     * 1.41: creates a FormAttribute of type DataCompositionSettingsComposer
     * plus two UI tables (Settings + UserSettings) wired via dataPath.
     * Returns the composer attribute, both tables, and BSL initialization
     * snippets in both RU and EN dialects.
     */
    public SettingsComposerResult setupSettingsComposer(Object form, String composerName,
        String settingsTableName, String userSettingsTableName) throws Exception
    {
        if (composerName == null || composerName.isEmpty())
        {
            composerName = "Composer"; //$NON-NLS-1$
        }
        if (settingsTableName == null || settingsTableName.isEmpty())
        {
            settingsTableName = "SettingsTable"; //$NON-NLS-1$
        }
        if (userSettingsTableName == null || userSettingsTableName.isEmpty())
        {
            userSettingsTableName = "UserSettingsTable"; //$NON-NLS-1$
        }

        // Idempotency
        Object existing = findFormAttributeByName(form, composerName);
        if (existing != null)
        {
            SettingsComposerResult r = new SettingsComposerResult();
            r.composer = existing;
            r.idempotent = true;
            r.message = "setupSettingsComposerOnForm idempotent: FormAttribute '" //$NON-NLS-1$
                + composerName + "' already exists"; //$NON-NLS-1$
            populateSettingsComposerSnippets(r, composerName);
            return r;
        }

        Object composer = createFormAttribute(composerName, null);

        Object extInfo = probeFactoryCreate(
            "createDataCompositionSettingsComposerExtInfo", //$NON-NLS-1$
            "createSettingsComposerExtInfo"); //$NON-NLS-1$
        if (extInfo == null)
        {
            throw new UnsupportedOperationException(
                "formApiNotFound: createDataCompositionSettingsComposerExtInfo " //$NON-NLS-1$
                    + "(SettingsComposer ExtInfo factory not exposed)"); //$NON-NLS-1$
        }
        invokeSingleParamSetter(composer, "setExtInfo", extInfo); //$NON-NLS-1$
        addAttributeToForm(form, composer);

        Object settingsTable = createTable(settingsTableName, null);
        try
        {
            setDataPath(settingsTable, composerName + ".Settings"); //$NON-NLS-1$
        }
        catch (Exception ignored)
        {
            // best-effort
        }
        Object userSettingsTable = createTable(userSettingsTableName, null);
        try
        {
            setDataPath(userSettingsTable, composerName + ".UserSettings"); //$NON-NLS-1$
        }
        catch (Exception ignored)
        {
            // best-effort
        }

        SettingsComposerResult r = new SettingsComposerResult();
        r.composer = composer;
        r.settingsTable = settingsTable;
        r.userSettingsTable = userSettingsTable;
        r.idempotent = false;
        populateSettingsComposerSnippets(r, composerName);
        r.message = "setupSettingsComposerOnForm: created '" + composerName //$NON-NLS-1$
            + "' + UI tables '" + settingsTableName + "', '" + userSettingsTableName + "'"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return r;
    }

    /**
     * 1.41: fills RU and EN BSL snippets on the result struct so both
     * idempotent and non-idempotent paths surface the example to the AI.
     */
    private void populateSettingsComposerSnippets(SettingsComposerResult r, String composerName)
    {
        r.bslSnippetRu = "// 1.41: paste into ProcedureOnCreateAtServer (ru)\n" //$NON-NLS-1$
            + composerName + ".Инициализировать(" //$NON-NLS-1$ // Инициализировать
            + "Новый ИсточникДоступныхНастроекКомпоновкиДанных(СхемаКД));\n" //$NON-NLS-1$
            + composerName + ".ЗагрузитьНастройки(СхемаКД.НастройкиПоУмолчанию);"; //$NON-NLS-1$
        r.bslSnippetEn = "// 1.41: paste into ProcedureOnCreateAtServer (en)\n" //$NON-NLS-1$
            + composerName + ".Initialize(New DataCompositionAvailableSettingsSource(Schema));\n" //$NON-NLS-1$
            + composerName + ".LoadSettings(Schema.DefaultSettings);"; //$NON-NLS-1$
    }

    /**
     * 1.41: result of {@link #setupSettingsComposer}.
     */
    public static final class SettingsComposerResult
    {
        public Object composer;
        public Object settingsTable;
        public Object userSettingsTable;
        public boolean idempotent;
        public String message;
        public String bslSnippetRu;
        public String bslSnippetEn;
    }

    /**
     * Sets a property on the form item identified by {@code itemName} inside
     * {@code container} (form or sub-group). Uses reflection on
     * {@code setXxx(...)} setters with best-effort coercion for booleans
     * and the title pseudo-property (delegates to {@link #setTitle}).
     * <p>
     * Supported property names (case-sensitive against EMF setters):
     * {@code title}, {@code visible}, {@code enabled}, {@code readOnly},
     * {@code dataPath}, plus any EMF feature exposed as {@code setXxx}.
     *
     * @return {@code null} on success, an error description otherwise
     */
    public String setItemProperty(Object container, String itemName, String property, String value)
        throws Exception
    {
        Object item = findItemByName(container, itemName);
        if (item == null)
        {
            return "Form item not found: " + itemName; //$NON-NLS-1$
        }
        if (property == null || property.isEmpty())
        {
            return "property is required"; //$NON-NLS-1$
        }
        // Pseudo-property: title -> setTitle (handles the localized map)
        if ("title".equalsIgnoreCase(property)) //$NON-NLS-1$
        {
            try
            {
                setTitle(item, value);
                return null;
            }
            catch (Exception e)
            {
                return "Failed to set title: " + e.getMessage(); //$NON-NLS-1$
            }
        }
        if ("dataPath".equalsIgnoreCase(property)) //$NON-NLS-1$
        {
            try
            {
                setDataPath(item, value);
                return null;
            }
            catch (Exception e)
            {
                return "Failed to set dataPath: " + e.getMessage(); //$NON-NLS-1$
            }
        }
        String setter = "set" + Character.toUpperCase(property.charAt(0)) //$NON-NLS-1$
            + property.substring(1);
        for (Method m : item.getClass().getMethods())
        {
            if (!setter.equals(m.getName()) || m.getParameterCount() != 1)
            {
                continue;
            }
            Class<?> paramType = m.getParameterTypes()[0];
            try
            {
                Object converted = coerceFormValue(value, paramType);
                m.invoke(item, converted);
                return null;
            }
            catch (Exception e)
            {
                return "Failed to set " + property + ": " + e.getMessage(); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return "Property '" + property + "' not found on " //$NON-NLS-1$ //$NON-NLS-2$
            + item.getClass().getSimpleName();
    }

    /**
     * Best-effort coercion to a setter's parameter type: boolean, int, enum,
     * String. Other types are returned as-is and may throw downstream.
     */
    private static Object coerceFormValue(String value, Class<?> targetType)
    {
        if (value == null || targetType == String.class)
        {
            return value;
        }
        if (targetType == boolean.class || targetType == Boolean.class)
        {
            return Boolean.valueOf(value);
        }
        if (targetType == int.class || targetType == Integer.class)
        {
            return Integer.valueOf(value);
        }
        if (targetType == long.class || targetType == Long.class)
        {
            return Long.valueOf(value);
        }
        if (targetType.isEnum())
        {
            try
            {
                Method get = targetType.getMethod("get", String.class); //$NON-NLS-1$
                Object r = get.invoke(null, value);
                if (r != null)
                {
                    return r;
                }
            }
            catch (Exception ignored)
            {
                // fall through
            }
            try
            {
                Method getByName = targetType.getMethod("getByName", String.class); //$NON-NLS-1$
                Object r = getByName.invoke(null, value);
                if (r != null)
                {
                    return r;
                }
            }
            catch (Exception ignored)
            {
                // fall through
            }
            // Constant scan as the final fallback.
            for (Object c : targetType.getEnumConstants())
            {
                if (c.toString().equalsIgnoreCase(value)
                    || ((Enum<?>) c).name().equalsIgnoreCase(value))
                {
                    return c;
                }
            }
            throw new IllegalArgumentException("Unknown enum value '" + value //$NON-NLS-1$
                + "' for type " + targetType.getSimpleName()); //$NON-NLS-1$
        }
        return value;
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
     * Resets the ID counter considering both the form and its BaseForm.
     * Borrowed forms in extensions only expose override items via
     * {@code getItems()}, so scanning the BaseForm top-object is required
     * to pick up IDs inherited from the main configuration.
     *
     * @param form the form object
     * @param baseForm the BaseForm top-object, or {@code null} if not applicable
     * @throws Exception if scanning fails
     */
    public void resetIdCounter(Object form, Object baseForm) throws Exception
    {
        int max = findMaxId(form);
        if (baseForm != null)
        {
            int baseMax = findMaxId(baseForm);
            if (baseMax > max)
            {
                max = baseMax;
            }
        }
        idCounter = max;
    }

    /**
     * Resolves the BaseForm top-object for a borrowed form in an extension.
     * <p>
     * For a form FQN like {@code "Document.X.Form.Y.Form"}, the BaseForm (if
     * any) is a separate top-object with FQN {@code "Document.X.Form.Y.Form.BaseForm"}.
     * Returns {@code null} for forms in the main configuration or when no
     * BaseForm exists.
     *
     * @param transaction the active BM transaction
     * @param formFqn the main form FQN (including trailing {@code .Form})
     * @return the BaseForm object, or {@code null} if it does not exist
     * @throws Exception if the lookup fails
     */
    public Object findBaseForm(Object transaction, String formFqn) throws Exception
    {
        return txIface.getMethod("getTopObjectByFqn", String.class) //$NON-NLS-1$
            .invoke(transaction, formFqn + ".BaseForm"); //$NON-NLS-1$
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
     * Forces the BM namespace to export the given top-object to its backing
     * file. Without this step, changes committed inside a BM transaction stay
     * in the in-memory namespace and the {@code .form} file is never updated.
     * <p>
     * Uses {@code IBmModelManager.forceExport(IDtProject, String)} via
     * reflection (IDtProject is not a compile-time dependency). Falls back to
     * {@code waitModelSynchronization(IProject)} if forceExport is
     * unavailable. Both failures are non-fatal and logged as warnings.
     */
    private void persistFormChanges(IBmModelManager bmModelManager, IProject project, String formFqn)
    {
        try
        {
            Method getDtProject = bmModelManager.getClass().getMethod("getDtProject", String.class); //$NON-NLS-1$
            Object dtProject = getDtProject.invoke(bmModelManager, project.getName());
            if (dtProject != null)
            {
                Method forceExport = bmModelManager.getClass().getMethod("forceExport", dtProjectIface, String.class); //$NON-NLS-1$
                forceExport.invoke(bmModelManager, dtProject, formFqn);
                return;
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("forceExport failed, trying waitModelSynchronization: " + e.getMessage()); //$NON-NLS-1$
        }
        try
        {
            Method waitSync = bmModelManager.getClass().getMethod("waitModelSynchronization", IProject.class); //$NON-NLS-1$
            waitSync.invoke(bmModelManager, project);
        }
        catch (Exception e)
        {
            Activator.logWarning("waitModelSynchronization also failed: " + e.getMessage()); //$NON-NLS-1$
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
