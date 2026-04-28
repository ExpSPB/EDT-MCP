/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.common.util.EList;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Helper for BM-transaction operations on metadata objects (Catalog, Document,
 * Register, etc.). Used by {@code EditMetadataTool} as a backing for the
 * operations in the "Objects" group.
 * <p>
 * Most operations are implemented via reflection on the EMF-generated
 * {@link MdObject} hierarchy: each metadata type has slightly different
 * containment lists (e.g. {@code getAttributes()} vs {@code getDimensions()}),
 * so we look the method up by name and invoke it. Failures are surfaced as
 * informative error strings instead of stack traces.
 * <p>
 * <b>State:</b> Phase 3 skeleton. Initial revision exposes the most-needed
 * operations (createObject, setObjectProperty, addObjectAttribute,
 * removeObjectAttribute, addTabularSection, removeTabularSection,
 * addTabularSectionAttribute, removeTabularSectionAttribute). Per-type
 * specifics (Catalog vs ChartOfAccounts vs Register) are added incrementally.
 */
public final class BmObjectHelper
{
    private BmObjectHelper()
    {
        // utility class
    }

    /**
     * Result of a metadata-mutation operation.
     * <p>
     * {@link #tags} carries machine-readable structured fields (e.g.
     * {@code supportLock}, {@code standardAttributeConflict},
     * {@code alreadyExists}, {@code notFound}) surfaced into the JSON
     * response so AI agents can branch on them without parsing the
     * {@link #error} text.
     */
    public static final class Result
    {
        public boolean ok;
        public String error;
        public String fqn;
        public String message;
        public Map<String, Object> tags = new LinkedHashMap<>();
    }

    /**
     * Functional callback executed inside a BM read-write transaction with the
     * resolved owner already in hand.
     */
    @FunctionalInterface
    public interface MdObjectAction
    {
        Object execute(IBmTransaction tx, MdObject owner) throws Exception;
    }

    /**
     * Optional pre-flight guard executed inside the BM read-write transaction
     * after {@link MetadataGuards#checkSupplierLock} but before the
     * {@link MdObjectAction}. Use it to add context-specific guards that need
     * the candidate name / kind known to the caller (e.g.
     * {@link MetadataGuards#checkStandardAttributeConflict}).
     * <p>
     * Throw {@link MetadataGuards.BlockedGuardException} to abort with a
     * structured {@code Verdict} that propagates into {@link Result#tags}.
     */
    @FunctionalInterface
    public interface PreExecuteCheck
    {
        void validate(MdObject owner) throws Exception;
    }

    /**
     * Executes the given action inside a BM read-write transaction against the
     * resolved owner FQN. {@code dryRun=true} runs the action then rolls back
     * by throwing a sentinel exception that {@link IBmModel#execute} treats as
     * normal abort - changes never reach the model.
     * <p>
     * This overload preserves the legacy contract (no centralized guards). It
     * delegates to the {@link #executeWriteOnObject(IProject, String, boolean,
     * MdObjectAction, PreExecuteCheck)} variant with a {@code null} preCheck,
     * but the supplier-lock guard always runs.
     */
    public static Result executeWriteOnObject(IProject project, String ownerFqn, boolean dryRun,
        MdObjectAction action)
    {
        return executeWriteOnObject(project, ownerFqn, dryRun, action, null);
    }

    /**
     * Executes the given action inside a BM read-write transaction with two
     * automatic guards:
     * <ol>
     *   <li>{@link MetadataGuards#checkSupplierLock} - always.</li>
     *   <li>{@code preCheck.validate(owner)} - optional, caller-provided.</li>
     * </ol>
     * Both guards may throw {@link MetadataGuards.BlockedGuardException} with
     * a structured {@link MetadataGuards.Verdict}. The verdict's
     * {@link MetadataGuards.ErrorTag} is captured into {@link Result#tags} so
     * the response carries a machine-readable field next to the {@code error}
     * string.
     */
    public static Result executeWriteOnObject(IProject project, String ownerFqn, boolean dryRun,
        MdObjectAction action, PreExecuteCheck preCheck)
    {
        Result r = new Result();
        r.fqn = ownerFqn;
        if (project == null || ownerFqn == null || ownerFqn.isEmpty())
        {
            r.error = "project and ownerFqn are required"; //$NON-NLS-1$
            return r;
        }

        IConfigurationProvider configProvider = Activator.getDefault().getConfigurationProvider();
        if (configProvider == null)
        {
            r.error = "Configuration provider not available"; //$NON-NLS-1$
            return r;
        }
        Configuration config = configProvider.getConfiguration(project);
        if (config == null)
        {
            r.error = "Could not get configuration for project: " + project.getName(); //$NON-NLS-1$
            return r;
        }

        String normalized = MetadataTypeUtils.normalizeFqn(ownerFqn);
        String[] parts = normalized.split("\\.", 2); //$NON-NLS-1$
        if (parts.length < 2)
        {
            r.error = "ownerFqn must be 'Type.Name'"; //$NON-NLS-1$
            return r;
        }
        MdObject owner = MetadataTypeUtils.findObject(config, parts[0], parts[1]);
        if (owner == null)
        {
            r.error = "Owner not found: " + normalized; //$NON-NLS-1$
            Map<String, Object> notFoundData = new LinkedHashMap<>();
            notFoundData.put("ownerFqn", normalized); //$NON-NLS-1$
            r.tags.put("notFound", notFoundData); //$NON-NLS-1$
            return r;
        }
        if (!(owner instanceof IBmObject))
        {
            r.error = "Owner is not a BM object: " + normalized; //$NON-NLS-1$
            return r;
        }

        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        if (bmModelManager == null)
        {
            r.error = "IBmModelManager not available"; //$NON-NLS-1$
            return r;
        }
        IBmModel bmModel = bmModelManager.getModel(project);
        if (bmModel == null)
        {
            r.error = "BM model not available for project: " + project.getName(); //$NON-NLS-1$
            return r;
        }

        long bmId = ((IBmObject) owner).bmGetId();
        try
        {
            bmModel.execute(new AbstractBmTask<Void>("BmObjectHelper.write") //$NON-NLS-1$
            {
                @Override
                public Void execute(IBmTransaction tx, IProgressMonitor pm)
                {
                    try
                    {
                        MdObject txOwner = (MdObject) tx.getObjectById(bmId);
                        if (txOwner == null)
                        {
                            throw new RuntimeException("Owner not found in transaction"); //$NON-NLS-1$
                        }

                        // GUARD 1: supplier lock - always
                        MetadataGuards.Verdict lock = MetadataGuards.checkSupplierLock(txOwner);
                        if (lock.blocked)
                        {
                            throw new MetadataGuards.BlockedGuardException(lock);
                        }

                        // GUARD 2: caller-provided preCheck (optional)
                        if (preCheck != null)
                        {
                            preCheck.validate(txOwner);
                        }

                        Object actionResult = action.execute(tx, txOwner);
                        if (actionResult != null)
                        {
                            r.message = actionResult.toString();
                        }
                        if (dryRun)
                        {
                            // Throw to abort the transaction - the model preserves no state.
                            throw new DryRunAbort("dryRun"); //$NON-NLS-1$
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
        }
        catch (DryRunAbort dra)
        {
            // Expected for dryRun
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
            Activator.logWarning("BmObjectHelper write failed for " + normalized + ": " + r.error); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return r;
    }

    /**
     * Marker exception used to abort a transaction cleanly when {@code dryRun=true}.
     */
    private static final class DryRunAbort extends RuntimeException
    {
        private static final long serialVersionUID = 1L;

        DryRunAbort(String msg)
        {
            super(msg);
        }
    }

    // -----------------------------------------------------------------------
    // Operation shortcuts
    // -----------------------------------------------------------------------

    /**
     * Creates a new top-level metadata object via {@link MdClassFactory}.
     * Caller must add the result to the relevant Configuration collection
     * inside a BM read-write transaction.
     *
     * @param englishType English singular type name, e.g. {@code "Catalog"},
     *            {@code "Document"}, {@code "InformationRegister"}.
     * @return new MdObject or {@code null} when the factory does not expose a
     *         {@code createXxx()} method for that type (e.g. read-only system
     *         types).
     */
    public static MdObject createObject(String englishType)
    {
        if (englishType == null || englishType.isEmpty())
        {
            return null;
        }
        String factoryMethodName = "create" + englishType; //$NON-NLS-1$
        try
        {
            Method m = MdClassFactory.class.getMethod(factoryMethodName);
            Object result = m.invoke(MdClassFactory.eINSTANCE);
            if (result instanceof MdObject)
            {
                MdObject created = (MdObject) result;
                created.setUuid(UUID.randomUUID());
                return created;
            }
        }
        catch (NoSuchMethodException nsme)
        {
            return null;
        }
        catch (Exception e)
        {
            Activator.logWarning("MdClassFactory." + factoryMethodName //$NON-NLS-1$
                + " failed: " + e.getMessage()); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Adds the given object to the configuration's collection for its type
     * (e.g. {@code config.getCatalogs()}).
     *
     * @return {@code true} when added.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static boolean addToConfiguration(Configuration config, MdObject obj)
    {
        if (config == null || obj == null)
        {
            return false;
        }
        String typeName = obj.eClass().getName();
        String collection = MetadataTypeUtils.getConfigReferenceName(typeName);
        if (collection == null)
        {
            return false;
        }
        // collection is e.g. "catalogs" - the EMF getter is "getCatalogs"
        String getter = "get" + Character.toUpperCase(collection.charAt(0)) //$NON-NLS-1$
            + collection.substring(1);
        try
        {
            Method m = config.getClass().getMethod(getter);
            Object list = m.invoke(config);
            if (list instanceof EList)
            {
                ((EList) list).add(obj);
                return true;
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("addToConfiguration failed for " + typeName //$NON-NLS-1$
                + ": " + e.getMessage()); //$NON-NLS-1$
        }
        return false;
    }

    /**
     * Sets a generic EMF-feature-backed property on an object via
     * {@code setXxx(...)} reflection. Returns {@code null} on success or an
     * error message.
     */
    public static String setProperty(MdObject obj, String propertyName, Object value)
    {
        if (obj == null || propertyName == null || propertyName.isEmpty())
        {
            return "owner and propertyName are required"; //$NON-NLS-1$
        }
        String setter = "set" + Character.toUpperCase(propertyName.charAt(0)) //$NON-NLS-1$
            + propertyName.substring(1);
        for (Method m : obj.getClass().getMethods())
        {
            if (!setter.equals(m.getName()) || m.getParameterCount() != 1)
            {
                continue;
            }
            try
            {
                Object converted = coerceValue(value, m.getParameterTypes()[0]);
                m.invoke(obj, converted);
                return null;
            }
            catch (Exception e)
            {
                return "Failed to set " + propertyName + ": " + e.getMessage(); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return "Property '" + propertyName + "' not found on " + obj.eClass().getName(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Best-effort coercion from a String parameter value to the EMF setter's
     * expected primitive / boxed type. Other types are passed through.
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
            @SuppressWarnings({ "rawtypes", "unchecked" })
            Object e = Enum.valueOf((Class<Enum>) targetType, s);
            return e;
        }
        return value;
    }

    /**
     * Returns the {@code getAttributes()} list reflectively, or {@code null}
     * when the type does not carry one.
     */
    @SuppressWarnings("unchecked")
    public static EList<MdObject> getAttributes(MdObject owner)
    {
        try
        {
            Method m = owner.getClass().getMethod("getAttributes"); //$NON-NLS-1$
            Object result = m.invoke(owner);
            if (result instanceof EList)
            {
                return (EList<MdObject>) result;
            }
        }
        catch (Exception ignored)
        {
            // type does not expose getAttributes()
        }
        return null;
    }

    /**
     * Returns the {@code getTabularSections()} list reflectively, or {@code null}
     * when the type does not carry one.
     */
    @SuppressWarnings("unchecked")
    public static EList<MdObject> getTabularSections(MdObject owner)
    {
        try
        {
            Method m = owner.getClass().getMethod("getTabularSections"); //$NON-NLS-1$
            Object result = m.invoke(owner);
            if (result instanceof EList)
            {
                return (EList<MdObject>) result;
            }
        }
        catch (Exception ignored)
        {
            // type does not expose getTabularSections()
        }
        return null;
    }

    /**
     * Looks up a child by case-insensitive name in the given list.
     */
    public static MdObject findByName(EList<? extends MdObject> list, String name)
    {
        if (list == null || name == null)
        {
            return null;
        }
        for (MdObject child : list)
        {
            if (name.equalsIgnoreCase(child.getName()))
            {
                return child;
            }
        }
        return null;
    }

    /**
     * Builds a {@link MetadataGuards.BlockedGuardException} carrying an
     * {@code alreadyExists} tag. Use this in mutation lambdas instead of a
     * plain {@code RuntimeException} so the response surfaces a structured
     * field next to the human-readable error.
     */
    public static MetadataGuards.BlockedGuardException alreadyExists(String childName,
        String ownerFqn, String kind)
    {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", childName); //$NON-NLS-1$
        data.put("ownerFqn", ownerFqn); //$NON-NLS-1$
        data.put("kind", kind); //$NON-NLS-1$
        return new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
            kind + " already exists: " + childName, //$NON-NLS-1$
            "Use the matching remove operation first, or pick a different name.", //$NON-NLS-1$
            new MetadataGuards.ErrorTag("alreadyExists", data))); //$NON-NLS-1$
    }

    /**
     * Builds a {@link MetadataGuards.BlockedGuardException} carrying a
     * {@code notFound} tag.
     */
    public static MetadataGuards.BlockedGuardException notFound(String childName, String ownerFqn,
        String kind)
    {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", childName); //$NON-NLS-1$
        data.put("ownerFqn", ownerFqn); //$NON-NLS-1$
        data.put("kind", kind); //$NON-NLS-1$
        return new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
            kind + " not found: " + childName, //$NON-NLS-1$
            "Verify the name and try again.", //$NON-NLS-1$
            new MetadataGuards.ErrorTag("notFound", data))); //$NON-NLS-1$
    }
}
