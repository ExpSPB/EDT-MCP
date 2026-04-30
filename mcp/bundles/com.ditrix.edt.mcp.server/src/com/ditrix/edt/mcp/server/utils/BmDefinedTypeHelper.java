/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Helper for {@code DefinedType} (ОпределяемыйТип) operations: setting the
 * type composition (which metadata refs / primitive types are part of the
 * defined type) in one call. <p>
 *
 * <p>{@code setDefinedTypeTypes} accepts an array of FQNs like
 * {@code ["CatalogRef.Users", "CatalogRef.ExternalUsers"]} and rebuilds the
 * type composition atomically. After the call, the defined type can be
 * referenced from attribute types via {@code "DefinedType.X"}.
 *
 * <p>Implementation strategy (1.40.1):
 * <ol>
 *   <li>Validate every FQN against the known type shapes
 *       ({@code CatalogRef.X}, {@code DocumentRef.Y}, primitives).</li>
 *   <li>Build a new {@code TypeItem} for each FQN: prefer a fresh instance
 *       from the platform factory (probe), fall back to {@code EcoreUtil.copy}
 *       of an existing produced type pulled from the configuration.</li>
 *   <li>Idempotency: when the requested set already matches the current
 *       composition (compared by {@code McoreUtil.getTypeName}), return
 *       {@code idempotentSkip} and do not mutate.</li>
 *   <li>Otherwise clear the {@code TypeDescription.types} list and add the
 *       new TypeItems. The BM transaction commit handles persistence.</li>
 * </ol>
 *
 * <p>If a particular FQN cannot be resolved into a TypeItem (no produced type
 * is available - typical for primitives on older EDT builds), the result
 * carries a {@code partialMutation} tag and {@code unresolved} list so the
 * caller can surface the warning while the resolved subset is still applied.
 */
public final class BmDefinedTypeHelper
{
    private static final String[] FACTORY_CANDIDATES = {
        "com._1c.g5.v8.dt.mcore.McoreFactory", //$NON-NLS-1$
        "com._1c.g5.v8.dt.mcore.MCoreFactory" //$NON-NLS-1$
    };

    /** Cached probes (avoid Class.forName in hot loops). */
    private static volatile Class<?> cachedTypeItemClass;
    private static volatile Class<?> cachedMcoreUtilClass;
    private static volatile boolean classProbeDone;

    private BmDefinedTypeHelper()
    {
        // utility
    }

    private static void ensureClassProbeDone()
    {
        if (classProbeDone)
        {
            return;
        }
        synchronized (BmDefinedTypeHelper.class)
        {
            if (classProbeDone)
            {
                return;
            }
            cachedTypeItemClass = forNameOrNull("com._1c.g5.v8.dt.mcore.TypeItem"); //$NON-NLS-1$
            cachedMcoreUtilClass = forNameOrNull("com._1c.g5.v8.dt.mcore.util.McoreUtil"); //$NON-NLS-1$
            classProbeDone = true;
        }
    }

    /**
     * Result of a setTypes operation.
     */
    public static final class TypesResult
    {
        public boolean ok;
        public boolean mutated;
        public boolean idempotentSkip;
        public final List<String> resolved = new ArrayList<>();
        public final List<String> unresolved = new ArrayList<>();
        public String error;
    }

    /**
     * Replaces the type composition of a DefinedType with the given FQN list.
     * Idempotent: when the requested set already matches the current set
     * (regardless of order), no mutation happens.
     *
     * @param definedType the DefinedType MdObject (must be the one returned
     *                    by tx.getObjectById)
     * @param config      project configuration (for FQN resolution)
     * @param typeFqns    list of FQNs:
     *                    {@code "CatalogRef.X"} / {@code "DocumentRef.Y"} /
     *                    primitives like {@code "String"} / {@code "Number"}
     * @return result with resolved/unresolved lists
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static TypesResult setTypes(MdObject definedType, Configuration config,
        List<String> typeFqns)
    {
        TypesResult r = new TypesResult();
        if (definedType == null || typeFqns == null)
        {
            r.error = "definedType and typeFqns are required";
            return r;
        }
        Object typeDesc = readTypeDescription(definedType);
        if (typeDesc == null)
        {
            r.error = "DefinedType has no type description (EDT version mismatch?)";
            return r;
        }
        EList<?> typesList = readTypesList(typeDesc);
        if (typesList == null)
        {
            r.error = "TypeDescription does not expose getTypes()";
            return r;
        }

        // 1. Validate FQNs
        List<String> requested = new ArrayList<>();
        for (String fqn : typeFqns)
        {
            if (fqn == null || fqn.isEmpty())
            {
                continue;
            }
            if (isKnownTypeShape(fqn, config))
            {
                requested.add(fqn);
            }
            else
            {
                r.unresolved.add(fqn);
            }
        }
        if (!r.unresolved.isEmpty())
        {
            r.error = "Some types could not be resolved: " + String.join(", ", r.unresolved);
            return r;
        }

        // 2. Idempotency check
        Set<String> currentSet = readCurrentTypeNames(typesList);
        Set<String> requestedSet = new HashSet<>(requested);
        if (currentSet.equals(requestedSet))
        {
            r.ok = true;
            r.idempotentSkip = true;
            r.resolved.addAll(requested);
            return r;
        }

        // 3. Build new TypeItem objects (fresh, no containment)
        List<Object> newItems = new ArrayList<>();
        List<String> partial = new ArrayList<>();
        for (String fqn : requested)
        {
            Object typeItem = createTypeItem(fqn, config);
            if (typeItem != null)
            {
                newItems.add(typeItem);
                r.resolved.add(fqn);
            }
            else
            {
                partial.add(fqn);
            }
        }
        if (newItems.isEmpty())
        {
            r.error = "Could not resolve any TypeItem for the requested FQNs: "
                + String.join(", ", partial);
            r.unresolved.addAll(partial);
            return r;
        }

        // 4. Mutate: clear + add
        try
        {
            typesList.clear();
            for (Object item : newItems)
            {
                ((EList) typesList).add(item);
            }
            r.ok = true;
            r.mutated = true;
            r.unresolved.addAll(partial);
            return r;
        }
        catch (Exception e)
        {
            Activator.logWarning("setTypes mutation failed: " + e.getMessage()); //$NON-NLS-1$
            r.error = "Mutation failed: " + e.getMessage();
            return r;
        }
    }

    /**
     * Builds a fresh TypeItem for the given FQN. Strategy:
     * <ol>
     *   <li>For {@code <X>Ref.<Name>} or {@code <X>Object.<Name>}: locate the
     *       referenced MdObject in {@code config}, pull its produced types,
     *       pick the matching kind, and {@link EcoreUtil#copy} it (so the
     *       resulting TypeItem is detached from its original container).</li>
     *   <li>For primitives: probe the platform factory and try a generic
     *       {@code create<Name>TypeItem} method.</li>
     * </ol>
     * Returns {@code null} when none of the above resolves.
     */
    private static Object createTypeItem(String fqn, Configuration config)
    {
        if (fqn == null || fqn.isEmpty())
        {
            return null;
        }
        // Reference / Object / Selection / Manager types - via produced types
        Object copy = createFromProducedTypes(fqn, config);
        if (copy != null)
        {
            return copy;
        }
        // Primitives or DefinedType.X - try factory
        Object byFactory = createViaFactoryProbe(fqn);
        if (byFactory != null)
        {
            return byFactory;
        }
        return null;
    }

    /**
     * Resolves a {@code <Kind>.<Name>} FQN to an existing produced TypeItem,
     * then makes a deep copy so the result has no container.
     */
    private static Object createFromProducedTypes(String fqn, Configuration config)
    {
        if (config == null || fqn == null || fqn.isEmpty())
        {
            return null;
        }
        String[] parts = fqn.split("\\.", 2); //$NON-NLS-1$
        if (parts.length != 2 || parts[1].isEmpty())
        {
            return null;
        }
        String kind = parts[0];
        String typePrefix = stripTypeKindSuffix(kind);
        if (typePrefix == null)
        {
            return null;
        }
        // Locate the MdObject by short type name + name (CatalogRef -> Catalog)
        MdObject target = MetadataTypeUtils.findObject(config, typePrefix, parts[1]);
        if (target == null)
        {
            return null;
        }
        // Get producedTypes via reflection (avoids hard import of MdClassUtil
        // for builds where it lives in a different bundle)
        Object producedTypes = invokeNoArg(target, "getProducedTypes"); //$NON-NLS-1$
        if (producedTypes == null)
        {
            // Try MdClassUtil.getProducedTypes via reflection
            producedTypes = invokeStatic("com._1c.g5.v8.dt.metadata.mdclass.util.MdClassUtil", //$NON-NLS-1$
                "getProducedTypes", new Class<?>[] { MdObject.class }, target); //$NON-NLS-1$
        }
        if (!(producedTypes instanceof EObject))
        {
            return null;
        }
        // Iterate eContents() looking for the matching kind
        Collection<EObject> contents = ((EObject) producedTypes).eContents();
        Object matched = pickProducedTypeForKind(contents, kind);
        if (!(matched instanceof EObject))
        {
            return null;
        }
        Object typeItem = invokeNoArg(matched, "getType"); //$NON-NLS-1$
        if (typeItem == null)
        {
            typeItem = invokeNoArg(matched, "getTypeSet"); //$NON-NLS-1$
        }
        if (!(typeItem instanceof EObject))
        {
            return null;
        }
        // Detach via deep copy so we can place it under another container
        return EcoreUtil.copy((EObject) typeItem);
    }

    /**
     * Maps an FQN-kind prefix ({@code CatalogRef}, {@code DocumentObject},
     * {@code DefinedType}, ...) to the metadata type (Catalog, Document,
     * DefinedType). Returns {@code null} for unknown prefixes.
     */
    private static String stripTypeKindSuffix(String kind)
    {
        if (kind == null)
        {
            return null;
        }
        for (String suffix : new String[] { "Ref", "Object", "Selection", "Manager", "Cache", "List" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        {
            if (kind.endsWith(suffix))
            {
                String stripped = kind.substring(0, kind.length() - suffix.length());
                if (!stripped.isEmpty())
                {
                    return stripped;
                }
            }
        }
        // DefinedType / characteristics are matched directly
        if ("DefinedType".equals(kind) || "Characteristic".equals(kind)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return kind;
        }
        return null;
    }

    /**
     * Picks the produced type entry matching the requested kind
     * (Ref/Object/Selection/Manager/Cache/List) by inspecting the EClass name.
     */
    private static Object pickProducedTypeForKind(Collection<EObject> contents, String kind)
    {
        if (contents == null || contents.isEmpty())
        {
            return null;
        }
        // The original kind is something like "CatalogRef" - we need the trailing suffix
        String wantSuffix = null;
        for (String s : new String[] { "Ref", "Object", "Selection", "Manager", "Cache", "List" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        {
            if (kind.endsWith(s))
            {
                wantSuffix = s;
                break;
            }
        }
        // Default to Ref if the FQN was just "DefinedType.X"
        if (wantSuffix == null)
        {
            wantSuffix = "Ref"; //$NON-NLS-1$
        }
        for (EObject e : contents)
        {
            String className = e.eClass().getName();
            // EDT names produced types like RefMdType, ObjectMdType, SelectionMdType
            if (className.startsWith(wantSuffix))
            {
                return e;
            }
        }
        // Fallback: first element
        return contents.iterator().next();
    }

    /**
     * Probes platform factories for {@code create<Name>TypeItem} - used as a
     * last resort for primitives. Returns {@code null} on any failure.
     */
    private static Object createViaFactoryProbe(String fqn)
    {
        for (String factoryClassName : FACTORY_CANDIDATES)
        {
            try
            {
                Class<?> clazz = Class.forName(factoryClassName);
                Field eInstance = clazz.getField("eINSTANCE"); //$NON-NLS-1$
                Object factory = eInstance.get(null);
                // Try createTypeItem first - factory may set name via eSet
                for (String method : new String[] { "createTypeItem", "createType" }) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    try
                    {
                        Method m = factory.getClass().getMethod(method);
                        Object item = m.invoke(factory);
                        if (item instanceof EObject)
                        {
                            // Best effort: try to set name to the FQN
                            tryEsetName((EObject) item, fqn);
                            return item;
                        }
                    }
                    catch (NoSuchMethodException ignored)
                    {
                        // try next
                    }
                }
            }
            catch (ClassNotFoundException ignored)
            {
                // try next factory
            }
            catch (Exception e)
            {
                Activator.logWarning("createViaFactoryProbe " + factoryClassName //$NON-NLS-1$
                    + " failed: " + e.getMessage()); //$NON-NLS-1$
            }
        }
        return null;
    }

    private static void tryEsetName(EObject item, String value)
    {
        for (String f : new String[] { "name", "typeName", "primitiveType" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            org.eclipse.emf.ecore.EStructuralFeature feature = item.eClass().getEStructuralFeature(f);
            if (feature != null)
            {
                try
                {
                    item.eSet(feature, value);
                    return;
                }
                catch (Exception ignored)
                {
                    // try next feature
                }
            }
        }
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

    private static Object invokeStatic(String className, String methodName,
        Class<?>[] paramTypes, Object... args)
    {
        try
        {
            Class<?> clazz = Class.forName(className);
            Method m = clazz.getMethod(methodName, paramTypes);
            return m.invoke(null, args);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * Reads canonical type names from the existing TypeDescription.types list
     * (via {@code McoreUtil.getTypeName} reflection). Used for idempotency.
     */
    @SuppressWarnings("unchecked")
    private static Set<String> readCurrentTypeNames(EList<?> typesList)
    {
        Set<String> names = new HashSet<>();
        if (typesList == null || typesList.isEmpty())
        {
            return names;
        }
        for (Object item : (EList<Object>) typesList)
        {
            String name = readTypeName(item);
            if (name != null && !name.isEmpty())
            {
                names.add(name);
            }
        }
        return names;
    }

    private static String readTypeName(Object typeItem)
    {
        ensureClassProbeDone();
        // McoreUtil.getTypeName(TypeItem) - prefer if available (cached probes)
        if (cachedTypeItemClass != null && cachedMcoreUtilClass != null)
        {
            try
            {
                Method m = cachedMcoreUtilClass.getMethod("getTypeName", cachedTypeItemClass); //$NON-NLS-1$
                Object viaUtil = m.invoke(null, typeItem);
                if (viaUtil instanceof String)
                {
                    return (String) viaUtil;
                }
            }
            catch (Exception ignored)
            {
                // fall through to direct getter probe
            }
        }
        // Fallback: try common getter names directly on the item
        for (String getter : new String[] { "getName", "getTypeName" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            Object v = invokeNoArg(typeItem, getter);
            if (v instanceof String)
            {
                return (String) v;
            }
        }
        return null;
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

    private static Object readTypeDescription(MdObject definedType)
    {
        for (String getter : new String[] { "getType", "getTypes", "getTypeDescription" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            try
            {
                Method m = definedType.getClass().getMethod(getter);
                Object result = m.invoke(definedType);
                if (result != null)
                {
                    return result;
                }
            }
            catch (NoSuchMethodException ignored)
            {
                // try next
            }
            catch (Exception e)
            {
                Activator.logWarning("readTypeDescription " + getter + " failed: " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return null;
    }

    private static EList<?> readTypesList(Object typeDesc)
    {
        try
        {
            Method m = typeDesc.getClass().getMethod("getTypes"); //$NON-NLS-1$
            Object list = m.invoke(typeDesc);
            if (list instanceof EList)
            {
                return (EList<?>) list;
            }
        }
        catch (NoSuchMethodException ignored)
        {
            // try alternative
        }
        catch (Exception e)
        {
            Activator.logWarning("readTypesList failed: " + e.getMessage()); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Best-effort check that the FQN looks like a valid type reference.
     * Accepts {@code <Type>Ref.<Name>} (CatalogRef, DocumentRef, EnumRef, etc.)
     * and a small set of primitive type names.
     */
    private static boolean isKnownTypeShape(String fqn, Configuration config)
    {
        if (fqn == null || fqn.isEmpty())
        {
            return false;
        }
        String[] parts = fqn.split("\\.", 2); //$NON-NLS-1$
        if (parts.length == 1)
        {
            // primitive: String / Number / Date / Boolean / UUID / ...
            return parts[0].matches("[A-Z][A-Za-z]+"); //$NON-NLS-1$
        }
        if (parts[0].endsWith("Ref") || parts[0].endsWith("Object")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return parts[1] != null && !parts[1].isEmpty();
        }
        if (parts[0].equals("DefinedType") || parts[0].equals("ОпределяемыйТип")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return true;
        }
        return false;
    }
}
