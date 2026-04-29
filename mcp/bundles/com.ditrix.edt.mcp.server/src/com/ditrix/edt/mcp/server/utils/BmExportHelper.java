/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Generalized BM persistence helper. Wraps
 * {@code IBmModelManager.forceExport(IDtProject, List<String>)} with a
 * subsequent {@code waitComputation()} on the EXP_O / EXP_B / FORM_EXT
 * derived-data segments, mirroring a known upstream contract
 * helper.
 * <p>
 * Used by every tool that mutates the BM model:
 * <ul>
 *   <li>{@code WriteModuleSourceTool} (replaces the inline forceExport call
 *       added in 1.31).</li>
 *   <li>{@code EditFormTool} (already has a private {@code persistFormChanges} -
 *       refactor target for 1.40).</li>
 *   <li>{@code EditMetadataTool} (every operation that lands a write).</li>
 * </ul>
 */
public final class BmExportHelper
{
    /**
     * Derived-data segment names expected after a write. Discovered from the
     * reference upstream helper (BmExportHelper.DD_SEGMENT_*).
     */
    public static final String DD_SEGMENT_EXPORT_OBJECT = "EXP_O"; //$NON-NLS-1$
    public static final String DD_SEGMENT_EXPORT_BLOB = "EXP_B"; //$NON-NLS-1$
    public static final String DD_SEGMENT_FORM_EXT = "FORM_EXT"; //$NON-NLS-1$

    /** Default soft cap for the wait phase (10s). */
    private static final long DEFAULT_WAIT_TIMEOUT_MS = 10_000L;

    private BmExportHelper()
    {
        // utility class
    }

    /**
     * Result of a force-export call.
     */
    public static final class Result
    {
        public List<String> fqns;
        public boolean forceExportOk;
        public boolean waitComputationOk;
        public long forceExportMs;
        public long waitComputationMs;
        public long totalMs;
        public String error;

        public boolean isOk()
        {
            return error == null && forceExportOk;
        }
    }

    /**
     * Forces export of the given top-object FQN and waits for the export
     * derived-data segments to settle. Single-FQN convenience overload.
     */
    public static Result forceExportAndWait(IBmModelManager manager, IProject project, String fqn)
    {
        return forceExportAndWait(manager, project, Collections.singletonList(fqn),
            DEFAULT_WAIT_TIMEOUT_MS);
    }

    /**
     * Bulk overload. {@code fqns} may contain any mix of top-object FQNs
     * (e.g. {@code "Catalog.Products"} and {@code "Form.ItemForm.Form"}).
     */
    public static Result forceExportAndWait(IBmModelManager manager, IProject project,
        List<String> fqns, long waitTimeoutMs)
    {
        Result r = new Result();
        r.fqns = fqns;
        long t0 = System.currentTimeMillis();
        if (manager == null)
        {
            r.error = "IBmModelManager is null"; //$NON-NLS-1$
            return r;
        }
        if (project == null)
        {
            r.error = "project is null"; //$NON-NLS-1$
            return r;
        }
        if (fqns == null || fqns.isEmpty())
        {
            r.error = "fqns is empty"; //$NON-NLS-1$
            return r;
        }

        try
        {
            Class<?> dtProjectIface = Class.forName("com._1c.g5.v8.dt.core.platform.IDtProject"); //$NON-NLS-1$
            Method getDtProject = manager.getClass().getMethod("getDtProject", String.class); //$NON-NLS-1$
            Object dtProject = getDtProject.invoke(manager, project.getName());
            if (dtProject == null)
            {
                r.error = "IDtProject not resolved for " + project.getName(); //$NON-NLS-1$
                return r;
            }

            // Try the List overload first - it lets EDT batch the export once
            long forceStart = System.currentTimeMillis();
            boolean exportOk = false;
            try
            {
                Method forceExportList = manager.getClass()
                    .getMethod("forceExport", dtProjectIface, List.class); //$NON-NLS-1$
                Object res = forceExportList.invoke(manager, dtProject, fqns);
                exportOk = !(res instanceof Boolean) || ((Boolean) res).booleanValue();
            }
            catch (NoSuchMethodException nsmeList)
            {
                // Fall back to the per-FQN String overload
                Method forceExportString = manager.getClass()
                    .getMethod("forceExport", dtProjectIface, String.class); //$NON-NLS-1$
                exportOk = true;
                for (String fqn : fqns)
                {
                    Object res = forceExportString.invoke(manager, dtProject, fqn);
                    if (res instanceof Boolean && !((Boolean) res).booleanValue())
                    {
                        exportOk = false;
                    }
                }
            }
            r.forceExportOk = exportOk;
            r.forceExportMs = System.currentTimeMillis() - forceStart;

            if (!exportOk)
            {
                Activator.logWarning("forceExport returned false for " + fqns //$NON-NLS-1$
                    + " - waitComputation skipped"); //$NON-NLS-1$
            }
            else
            {
                long waitStart = System.currentTimeMillis();
                r.waitComputationOk = waitForSegments(manager, dtProject,
                    Arrays.asList(DD_SEGMENT_EXPORT_OBJECT, DD_SEGMENT_EXPORT_BLOB,
                        DD_SEGMENT_FORM_EXT),
                    waitTimeoutMs);
                r.waitComputationMs = System.currentTimeMillis() - waitStart;
            }
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            r.error = "wait interrupted"; //$NON-NLS-1$
        }
        catch (Throwable t)
        {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            r.error = cause.getClass().getSimpleName() + ": " + cause.getMessage(); //$NON-NLS-1$
            Activator.logWarning("BmExportHelper.forceExportAndWait failed: " + r.error); //$NON-NLS-1$
        }
        r.totalMs = System.currentTimeMillis() - t0;
        return r;
    }

    /**
     * Polls {@code waitComputation(...)} for the given derived-data segments
     * up to the given timeout. The exact method signature varies between EDT
     * versions, so we try a few shapes via reflection. Returns {@code true}
     * when the EDT confirms the segments are computed; {@code false} otherwise.
     */
    private static boolean waitForSegments(IBmModelManager manager, Object dtProject,
        List<String> segments, long timeoutMs) throws InterruptedException
    {
        if (segments == null || segments.isEmpty())
        {
            return false;
        }

        // Candidate signature 1: waitComputation(IDtProject, String[], long timeoutMs)
        // Candidate signature 2: waitComputation(IDtProject, Collection<String>)
        // Candidate signature 3: waitModelSynchronization(IProject) - fallback
        Class<?> dtProjectIface = dtProject.getClass();
        Method best = null;
        for (Method m : manager.getClass().getMethods())
        {
            if (!"waitComputation".equals(m.getName())) //$NON-NLS-1$
            {
                continue;
            }
            Class<?>[] params = m.getParameterTypes();
            if (params.length >= 1 && params[0].isAssignableFrom(dtProjectIface))
            {
                best = m;
                break;
            }
        }
        if (best != null)
        {
            try
            {
                Object[] args = buildWaitArgs(best, dtProject, segments, timeoutMs);
                Object result = best.invoke(manager, args);
                return !(result instanceof Boolean) || ((Boolean) result).booleanValue();
            }
            catch (Exception e)
            {
                Activator.logWarning("BmExportHelper.waitComputation failed: " + e.getMessage()); //$NON-NLS-1$
            }
        }

        // Last resort: short polling sleep
        Thread.sleep(Math.min(500L, timeoutMs));
        return true;
    }

    private static Object[] buildWaitArgs(Method m, Object dtProject, List<String> segments,
        long timeoutMs)
    {
        Class<?>[] params = m.getParameterTypes();
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++)
        {
            Class<?> p = params[i];
            if (p.isAssignableFrom(dtProject.getClass()))
            {
                args[i] = dtProject;
            }
            else if (p == String[].class)
            {
                args[i] = segments.toArray(new String[0]);
            }
            else if (p.isAssignableFrom(java.util.List.class))
            {
                args[i] = segments;
            }
            else if (p == long.class || p == Long.class)
            {
                args[i] = Long.valueOf(timeoutMs);
            }
            else
            {
                args[i] = null; // best-effort
            }
        }
        return args;
    }
}
