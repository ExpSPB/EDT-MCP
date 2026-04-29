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

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import com.ditrix.edt.mcp.server.Activator;

/**
 * <b>Defensive layer 3.8.2</b>: guards against the platform-rejected
 * combinations for {@code CommonModule} inside a configuration extension.
 * <p>
 * The platform forbids two combinations on UpdateDBCfg:
 * <ul>
 *   <li>{@code privileged=true} - "privileged common modules are not allowed
 *       in extensions"</li>
 *   <li>{@code global=true} together with {@code server=true}</li>
 * </ul>
 * The visual EDT editor flags these in the UI, but headless creation through
 * MCP would only fail at the very end of the configure-update cycle, after
 * dozens of agent-driven edits, requiring a full rollback.
 *
 * <p>This helper checks the project type (extension vs regular configuration)
 * and validates the requested flags before letting BmObjectHelper write the
 * module to disk - failing fast with a structured error tag.
 */
public final class BmCommonModuleGuards
{
    private BmCommonModuleGuards()
    {
        // utility
    }

    /**
     * Returns true when the given project is a configuration extension
     * (its {@code Configuration} carries a non-null
     * {@code configurationExtensionPurpose}).
     */
    public static boolean isExtensionProject(IProject project)
    {
        if (project == null || !project.isOpen())
        {
            return false;
        }
        try
        {
            Configuration config = Activator.getDefault().getConfigurationProvider()
                .getConfiguration(project);
            if (config == null)
            {
                return false;
            }
            Method m = config.getClass().getMethod("getConfigurationExtensionPurpose"); //$NON-NLS-1$
            Object purpose = m.invoke(config);
            return purpose != null;
        }
        catch (NoSuchMethodException ignored)
        {
            // Not an extension-aware platform version
            return false;
        }
        catch (Exception e)
        {
            Activator.logWarning("isExtensionProject probe failed: " + e.getMessage()); //$NON-NLS-1$
            return false;
        }
    }

    /**
     * Validates flag combinations for a CommonModule being created (or about
     * to receive {@code setObjectProperty}) in the given project.
     * <p>
     * Throws {@link MetadataGuards.BlockedGuardException} with one of:
     * {@code privilegedNotAllowedInExtension},
     * {@code globalServerNotAllowedInExtension}.
     *
     * @param project   the target project (must be open)
     * @param privileged the privileged-flag value being set; null/false is OK
     * @param globalFlag the global-flag value being set; null/false is OK
     * @param serverFlag the server-flag value being set; null/false is OK
     */
    public static void validate(IProject project, Boolean privileged, Boolean globalFlag,
        Boolean serverFlag) throws MetadataGuards.BlockedGuardException
    {
        if (!isExtensionProject(project))
        {
            return; // regular config - all flag combinations allowed
        }
        if (Boolean.TRUE.equals(privileged))
        {
            throw privilegedInExtension();
        }
        if (Boolean.TRUE.equals(globalFlag) && Boolean.TRUE.equals(serverFlag))
        {
            throw globalServerInExtension();
        }
    }

    /**
     * Same as {@link #validate(IProject, Boolean, Boolean, Boolean)} but
     * reads the flags directly from an already-existing CommonModule object.
     * Useful as a post-mutation guard inside a BM transaction (e.g. after
     * {@code setObjectProperty}).
     */
    public static void validateExisting(IProject project, MdObject commonModule)
        throws MetadataGuards.BlockedGuardException
    {
        if (commonModule == null || !isExtensionProject(project))
        {
            return;
        }
        Boolean privileged = readBooleanProperty(commonModule, "isPrivileged"); //$NON-NLS-1$
        Boolean global = readBooleanProperty(commonModule, "isGlobal"); //$NON-NLS-1$
        Boolean server = readBooleanProperty(commonModule, "isServer"); //$NON-NLS-1$
        if (Boolean.TRUE.equals(privileged))
        {
            throw privilegedInExtension();
        }
        if (Boolean.TRUE.equals(global) && Boolean.TRUE.equals(server))
        {
            throw globalServerInExtension();
        }
    }

    private static Boolean readBooleanProperty(Object obj, String getter)
    {
        try
        {
            Method m = obj.getClass().getMethod(getter);
            Object result = m.invoke(obj);
            if (result instanceof Boolean)
            {
                return (Boolean) result;
            }
        }
        catch (NoSuchMethodException ignored)
        {
            // try the get<X> form
            try
            {
                String alt = "get" + getter.substring(2);
                Method m2 = obj.getClass().getMethod(alt);
                Object result = m2.invoke(obj);
                if (result instanceof Boolean)
                {
                    return (Boolean) result;
                }
            }
            catch (Exception ignored2)
            {
                return null;
            }
        }
        catch (Exception e)
        {
            return null;
        }
        return null;
    }

    private static MetadataGuards.BlockedGuardException privilegedInExtension()
    {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("flag", "privileged"); //$NON-NLS-1$ //$NON-NLS-2$
        data.put("rejectedBy", "platform on UpdateDBCfg"); //$NON-NLS-1$ //$NON-NLS-2$
        return new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
            "Privileged common module is not allowed in an extension "
                + "(platform rejects on UpdateDBCfg). Drop the privileged flag.",
            "Use isPrivileged=false. If privileged context is required, place the module in the base configuration.",
            new MetadataGuards.ErrorTag("privilegedNotAllowedInExtension", data))); //$NON-NLS-1$
    }

    private static MetadataGuards.BlockedGuardException globalServerInExtension()
    {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("flags", "global=true + server=true"); //$NON-NLS-1$ //$NON-NLS-2$
        data.put("rejectedBy", "platform on UpdateDBCfg"); //$NON-NLS-1$ //$NON-NLS-2$
        return new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
            "global=true together with server=true is not allowed in an extension "
                + "(platform rejects on UpdateDBCfg).",
            "Pick one: server-only OR global-only. Most extension code wants server=true, global=false.",
            new MetadataGuards.ErrorTag("globalServerNotAllowedInExtension", data))); //$NON-NLS-1$
    }
}
