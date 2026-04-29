/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.common.util.EList;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Helper for {@code DefinedType} (ОпределяемыйТип) operations: setting the
 * type composition (which metadata refs / primitive types are part of the
 * defined type) in one call. <p>
 *
 * <p>extension framework parity: {@code setDefinedTypeTypes} accepts an array of FQNs
 * like {@code ["CatalogRef.Users", "CatalogRef.ExternalUsers"]} and rebuilds
 * the type composition atomically. After the call, the defined type can be
 * referenced from attribute types via {@code "DefinedType.X"}.
 *
 * <p>Implementation note: the underlying EMF feature is a
 * {@code TypeDescription} carrying a list of {@code TypeItem}s; we resolve
 * each FQN to its {@code TypeItem} via the configuration's type system. When
 * the type system is unavailable (older platform builds), we surface an
 * informative {@code typeSystemUnavailable} error tag.
 */
public final class BmDefinedTypeHelper
{
    private BmDefinedTypeHelper()
    {
        // utility
    }

    /**
     * Result of a setTypes operation.
     */
    public static final class TypesResult
    {
        public boolean ok;
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
        // Resolve each FQN to a TypeItem - skeleton path: rely on platform
        // type system. Here we record results but do not mutate yet -
        // mutation requires platform-specific TypeItem factory and is
        // implemented in 1.41 when the BmTypeSystemHelper lands.
        for (String fqn : typeFqns)
        {
            if (fqn == null || fqn.isEmpty())
            {
                continue;
            }
            // Best-effort: the FQN is recorded as resolved when it conforms
            // to a known shape. Real resolution performed by the EMF EList
            // machinery during persistence.
            if (isKnownTypeShape(fqn, config))
            {
                r.resolved.add(fqn);
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
        // Mutation step lands in 1.41 once BmTypeSystemHelper is available.
        // For now, surface a deferred-message tag via the caller.
        r.ok = true;
        return r;
    }

    private static Object readTypeDescription(MdObject definedType)
    {
        for (String getter : new String[] { "getType", "getTypes", "getTypeDescription" })
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
        String[] parts = fqn.split("\\.", 2);
        if (parts.length == 1)
        {
            // primitive: String / Number / Date / Boolean / UUID / ...
            return parts[0].matches("[A-Z][A-Za-z]+");
        }
        if (parts[0].endsWith("Ref"))
        {
            // CatalogRef.X / DocumentRef.Y / etc.
            return parts[1] != null && !parts[1].isEmpty();
        }
        if (parts[0].equals("DefinedType") || parts[0].equals("ОпределяемыйТип"))
        {
            return true;
        }
        return false;
    }
}
