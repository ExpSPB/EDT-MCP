/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

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
 */
public final class BmRightsHelper
{
    private static volatile Boolean apiAvailable;
    private static final Object LOCK = new Object();

    /** Right alias map: case-insensitive lookup, value is canonical name. */
    private static final Map<String, String> RIGHT_ALIASES = buildRightAliases();

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
            "com._1c.g5.v8.dt.rights.model.RightsFactory",
            "com._1c.g5.v8.dt.rights.model.ObjectRights",
            "com._1c.g5.v8.dt.rights.model.RoleDescription"
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
     * Resolves the rights container for a given role and target object FQN.
     * <p>
     * Skeleton: returns a probe-class reference. Real mutation lands in 1.41
     * when the rights subsystem is integrated end-to-end. Until then,
     * callers should surface {@link #deferredMessage(String)} to the user.
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
