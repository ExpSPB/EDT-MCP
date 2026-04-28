/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import com.ditrix.edt.mcp.server.Activator;

/**
 * DCS schema operations for {@code dcs_workshop}. Reflection-based to stay
 * compatible across EDT versions.
 * <p>
 * <b>1.35 status:</b> in-memory schema mutation through a write transaction.
 * Direct save to disk in extension projects is delegated to
 * {@link DcsExtensionExportHelper}; recovery from disk to BM is in
 * {@link DcsExtensionImportHelper}.
 */
public final class BmDcsHelper
{
    private static final String DCS_FACTORY = "com._1c.g5.v8.dt.dcs.model.DcsFactory"; //$NON-NLS-1$
    private static final String DCS_SCHEMA = "com._1c.g5.v8.dt.dcs.model.DataCompositionSchema"; //$NON-NLS-1$

    /** Default DCS template name on Reports / DataProcessors / etc. */
    public static final String DEFAULT_TEMPLATE_NAME = "MainDataCompositionSchema"; //$NON-NLS-1$

    private static volatile Boolean cachedAvailable;
    private static volatile Object cachedFactory;

    private BmDcsHelper()
    {
        // utility class
    }

    public static boolean isAvailable()
    {
        Boolean cached = cachedAvailable;
        if (cached != null)
        {
            return cached.booleanValue();
        }
        boolean ok = false;
        try
        {
            Class.forName(DCS_FACTORY);
            Class.forName(DCS_SCHEMA);
            ok = true;
        }
        catch (ClassNotFoundException e)
        {
            Activator.logWarning("BmDcsHelper: DCS API not available - " + e.getMessage()); //$NON-NLS-1$
        }
        cachedAvailable = Boolean.valueOf(ok);
        return ok;
    }

    public static String deferredMessage(String operation)
    {
        return "DCS operation '" + operation //$NON-NLS-1$
            + "' is not yet implemented in this build. " //$NON-NLS-1$
            + (isAvailable()
                ? "DCS API is reachable - implementation pending." //$NON-NLS-1$
                : "DCS API is NOT reachable in this EDT version."); //$NON-NLS-1$
    }

    /**
     * Resolved DcsFactory instance via {@code DcsFactory.eINSTANCE}, cached.
     */
    public static Object getFactory()
    {
        Object cached = cachedFactory;
        if (cached != null)
        {
            return cached;
        }
        try
        {
            Class<?> factoryClass = Class.forName(DCS_FACTORY);
            Object f = factoryClass.getField("eINSTANCE").get(null); //$NON-NLS-1$
            cachedFactory = f;
            return f;
        }
        catch (Exception e)
        {
            Activator.logWarning("BmDcsHelper.getFactory failed: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Result of a schema-mutation operation.
     * <p>
     * {@link #tags} carries machine-readable structured fields surfaced into
     * the JSON response (e.g. {@code notFound}, {@code alreadyExists},
     * {@code queryValidation}, {@code expressionValidation}). Always non-null.
     */
    public static final class Result
    {
        public boolean ok;
        public String error;
        public String schemaFqn;
        public String message;
        public DcsExtensionExportHelper.Result directSave; // populated for extensions
        public Map<String, Object> tags = new LinkedHashMap<>();
    }

    /**
     * Action executed inside a BM read-write transaction with the schema EObject
     * already resolved.
     */
    @FunctionalInterface
    public interface DcsAction
    {
        Object execute(IBmTransaction tx, EObject schema) throws Exception;
    }

    /**
     * Resolves the DCS schema by FQN and runs the action under a BM write
     * transaction. {@code dryRun=true} discards the changes.
     *
     * @param ownerFqn FQN of the metadata object (e.g. "Report.Sales") or the
     *            full schema FQN ("Report.Sales.Template.MainDCS.Template").
     */
    public static Result executeWriteOnSchema(IProject project, String ownerFqn, String templateName,
        boolean dryRun, DcsAction action)
    {
        Result r = new Result();
        if (project == null || ownerFqn == null || ownerFqn.isEmpty())
        {
            r.error = "project and ownerFqn are required"; //$NON-NLS-1$
            return r;
        }
        if (!isAvailable())
        {
            r.error = "DCS API is not available in this EDT runtime"; //$NON-NLS-1$
            return r;
        }
        String schemaFqn = buildSchemaFqn(ownerFqn, templateName);
        r.schemaFqn = schemaFqn;

        IBmModelManager mm = Activator.getDefault().getBmModelManager();
        if (mm == null)
        {
            r.error = "IBmModelManager not available"; //$NON-NLS-1$
            return r;
        }
        IBmModel model = mm.getModel(project);
        if (model == null)
        {
            r.error = "BM model not available for project: " + project.getName(); //$NON-NLS-1$
            return r;
        }

        try
        {
            model.execute(new AbstractBmTask<Void>("dcs_workshop.write") //$NON-NLS-1$
            {
                @Override
                public Void execute(IBmTransaction tx, IProgressMonitor pm)
                {
                    try
                    {
                        IBmObject top = tx.getTopObjectByFqn(schemaFqn);
                        if (top == null)
                        {
                            throw new RuntimeException("DCS schema not found by FQN: " + schemaFqn); //$NON-NLS-1$
                        }
                        Object res = action.execute(tx, (EObject) top);
                        if (res != null)
                        {
                            r.message = res.toString();
                        }
                        if (dryRun)
                        {
                            throw new DryRunAbort();
                        }
                    }
                    catch (DryRunAbort e)
                    {
                        throw e;
                    }
                    catch (MetadataGuards.BlockedGuardException e)
                    {
                        throw e;
                    }
                    catch (Exception e)
                    {
                        throw new RuntimeException(e.getMessage() != null ? e.getMessage()
                            : e.getClass().getSimpleName(), e);
                    }
                    return null;
                }
            });
            r.ok = true;
            // Direct save to disk for extensions (only when not a dry run)
            if (!dryRun && isExtensionProject(project))
            {
                r.directSave = DcsExtensionExportHelper.exportSchemaToDisk(mm, project, schemaFqn);
            }
        }
        catch (DryRunAbort dra)
        {
            r.ok = true;
            if (r.message == null || r.message.isEmpty())
            {
                r.message = "Dry run completed without applying changes."; //$NON-NLS-1$
            }
        }
        catch (Exception e)
        {
            MetadataGuards.BlockedGuardException blocked = MetadataGuards.BlockedGuardException
                .unwrap(e);
            if (blocked != null)
            {
                MetadataGuards.Verdict v = blocked.verdict;
                r.error = v.error != null ? v.error : "blocked"; //$NON-NLS-1$
                if (v.hint != null && !v.hint.isEmpty())
                {
                    r.error = r.error + " - " + v.hint; //$NON-NLS-1$
                }
                if (v.tag != null)
                {
                    r.tags.put(v.tag.name, v.tag.data);
                }
            }
            else
            {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                r.error = cause.getMessage() != null ? cause.getMessage()
                    : cause.getClass().getSimpleName();
            }
            Activator.logWarning("BmDcsHelper.executeWriteOnSchema failed: " + r.error); //$NON-NLS-1$
        }
        return r;
    }

    /**
     * Resolves the parent metadata object (Report / DataProcessor / etc.)
     * and creates a default DCS schema as one of its templates. Returns
     * the resulting schema FQN.
     */
    public static Result createSchemaOnObject(IProject project, String objectFqn,
        String templateName, boolean dryRun)
    {
        Result r = new Result();
        if (templateName == null || templateName.isEmpty())
        {
            templateName = DEFAULT_TEMPLATE_NAME;
        }
        r.schemaFqn = buildSchemaFqn(objectFqn, templateName);
        if (!isAvailable())
        {
            r.error = "DCS API not available"; //$NON-NLS-1$
            return r;
        }
        IConfigurationProvider cp = Activator.getDefault().getConfigurationProvider();
        Configuration config = cp != null ? cp.getConfiguration(project) : null;
        if (config == null)
        {
            r.error = "Configuration not available"; //$NON-NLS-1$
            return r;
        }
        String[] parts = MetadataTypeUtils.normalizeFqn(objectFqn).split("\\.", 2); //$NON-NLS-1$
        if (parts.length < 2)
        {
            r.error = "objectFqn must be 'Type.Name'"; //$NON-NLS-1$
            return r;
        }
        MdObject owner = MetadataTypeUtils.findObject(config, parts[0], parts[1]);
        if (owner == null)
        {
            r.error = "Object not found: " + objectFqn; //$NON-NLS-1$
            return r;
        }
        if (!(owner instanceof IBmObject))
        {
            r.error = "Owner is not a BM object"; //$NON-NLS-1$
            return r;
        }
        long ownerId = ((IBmObject) owner).bmGetId();

        IBmModelManager mm = Activator.getDefault().getBmModelManager();
        IBmModel model = mm != null ? mm.getModel(project) : null;
        if (model == null)
        {
            r.error = "BM model not available"; //$NON-NLS-1$
            return r;
        }

        final String finalTemplateName = templateName;
        try
        {
            model.execute(new AbstractBmTask<Void>("dcs_workshop.create_schema") //$NON-NLS-1$
            {
                @Override
                public Void execute(IBmTransaction tx, IProgressMonitor pm)
                {
                    try
                    {
                        MdObject obj = (MdObject) tx.getObjectById(ownerId);
                        if (obj == null)
                        {
                            throw new RuntimeException("Owner not found in transaction"); //$NON-NLS-1$
                        }
                        // Templates list lookup
                        EList<MdObject> templates = invokeListGetter(obj, "getTemplates"); //$NON-NLS-1$
                        if (templates == null)
                        {
                            throw new RuntimeException("Object type '" + obj.eClass().getName() //$NON-NLS-1$
                                + "' has no Templates collection"); //$NON-NLS-1$
                        }
                        if (BmObjectHelper.findByName(templates, finalTemplateName) != null)
                        {
                            throw new RuntimeException("Template already exists: " //$NON-NLS-1$
                                + finalTemplateName);
                        }
                        MdObject template = BmObjectHelper.createObject("Template"); //$NON-NLS-1$
                        if (template == null)
                        {
                            throw new RuntimeException("MdClassFactory.createTemplate not available"); //$NON-NLS-1$
                        }
                        template.setName(finalTemplateName);
                        BmObjectHelper.setProperty(template,
                            "templateType", "DataCompositionSchema"); //$NON-NLS-1$ //$NON-NLS-2$
                        // Attach a minimal DCS schema EObject as Template.template
                        Object factory = getFactory();
                        if (factory != null)
                        {
                            try
                            {
                                Method create = factory.getClass()
                                    .getMethod("createDataCompositionSchema"); //$NON-NLS-1$
                                Object schema = create.invoke(factory);
                                BmObjectHelper.setProperty(template, "template", schema); //$NON-NLS-1$
                            }
                            catch (NoSuchMethodException nsme)
                            {
                                // Newer factories may use a different method name; not fatal
                                Activator.logWarning("DcsFactory.createDataCompositionSchema not found"); //$NON-NLS-1$
                            }
                        }
                        templates.add(template);
                        if (dryRun)
                        {
                            throw new DryRunAbort();
                        }
                    }
                    catch (DryRunAbort e)
                    {
                        throw e;
                    }
                    catch (MetadataGuards.BlockedGuardException e)
                    {
                        throw e;
                    }
                    catch (Exception e)
                    {
                        throw new RuntimeException(e.getMessage() != null ? e.getMessage()
                            : e.getClass().getSimpleName(), e);
                    }
                    return null;
                }
            });
            r.ok = true;
            r.message = "Schema created: " + r.schemaFqn; //$NON-NLS-1$
            if (!dryRun && isExtensionProject(project))
            {
                r.directSave = DcsExtensionExportHelper.exportSchemaToDisk(mm, project, r.schemaFqn);
            }
        }
        catch (DryRunAbort dra)
        {
            r.ok = true;
            r.message = "Dry run: schema would be created at " + r.schemaFqn; //$NON-NLS-1$
        }
        catch (Exception e)
        {
            MetadataGuards.BlockedGuardException blocked = MetadataGuards.BlockedGuardException
                .unwrap(e);
            if (blocked != null)
            {
                MetadataGuards.Verdict v = blocked.verdict;
                r.error = v.error != null ? v.error : "blocked"; //$NON-NLS-1$
                if (v.hint != null && !v.hint.isEmpty())
                {
                    r.error = r.error + " - " + v.hint; //$NON-NLS-1$
                }
                if (v.tag != null)
                {
                    r.tags.put(v.tag.name, v.tag.data);
                }
            }
            else
            {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                r.error = cause.getMessage();
            }
        }
        return r;
    }

    /**
     * Builds the BM-canonical schema FQN.
     *
     * @param ownerFqn metadata object FQN (e.g. {@code "Report.Sales"}) or already-full
     *            schema FQN (e.g. {@code "Report.Sales.Template.MainDCS.Template"}).
     */
    public static String buildSchemaFqn(String ownerFqn, String templateName)
    {
        if (ownerFqn == null)
        {
            return null;
        }
        String tn = templateName != null && !templateName.isEmpty() ? templateName
            : DEFAULT_TEMPLATE_NAME;
        if (ownerFqn.endsWith(".Template")) //$NON-NLS-1$
        {
            return ownerFqn;
        }
        if (ownerFqn.contains(".Template.")) //$NON-NLS-1$
        {
            return ownerFqn.endsWith(".Template") ? ownerFqn : ownerFqn + ".Template"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return ownerFqn + ".Template." + tn + ".Template"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    @SuppressWarnings("unchecked")
    static EList<MdObject> invokeListGetter(Object target, String methodName)
    {
        try
        {
            Method m = target.getClass().getMethod(methodName);
            Object v = m.invoke(target);
            if (v instanceof EList)
            {
                return (EList<MdObject>) v;
            }
        }
        catch (Exception ignored)
        {
            // type does not expose this collection
        }
        return null;
    }

    /**
     * Reflection-based generic list getter. Returns the {@link EList} returned
     * by {@code target.<methodName>()} or {@code null} when the method does
     * not exist or returns something else.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static EList<EObject> getEObjectList(Object target, String methodName)
    {
        if (target == null)
        {
            return null;
        }
        try
        {
            Method m = target.getClass().getMethod(methodName);
            Object v = m.invoke(target);
            if (v instanceof EList)
            {
                return (EList) v;
            }
        }
        catch (Exception ignored)
        {
            // missing collection - caller decides
        }
        return null;
    }

    /**
     * Looks up an EObject child by case-insensitive {@code getName()} match in
     * the list returned by {@code container.<getterName>()}.
     */
    public static EObject findByNameInList(Object container, String getterName, String name)
    {
        EList<EObject> list = getEObjectList(container, getterName);
        if (list == null || name == null)
        {
            return null;
        }
        for (EObject child : list)
        {
            try
            {
                Method getName = child.getClass().getMethod("getName"); //$NON-NLS-1$
                Object n = getName.invoke(child);
                if (n != null && name.equalsIgnoreCase(n.toString()))
                {
                    return child;
                }
            }
            catch (Exception ignored)
            {
                // not a Named element - skip
            }
        }
        return null;
    }

    /**
     * Returns a singular factory method name for a DCS schema element class,
     * e.g. {@code "createDataSetQuery"}. Resolves it on the cached
     * {@link #getFactory()} instance through reflection.
     */
    public static Object createElement(String factoryMethodName)
    {
        Object factory = getFactory();
        if (factory == null)
        {
            return null;
        }
        try
        {
            Method m = factory.getClass().getMethod(factoryMethodName);
            return m.invoke(factory);
        }
        catch (NoSuchMethodException nsme)
        {
            Activator.logWarning("DcsFactory." + factoryMethodName + " not found"); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
        catch (Exception e)
        {
            Activator.logWarning("DcsFactory." + factoryMethodName //$NON-NLS-1$
                + " invocation failed: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Calls {@code target.set<PropertyName>(value)} via reflection. Best-effort
     * coercion to common types (boolean, int, enum). Returns {@code null} on
     * success or a short error message describing what went wrong.
     */
    public static String setProperty(Object target, String propertyName, Object value)
    {
        if (target == null || propertyName == null || propertyName.isEmpty())
        {
            return "target and propertyName are required"; //$NON-NLS-1$
        }
        String setter = "set" + Character.toUpperCase(propertyName.charAt(0)) //$NON-NLS-1$
            + propertyName.substring(1);
        for (Method m : target.getClass().getMethods())
        {
            if (!setter.equals(m.getName()) || m.getParameterCount() != 1)
            {
                continue;
            }
            try
            {
                Object converted = coerceValue(value, m.getParameterTypes()[0]);
                m.invoke(target, converted);
                return null;
            }
            catch (Exception e)
            {
                return "Failed to set " + propertyName + ": " + e.getMessage(); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return "Property '" + propertyName + "' not found on " + target.getClass().getSimpleName(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Coerces a String / wrapper value to the EMF setter's expected type.
     */
    private static Object coerceValue(Object value, Class<?> targetType)
    {
        if (value == null || targetType.isInstance(value))
        {
            return value;
        }
        String s = value.toString();
        if (targetType == String.class)
        {
            return s;
        }
        if (targetType == boolean.class || targetType == Boolean.class)
        {
            return Boolean.valueOf(s);
        }
        if (targetType == int.class || targetType == Integer.class)
        {
            return Integer.valueOf(s);
        }
        if (targetType == long.class || targetType == Long.class)
        {
            return Long.valueOf(s);
        }
        if (targetType.isEnum())
        {
            // EMF enums expose two retrieval patterns: `get(String literal)` -
            // returns the enum for the display literal (e.g. "Equal"); and
            // `getByName(String name)` for the Java enum constant name. Both
            // are tried before falling back to a constant scan.
            try
            {
                Method get = targetType.getMethod("get", String.class); //$NON-NLS-1$
                Object val = get.invoke(null, s);
                if (val != null)
                {
                    return val;
                }
            }
            catch (Exception ignored)
            {
                // fall through
            }
            try
            {
                Method getByName = targetType.getMethod("getByName", String.class); //$NON-NLS-1$
                Object val = getByName.invoke(null, s);
                if (val != null)
                {
                    return val;
                }
            }
            catch (Exception ignored)
            {
                // fall through
            }
            // Iterate constants comparing toString() (literal) and name() case-
            // insensitively. Covers EDT EMF enums with non-Java-identifier
            // literals (e.g. "=" for Equal in some versions).
            Object[] constants = targetType.getEnumConstants();
            if (constants != null)
            {
                for (Object c : constants)
                {
                    if (c.toString().equalsIgnoreCase(s)
                        || ((Enum<?>) c).name().equalsIgnoreCase(s))
                    {
                        return c;
                    }
                }
            }
            throw new RuntimeException("Unknown enum value '" + s //$NON-NLS-1$
                + "' for type " + targetType.getSimpleName()); //$NON-NLS-1$
        }
        return value;
    }

    /**
     * Heuristic check: is the project a configuration extension? Reads
     * {@code Configuration.getConfigurationExtensionPurpose()} reflectively;
     * if the method/value is present and non-null, treats the project as an
     * extension.
     */
    public static boolean isExtensionProject(IProject project)
    {
        try
        {
            IConfigurationProvider cp = Activator.getDefault().getConfigurationProvider();
            Configuration config = cp != null ? cp.getConfiguration(project) : null;
            if (config == null)
            {
                return false;
            }
            Method m = config.getClass().getMethod("getConfigurationExtensionPurpose"); //$NON-NLS-1$
            Object value = m.invoke(config);
            return value != null;
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

    /**
     * Sentinel exception to abort a BM transaction cleanly for dryRun=true.
     */
    static final class DryRunAbort extends RuntimeException
    {
        private static final long serialVersionUID = 1L;
    }
}
