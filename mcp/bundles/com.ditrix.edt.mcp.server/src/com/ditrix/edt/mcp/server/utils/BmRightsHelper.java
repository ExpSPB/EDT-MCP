/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Helper for {@code Role} rights operations: setRoleRight on object as a
 * whole or on a child element (attribute / tabular section / column /
 * command / register dimension or resource). <p>
 *
 * Backed by the EDT API in {@code com._1c.g5.v8.dt.rights.model.*}
 * (already in MANIFEST Import-Package). Probe-pattern is used so absence of
 * the package on a particular EDT build returns a graceful
 * {@code rightsApiNotFound} error tag instead of NoClassDefFoundError.
 *
 * <p>Right names are mapped from English/Russian aliases to the canonical
 * platform names. Supported: Read/Чтение, Insert/Добавление,
 * Update/Изменение, Delete/Удаление, View/Просмотр, Edit/Редактирование,
 * InteractiveInsert, InteractiveDelete, ThinClient, WebClient, MobileClient,
 * SaveUserData, Output, Use, ... (full list in upstream docs).
 *
 * <p>1.40.1 mutation strategy:
 * <ol>
 *   <li>Resolve {@code RoleDescription} from the {@code Role} MdObject via
 *       {@code Role.getRights()} (with fallbacks for older EDT builds).</li>
 *   <li>Find or create the {@code ObjectRights} entry for the target FQN.</li>
 *   <li>Find or create the {@code Right} with the canonical name.</li>
 *   <li>Set the granted/denied value via a probed setter
 *       ({@code setAccess(EAccess)}, {@code setGranted(boolean)},
 *       {@code setValue(boolean)} or {@code setEnabled(boolean)}).</li>
 * </ol>
 * Every step has a graceful fallback - when a step cannot be completed, a
 * {@code partialMutation} tag is surfaced.
 */
public final class BmRightsHelper
{
    private static volatile Boolean apiAvailable;
    private static final Object LOCK = new Object();

    /** Right alias map: case-insensitive lookup, value is canonical name. */
    private static final Map<String, String> RIGHT_ALIASES = buildRightAliases();

    /**
     * Result of a setRight mutation.
     */
    public static final class RightResult
    {
        public boolean ok;
        public boolean mutated;
        public boolean idempotentSkip;
        public boolean rightCreated;
        public boolean objectRightsCreated;
        public String setterMethod;
        public String error;
        public Map<String, Object> tags = new LinkedHashMap<>();
    }

    private BmRightsHelper()
    {
        // utility
    }

    /**
     * Returns true when the EDT rights model is reachable via Class.forName.
     * Cached after the first probe.
     */
    public static boolean isAvailable()
    {
        if (apiAvailable != null)
        {
            return apiAvailable;
        }
        synchronized (LOCK)
        {
            if (apiAvailable != null)
            {
                return apiAvailable;
            }
            apiAvailable = probe();
            return apiAvailable;
        }
    }

    private static boolean probe()
    {
        for (String cls : new String[] {
            "com._1c.g5.v8.dt.rights.model.RightsFactory", //$NON-NLS-1$
            "com._1c.g5.v8.dt.rights.model.ObjectRights", //$NON-NLS-1$
            "com._1c.g5.v8.dt.rights.model.RoleDescription" //$NON-NLS-1$
        })
        {
            try
            {
                Class.forName(cls);
            }
            catch (ClassNotFoundException ignored)
            {
                Activator.logInfo("BmRightsHelper.probe: " + cls + " not on classpath"); //$NON-NLS-1$ //$NON-NLS-2$
                return false;
            }
        }
        return true;
    }

    /**
     * Returns a deferred-style explanation when the rights API is not
     * available on the current EDT build.
     */
    public static String deferredMessage(String op)
    {
        return op + " requires the EDT rights model (com._1c.g5.v8.dt.rights.model). "
            + "It is not available on this EDT build. Open the role in the EDT GUI "
            + "and edit rights manually for now.";
    }

    /**
     * Resolves an alias to the canonical right name.
     *
     * @return canonical name, or the input if no alias matches (caller may
     *         use the input verbatim, the platform will reject if invalid)
     */
    public static String canonicalRightName(String alias)
    {
        if (alias == null || alias.isEmpty())
        {
            return alias;
        }
        String key = alias.toLowerCase(Locale.ROOT);
        String mapped = RIGHT_ALIASES.get(key);
        return mapped == null ? alias : mapped;
    }

    /**
     * Builds the standard right-name aliases (case-insensitive).
     */
    private static Map<String, String> buildRightAliases()
    {
        Map<String, String> m = new HashMap<>();
        // English canonical -> identity
        for (String name : new String[] { "Read", "Insert", "Update", "Delete", "View",
            "Edit", "InteractiveInsert", "InteractiveDelete", "InteractiveUpdate",
            "InteractiveDeletionMark", "InteractivePosting", "InteractiveUndoPosting",
            "ThinClient", "WebClient", "MobileClient", "SaveUserData", "Output",
            "Use", "Posting", "UndoPosting", "InputByString", "TotalsControl",
            "RecoverData", "InteractiveOpenExtDataProcessors", "DataAdministration",
            "Administration", "ConfigurationExtensionsAdministration" })
        {
            m.put(name.toLowerCase(Locale.ROOT), name);
        }
        // Russian -> English canonical
        m.put("чтение", "Read");
        m.put("просмотр", "View");
        m.put("добавление", "Insert");
        m.put("изменение", "Update");
        m.put("редактирование", "Edit");
        m.put("удаление", "Delete");
        m.put("интерактивноедобавление", "InteractiveInsert");
        m.put("интерактивноеудаление", "InteractiveDelete");
        m.put("интерактивноеизменение", "InteractiveUpdate");
        m.put("проведение", "Posting");
        m.put("отменапроведения", "UndoPosting");
        m.put("использование", "Use");
        m.put("вывод", "Output");
        m.put("сохранениеданныхпользователя", "SaveUserData");
        m.put("тонкийклиент", "ThinClient");
        m.put("веб-клиент", "WebClient");
        m.put("веб клиент", "WebClient");
        m.put("мобильныйклиент", "MobileClient");
        m.put("администрирование", "Administration");
        m.put("администрированиерасширенийконфигурации", "ConfigurationExtensionsAdministration");
        return m;
    }

    /**
     * Builds a {@code rightsApiNotFound} error tag - graceful fallback when
     * the EDT rights model is missing on this build.
     */
    public static MetadataGuards.BlockedGuardException rightsApiNotFound()
    {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("missingPackage", "com._1c.g5.v8.dt.rights.model"); //$NON-NLS-1$ //$NON-NLS-2$
        data.put("workaround", "Edit role rights via the EDT GUI"); //$NON-NLS-1$ //$NON-NLS-2$
        return new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
            "EDT rights model is not available on this build.",
            "Open the role in EDT and set rights manually. Headless rights operations "
                + "land when this EDT build is updated.",
            new MetadataGuards.ErrorTag("rightsApiNotFound", data))); //$NON-NLS-1$
    }

    /**
     * Resolves the {@code RoleDescription} EObject from a Role MdObject.
     * Tries multiple getter names because the field has been renamed across
     * EDT builds. Returns {@code null} when none of the getters resolve.
     */
    public static Object resolveRoleDescription(MdObject role)
    {
        if (role == null)
        {
            return null;
        }
        for (String getter : new String[] { "getRights", "getRoleDescription", "getDescription" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            Object res = invokeNoArg(role, getter);
            if (res != null)
            {
                return res;
            }
        }
        return null;
    }

    /**
     * Top-level entry point: applies a single right to a target inside a Role.
     *
     * @param role        the Role MdObject (must come from
     *                    {@code tx.getObjectById})
     * @param config      project configuration (for resolving the target
     *                    MdObject reference on freshly-created ObjectRights)
     * @param targetFqn   full FQN of the target object
     *                    (e.g. {@code "Catalog.Products"})
     * @param canonicalRightName canonical right name
     *                    (e.g. {@code "Read"} - already resolved by
     *                    {@link #canonicalRightName(String)})
     * @param granted     {@code true} for granted, {@code false} for denied
     */
    public static RightResult setRight(MdObject role, Configuration config,
        String targetFqn, String canonicalRightName, boolean granted)
    {
        RightResult r = new RightResult();
        if (role == null || targetFqn == null || canonicalRightName == null)
        {
            r.error = "role, targetFqn and rightName are required";
            return r;
        }
        if (!isAvailable())
        {
            r.error = "EDT rights model is not available";
            r.tags.put("rightsApiNotFound", true); //$NON-NLS-1$
            return r;
        }
        Object roleDescription = resolveRoleDescription(role);
        if (roleDescription == null)
        {
            r.error = "Role does not expose a RoleDescription via getRights()/getRoleDescription()";
            r.tags.put("partialMutation", "RoleDescription not reachable"); //$NON-NLS-1$ //$NON-NLS-2$
            return r;
        }

        // 1. Find or create the ObjectRights entry for the target FQN
        Object objectRights = findObjectRights(roleDescription, targetFqn);
        if (objectRights == null)
        {
            objectRights = createObjectRights(roleDescription, config, targetFqn);
            if (objectRights == null)
            {
                r.error = "Could not create ObjectRights for " + targetFqn;
                r.tags.put("partialMutation", "ObjectRights factory unavailable"); //$NON-NLS-1$ //$NON-NLS-2$
                return r;
            }
            r.objectRightsCreated = true;
        }

        // 2. Find or create the Right entry inside ObjectRights
        Object right = findRight(objectRights, canonicalRightName);
        if (right == null)
        {
            right = createRight(objectRights, canonicalRightName);
            if (right == null)
            {
                r.error = "Could not create Right '" + canonicalRightName + "'";
                r.tags.put("partialMutation", "Right factory unavailable"); //$NON-NLS-1$ //$NON-NLS-2$
                return r;
            }
            r.rightCreated = true;
        }

        // 3. Idempotency: probe current value, compare to requested
        Boolean current = readRightValue(right);
        if (current != null && current.booleanValue() == granted)
        {
            r.ok = true;
            r.idempotentSkip = true;
            return r;
        }

        // 4. Apply via probed setter
        SetterProbeResult sp = applyRightValue(right, granted);
        if (!sp.applied)
        {
            r.error = "Could not find a writable setter on Right (tried setAccess/setGranted/setValue/setEnabled)";
            r.tags.put("partialMutation", "Right setter not found"); //$NON-NLS-1$ //$NON-NLS-2$
            return r;
        }

        r.setterMethod = sp.method;
        r.ok = true;
        r.mutated = true;
        return r;
    }

    /**
     * Returns the existing ObjectRights for the given target FQN, or
     * {@code null} when not present yet.
     */
    @SuppressWarnings("unchecked")
    private static Object findObjectRights(Object roleDescription, String targetFqn)
    {
        Object listObj = invokeNoArg(roleDescription, "getObjects"); //$NON-NLS-1$
        if (!(listObj instanceof EList))
        {
            return null;
        }
        for (Object entry : (EList<Object>) listObj)
        {
            String entryFqn = readObjectRightsTargetFqn(entry);
            if (entryFqn != null && entryFqn.equals(targetFqn))
            {
                return entry;
            }
        }
        return null;
    }

    /**
     * Reads the target FQN from an ObjectRights entry. Tries:
     * <ol>
     *   <li>{@code getObject().bmGetFqn()} - top-level reference path</li>
     *   <li>{@code getName()} - older builds</li>
     * </ol>
     */
    private static String readObjectRightsTargetFqn(Object objectRights)
    {
        if (objectRights == null)
        {
            return null;
        }
        Object obj = invokeNoArg(objectRights, "getObject"); //$NON-NLS-1$
        if (obj != null)
        {
            try
            {
                Method m = obj.getClass().getMethod("bmGetFqn"); //$NON-NLS-1$
                Object fqn = m.invoke(obj);
                if (fqn instanceof String && !((String) fqn).isEmpty())
                {
                    return (String) fqn;
                }
            }
            catch (NoSuchMethodException ignored)
            {
                // try fallback below
            }
            catch (Exception e)
            {
                Activator.logWarning("readObjectRightsTargetFqn bmGetFqn failed: " //$NON-NLS-1$
                    + e.getMessage());
            }
        }
        Object name = invokeNoArg(objectRights, "getName"); //$NON-NLS-1$
        return name instanceof String ? (String) name : null;
    }

    /**
     * Creates a new {@code ObjectRights} via {@code RightsFactory.eINSTANCE}
     * and adds it to {@code roleDescription.objects}. Sets both the
     * {@code object} reference (preferred) and the {@code name} string
     * (legacy fallback) so subsequent {@link #findObjectRights} calls can
     * locate this entry.
     */
    @SuppressWarnings("unchecked")
    private static Object createObjectRights(Object roleDescription, Configuration config,
        String targetFqn)
    {
        Object factory = rightsFactory();
        if (factory == null)
        {
            return null;
        }
        Object newEntry = invokeNoArg(factory, "createObjectRights"); //$NON-NLS-1$
        if (newEntry == null)
        {
            return null;
        }
        if (newEntry instanceof EObject)
        {
            EObject entry = (EObject) newEntry;
            // Preferred: setObject(MdObject) - links the entry to the
            // platform reference. Without this, findObjectRights cannot
            // locate the entry on subsequent calls (idempotency would break).
            MdObject target = resolveTarget(config, targetFqn);
            if (target != null)
            {
                org.eclipse.emf.ecore.EStructuralFeature objectFeature
                    = entry.eClass().getEStructuralFeature("object"); //$NON-NLS-1$
                if (objectFeature != null)
                {
                    try
                    {
                        entry.eSet(objectFeature, target);
                    }
                    catch (Exception e)
                    {
                        Activator.logWarning("ObjectRights.setObject failed: " //$NON-NLS-1$
                            + e.getMessage());
                    }
                }
            }
            // Fallback: name string for legacy builds where setObject is missing.
            org.eclipse.emf.ecore.EStructuralFeature nameFeature
                = entry.eClass().getEStructuralFeature("name"); //$NON-NLS-1$
            if (nameFeature != null)
            {
                try
                {
                    entry.eSet(nameFeature, targetFqn);
                }
                catch (Exception ignored)
                {
                    // best effort
                }
            }
        }
        // Add to roleDescription.objects
        Object listObj = invokeNoArg(roleDescription, "getObjects"); //$NON-NLS-1$
        if (listObj instanceof EList)
        {
            ((EList<Object>) listObj).add(newEntry);
        }
        return newEntry;
    }

    /**
     * Resolves a target FQN ({@code Catalog.Products}) into the actual
     * {@code MdObject} from the configuration. Returns {@code null} when the
     * configuration is missing or the FQN does not reference a known
     * top-level metadata object.
     */
    private static MdObject resolveTarget(Configuration config, String targetFqn)
    {
        if (config == null || targetFqn == null || targetFqn.isEmpty())
        {
            return null;
        }
        String[] parts = targetFqn.split("\\.", 2); //$NON-NLS-1$
        if (parts.length < 2 || parts[1].isEmpty())
        {
            return null;
        }
        try
        {
            return MetadataTypeUtils.findObject(config, parts[0], parts[1]);
        }
        catch (Exception e)
        {
            Activator.logWarning("resolveTarget failed for " + targetFqn //$NON-NLS-1$
                + ": " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Returns the existing Right with the given canonical name, or
     * {@code null} when not present.
     */
    @SuppressWarnings("unchecked")
    private static Object findRight(Object objectRights, String canonicalName)
    {
        Object listObj = invokeNoArg(objectRights, "getRights"); //$NON-NLS-1$
        if (!(listObj instanceof EList))
        {
            return null;
        }
        for (Object entry : (EList<Object>) listObj)
        {
            Object name = invokeNoArg(entry, "getName"); //$NON-NLS-1$
            if (canonicalName.equals(name))
            {
                return entry;
            }
        }
        return null;
    }

    /**
     * Creates a new {@code Right} with the given name and adds it to
     * {@code objectRights.rights}.
     */
    @SuppressWarnings("unchecked")
    private static Object createRight(Object objectRights, String canonicalName)
    {
        Object factory = rightsFactory();
        if (factory == null)
        {
            return null;
        }
        Object newRight = invokeNoArg(factory, "createRight"); //$NON-NLS-1$
        if (newRight == null)
        {
            return null;
        }
        if (newRight instanceof EObject)
        {
            org.eclipse.emf.ecore.EStructuralFeature nameFeature
                = ((EObject) newRight).eClass().getEStructuralFeature("name"); //$NON-NLS-1$
            if (nameFeature != null)
            {
                try
                {
                    ((EObject) newRight).eSet(nameFeature, canonicalName);
                }
                catch (Exception ignored)
                {
                    // best effort
                }
            }
        }
        Object listObj = invokeNoArg(objectRights, "getRights"); //$NON-NLS-1$
        if (listObj instanceof EList)
        {
            ((EList<Object>) listObj).add(newRight);
        }
        return newRight;
    }

    /**
     * Reads the boolean value from a Right object. Tries:
     * <ol>
     *   <li>{@code getAccess()} -&gt; EAccess enum (GRANTED == true)</li>
     *   <li>{@code getValue()} -&gt; Boolean</li>
     *   <li>{@code isGranted()} / {@code isEnabled()} -&gt; boolean</li>
     * </ol>
     * Returns {@code null} when no getter resolves.
     */
    private static Boolean readRightValue(Object right)
    {
        Object access = invokeNoArg(right, "getAccess"); //$NON-NLS-1$
        if (access != null)
        {
            // EAccess enum: GRANTED / DENIED / NOT_DEFINED
            String enumName = access.toString();
            if ("GRANTED".equalsIgnoreCase(enumName)) //$NON-NLS-1$
            {
                return Boolean.TRUE;
            }
            if ("DENIED".equalsIgnoreCase(enumName)) //$NON-NLS-1$
            {
                return Boolean.FALSE;
            }
            return null;
        }
        for (String getter : new String[] { "getValue", "isGranted", "isEnabled" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            Object v = invokeNoArg(right, getter);
            if (v instanceof Boolean)
            {
                return (Boolean) v;
            }
        }
        return null;
    }

    /**
     * Result of the setter probe for a Right value.
     */
    private static final class SetterProbeResult
    {
        boolean applied;
        String method;
    }

    /**
     * Applies the granted/denied value via a probed setter. Tries setters in
     * the order:
     * <ol>
     *   <li>{@code setAccess(EAccess)} - the EDT-canonical form</li>
     *   <li>{@code setGranted(boolean)} - older builds</li>
     *   <li>{@code setValue(boolean)} - generic</li>
     *   <li>{@code setEnabled(boolean)} - last resort</li>
     * </ol>
     */
    private static SetterProbeResult applyRightValue(Object right, boolean granted)
    {
        SetterProbeResult res = new SetterProbeResult();
        if (right == null)
        {
            return res;
        }

        // 1. setAccess(EAccess)
        Class<?> eAccessClass = forNameOrNull("com._1c.g5.v8.dt.rights.model.EAccess"); //$NON-NLS-1$
        if (eAccessClass == null)
        {
            eAccessClass = forNameOrNull("com._1c.g5.v8.dt.rights.model.RightAccess"); //$NON-NLS-1$
        }
        if (eAccessClass != null)
        {
            try
            {
                Method m = right.getClass().getMethod("setAccess", eAccessClass); //$NON-NLS-1$
                Object value = resolveAccessEnumValue(eAccessClass, granted);
                if (value != null)
                {
                    m.invoke(right, value);
                    res.applied = true;
                    res.method = "setAccess"; //$NON-NLS-1$
                    return res;
                }
                else
                {
                    // Method exists but no enum constant matched - log explicitly
                    // so the user understands why the tool fell through to the
                    // boolean setters (unusual but not silent now).
                    Activator.logWarning("setAccess: no matching enum constant on " //$NON-NLS-1$
                        + eAccessClass.getName() + " for granted=" + granted //$NON-NLS-1$
                        + " - tried GRANTED/ALLOWED/GRANT/ALLOW/ENABLED/TRUE/ON/YES " //$NON-NLS-1$
                        + "and DENIED/PROHIBITED/DENY/PROHIBIT/DISABLED/FALSE/OFF/NO/NOT_DEFINED");
                }
            }
            catch (NoSuchMethodException ignored)
            {
                // try next
            }
            catch (Exception e)
            {
                Activator.logWarning("setAccess failed: " + e.getMessage()); //$NON-NLS-1$
            }
        }

        // 2. Boolean setters
        for (String setter : new String[] { "setGranted", "setValue", "setEnabled" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            for (Class<?> paramType : new Class<?>[] { boolean.class, Boolean.class })
            {
                try
                {
                    Method m = right.getClass().getMethod(setter, paramType);
                    m.invoke(right, granted);
                    res.applied = true;
                    res.method = setter;
                    return res;
                }
                catch (NoSuchMethodException ignored)
                {
                    // try next
                }
                catch (Exception e)
                {
                    Activator.logWarning(setter + " failed: " + e.getMessage()); //$NON-NLS-1$
                }
            }
        }
        return res;
    }

    /**
     * Looks up the {@code RightsFactory.eINSTANCE} singleton via reflection.
     * Returns {@code null} when the rights model package is missing.
     */
    private static Object rightsFactory()
    {
        try
        {
            Class<?> clazz = Class.forName("com._1c.g5.v8.dt.rights.model.RightsFactory"); //$NON-NLS-1$
            Field eInstance = clazz.getField("eINSTANCE"); //$NON-NLS-1$
            return eInstance.get(null);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static Object enumValue(Class<?> enumClass, String name)
    {
        if (enumClass == null || name == null)
        {
            return null;
        }
        try
        {
            Field f = enumClass.getField(name);
            return f.get(null);
        }
        catch (NoSuchFieldException ignored)
        {
            return null;
        }
        catch (Exception e)
        {
            Activator.logWarning("enumValue " + enumClass.getName() + "." //$NON-NLS-1$ //$NON-NLS-2$
                + name + " failed: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * 1.40.x: extended enum-name probe for the {@code setAccess(EAccess)}
     * setter. Tries the typical EDT names first, then a wider catalog
     * (GRANT/ALLOW/ENABLED/TRUE/ON/YES vs DENY/PROHIBIT/DISABLED/FALSE/OFF/NO/NOT_DEFINED)
     * to cover non-standard custom builds.
     */
    private static Object resolveAccessEnumValue(Class<?> enumClass, boolean granted)
    {
        if (enumClass == null)
        {
            return null;
        }
        String[] candidates = granted
            ? new String[] { "GRANTED", "ALLOWED", "GRANT", "ALLOW", "ENABLED", "TRUE", "ON", "YES" }
            : new String[] { "DENIED", "PROHIBITED", "DENY", "PROHIBIT", "DISABLED", "FALSE", "OFF", "NO", "NOT_DEFINED" };
        for (String name : candidates)
        {
            Object v = enumValue(enumClass, name);
            if (v != null)
            {
                return v;
            }
        }
        return null;
    }

    private static Object invokeNoArg(Object target, String methodName)
    {
        if (target == null || methodName == null)
        {
            return null;
        }
        try
        {
            Method m = target.getClass().getMethod(methodName);
            return m.invoke(target);
        }
        catch (NoSuchMethodException ignored)
        {
            return null;
        }
        catch (Exception e)
        {
            Activator.logWarning("invokeNoArg " + methodName + " failed: " //$NON-NLS-1$ //$NON-NLS-2$
                + e.getMessage());
            return null;
        }
    }

    private static Class<?> forNameOrNull(String className)
    {
        try
        {
            return Class.forName(className);
        }
        catch (ClassNotFoundException e)
        {
            return null;
        }
    }

    /**
     * Resolves the rights container for a given role and target object FQN.
     * <p>
     * Legacy entry kept for backward compatibility; the canonical path now
     * uses {@link #setRight(MdObject, Configuration, String, String, boolean)}.
     */
    public static Object resolveObjectRights(Object roleDescription, String targetFqn)
    {
        if (!isAvailable())
        {
            return null;
        }
        if (roleDescription == null || targetFqn == null)
        {
            return null;
        }
        try
        {
            Method m = roleDescription.getClass().getMethod("getObjects"); //$NON-NLS-1$
            Object list = m.invoke(roleDescription);
            return list; // caller iterates
        }
        catch (Exception e)
        {
            Activator.logWarning("resolveObjectRights failed: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }
}
