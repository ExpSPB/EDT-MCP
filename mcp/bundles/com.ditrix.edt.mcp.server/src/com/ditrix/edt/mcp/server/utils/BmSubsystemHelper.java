/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.emf.common.util.EList;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Helper for {@code Subsystem} operations: include/exclude objects in the
 * {@code content} list. <p>
 *
 * <p>Subsystem content is an EList of cross-references to other metadata
 * objects (catalogs, documents, registers, etc.). The EMF feature is exposed
 * via {@code getContent()} on the {@code Subsystem} class, returning a list
 * whose element type varies between EDT versions ({@code MdObject},
 * {@code MetadataObject}, {@code IBmObject}). We rely on case-insensitive
 * name comparison to stay compatible.
 *
 * <p>1.40 operations: {@code addSubsystemContent}, {@code removeSubsystemContent} -
 * both idempotent (no-op when the object is already in / out).
 */
public final class BmSubsystemHelper
{
    private BmSubsystemHelper()
    {
        // utility
    }

    /**
     * Adds the target object to the subsystem's content list, idempotent.
     *
     * @param subsystem the Subsystem MdObject to mutate
     * @param target    the metadata object to include
     * @return true when an addition happened, false when it was already present
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static boolean addContent(MdObject subsystem, MdObject target)
    {
        if (subsystem == null || target == null)
        {
            return false;
        }
        EList contentList = getContentList(subsystem);
        if (contentList == null)
        {
            return false;
        }
        for (Object existing : contentList)
        {
            if (existing == target)
            {
                return false;
            }
            if (existing instanceof MdObject
                && target.getName().equalsIgnoreCase(((MdObject) existing).getName())
                && target.eClass().getName().equals(((MdObject) existing).eClass().getName()))
            {
                return false;
            }
        }
        contentList.add(target);
        return true;
    }

    /**
     * Removes a target object (resolved by FQN) from the subsystem's content.
     *
     * @return true when an item was removed
     */
    @SuppressWarnings("rawtypes")
    public static boolean removeContent(MdObject subsystem, String targetFqn)
    {
        if (subsystem == null || targetFqn == null || targetFqn.isEmpty())
        {
            return false;
        }
        EList contentList = getContentList(subsystem);
        if (contentList == null)
        {
            return false;
        }
        String[] parts = targetFqn.split("\\.", 2); //$NON-NLS-1$
        if (parts.length != 2)
        {
            return false;
        }
        String type = parts[0];
        String name = parts[1];
        Object found = null;
        for (Object existing : contentList)
        {
            if (existing instanceof MdObject)
            {
                MdObject m = (MdObject) existing;
                if (name.equalsIgnoreCase(m.getName())
                    && (type.equalsIgnoreCase(m.eClass().getName())
                        || type.equalsIgnoreCase(MetadataTypeUtils.toEnglishSingular(m.eClass().getName()))))
                {
                    found = existing;
                    break;
                }
            }
        }
        if (found == null)
        {
            return false;
        }
        contentList.remove(found);
        return true;
    }

    /**
     * Resolves a metadata object by its FQN against the project's configuration.
     */
    public static MdObject resolveByFqn(Configuration config, String fqn)
    {
        if (config == null || fqn == null || fqn.isEmpty())
        {
            return null;
        }
        String normalized = MetadataTypeUtils.normalizeFqn(fqn);
        String[] parts = normalized.split("\\.", 2); //$NON-NLS-1$
        if (parts.length < 2)
        {
            return null;
        }
        return MetadataTypeUtils.findObject(config, parts[0], parts[1]);
    }

    /**
     * Builds a {@code targetNotFound} error tag for {@code addSubsystemContent}.
     */
    public static MetadataGuards.BlockedGuardException targetNotFound(String targetFqn)
    {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("targetFqn", targetFqn); //$NON-NLS-1$
        return new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
            "Target metadata object not found in configuration: " + targetFqn,
            "Verify the FQN. Use 'Type.Name' shape, e.g. 'Catalog.Goods'.",
            new MetadataGuards.ErrorTag("targetNotFound", data))); //$NON-NLS-1$
    }

    @SuppressWarnings({ "rawtypes" })
    private static EList getContentList(MdObject subsystem)
    {
        try
        {
            Method m = subsystem.getClass().getMethod("getContent"); //$NON-NLS-1$
            Object list = m.invoke(subsystem);
            if (list instanceof EList)
            {
                return (EList) list;
            }
        }
        catch (NoSuchMethodException ignored)
        {
            // not a subsystem
        }
        catch (Exception e)
        {
            Activator.logWarning("getContentList failed: " + e.getMessage()); //$NON-NLS-1$
        }
        return null;
    }
}
