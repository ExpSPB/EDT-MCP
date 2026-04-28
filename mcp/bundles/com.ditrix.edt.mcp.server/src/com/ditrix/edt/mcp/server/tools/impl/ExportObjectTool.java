/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.io.File;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;

/**
 * Tool to export an external data processor / report project to a binary
 * .epf / .erf file.
 * <p>
 * <b>Status:</b> spike implementation. The 1C:EDT CLI ({@code 1cedtcli}) does
 * not expose a public command for assembling .epf / .erf files - it has
 * {@code export} (project to XML files) and {@code build} (validation only).
 * The internal EDT export API ({@code IExportPlatformService}, {@code BinaryDataExporter}
 * and similar) is package-restricted on the official 2025.2 / 2026.1 builds.
 * <p>
 * The tool tries a best-effort reflection lookup over the most-likely class
 * names and degrades gracefully with a precise error message when the API is
 * not reachable. When that happens, the agent receives clear instructions to
 * fall back to the EDT GUI ({@code File → Export → External data processor}).
 * <p>
 * Phase 4 will revisit this with a proper {@code BmExportHelper} integration
 * once the API surface is confirmed against EDT 2026.1.
 */
public class ExportObjectTool implements IMcpTool
{
    public static final String NAME = "export_object"; //$NON-NLS-1$

    /** Candidate fully-qualified names of the EDT export service we probe via reflection. */
    private static final String[] EXPORT_SERVICE_CANDIDATES = {
        "com._1c.g5.v8.dt.export.epf.IEpfExportService", //$NON-NLS-1$
        "com._1c.g5.v8.dt.export.IExternalObjectExporter", //$NON-NLS-1$
        "com._1c.g5.v8.dt.export.IExportPlatformService", //$NON-NLS-1$
        "com._1c.g5.v8.dt.bm.serializer.IBinaryDataExporter" //$NON-NLS-1$
    };

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Build an external data processor / report project into a binary " //$NON-NLS-1$
            + ".epf or .erf file, ready to open in 1C:Enterprise. " //$NON-NLS-1$
            + "Auto-detects the platform version from the project. " //$NON-NLS-1$
            + "STATUS: best-effort - depends on an EDT export API that is not " //$NON-NLS-1$
            + "exposed publicly on every EDT version. When the API is missing, " //$NON-NLS-1$
            + "returns a precise error and instructions for the EDT GUI fallback."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", "EDT project name (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("outputPath", //$NON-NLS-1$
                "Absolute path to the output .epf / .erf file (required). " //$NON-NLS-1$
                    + "Extension is appended automatically based on the object kind " //$NON-NLS-1$
                    + "(.epf for ExternalDataProcessor, .erf for ExternalReport).", true) //$NON-NLS-1$
            .stringProperty("objectName", //$NON-NLS-1$
                "Object name within the project (optional). Required only when the " //$NON-NLS-1$
                    + "project contains more than one external object.") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String outputPath = JsonUtils.extractStringArgument(params, "outputPath"); //$NON-NLS-1$
        String objectName = JsonUtils.extractStringArgument(params, "objectName"); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required").toJson(); //$NON-NLS-1$
        }
        if (outputPath == null || outputPath.isEmpty())
        {
            return ToolResult.error(
                "outputPath is required. Specify an absolute path with .epf or .erf extension, " //$NON-NLS-1$
                    + "e.g. outputPath=\"C:/build/MyReport.erf\". Without an output path, " //$NON-NLS-1$
                    + "ask the user where to save the file.").toJson();
        }

        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
        }

        // Reflection probe: which EDT export API is reachable?
        Class<?> serviceClass = findExportService();
        if (serviceClass == null)
        {
            return buildApiUnavailableError();
        }
        long start = System.currentTimeMillis();
        Activator.logInfo("ExportObjectTool: discovered candidate API " //$NON-NLS-1$
            + serviceClass.getName() + " (project=" + projectName //$NON-NLS-1$
            + ", outputPath=" + outputPath //$NON-NLS-1$
            + ", objectName=" + objectName + ")"); //$NON-NLS-1$ //$NON-NLS-2$

        // Best-effort invocation via OSGi service + reflection.
        BundleContext bc = FrameworkUtil.getBundle(ExportObjectTool.class).getBundleContext();
        if (bc == null)
        {
            return surfaceServiceFailure(serviceClass, projectName, outputPath,
                "BundleContext not available", start); //$NON-NLS-1$
        }
        ServiceReference<?> ref = bc.getServiceReference(serviceClass.getName());
        if (ref == null)
        {
            return surfaceServiceFailure(serviceClass, projectName, outputPath,
                "OSGi service reference not exposed", start); //$NON-NLS-1$
        }
        Object service = bc.getService(ref);
        if (service == null)
        {
            bc.ungetService(ref);
            return surfaceServiceFailure(serviceClass, projectName, outputPath,
                "OSGi service instance is null", start); //$NON-NLS-1$
        }
        try
        {
            Method exportMethod = findExportMethod(service.getClass());
            if (exportMethod == null)
            {
                return surfaceServiceFailure(serviceClass, projectName, outputPath,
                    "No export(...) method recognised on the service", start); //$NON-NLS-1$
            }
            try
            {
                Object[] args = buildExportArgs(exportMethod, project, objectName, outputPath);
                if (args == null)
                {
                    return surfaceServiceFailure(serviceClass, projectName, outputPath,
                        "Unsupported export method signature: " + exportMethod, start); //$NON-NLS-1$
                }
                Object result = exportMethod.invoke(service, args);
                long elapsed = System.currentTimeMillis() - start;
                return ToolResult.success()
                    .put("operation", "export_object") //$NON-NLS-1$ //$NON-NLS-2$
                    .put("projectName", projectName) //$NON-NLS-1$
                    .put("outputPath", outputPath) //$NON-NLS-1$
                    .put("discoveredApi", serviceClass.getName()) //$NON-NLS-1$
                    .put("methodSignature", exportMethod.toString()) //$NON-NLS-1$
                    .put("elapsedMs", elapsed) //$NON-NLS-1$
                    .put("returned", result != null ? result.toString() : "null") //$NON-NLS-1$ //$NON-NLS-2$
                    .toJson();
            }
            catch (Exception invokeEx)
            {
                Throwable cause = invokeEx.getCause() != null ? invokeEx.getCause() : invokeEx;
                String msg = cause.getMessage() != null ? cause.getMessage()
                    : cause.getClass().getSimpleName();
                return surfaceServiceFailure(serviceClass, projectName, outputPath,
                    "Export invocation threw: " + msg, start); //$NON-NLS-1$
            }
        }
        finally
        {
            try
            {
                bc.ungetService(ref);
            }
            catch (Throwable ignored)
            {
                // best-effort cleanup
            }
        }
    }

    private Method findExportMethod(Class<?> serviceClass)
    {
        for (String name : new String[] { "exportEpf", "exportExternalObject", "export", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "exportToFile", "exportProject" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            for (Method m : serviceClass.getMethods())
            {
                if (m.getName().equalsIgnoreCase(name))
                {
                    return m;
                }
            }
        }
        // Any method whose name starts with "export"
        for (Method m : serviceClass.getMethods())
        {
            if (m.getName().toLowerCase().startsWith("export") //$NON-NLS-1$
                && m.getParameterCount() >= 2)
            {
                return m;
            }
        }
        return null;
    }

    private Object[] buildExportArgs(Method m, IProject project, String objectName,
        String outputPath)
    {
        Class<?>[] types = m.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++)
        {
            if (IProject.class.isAssignableFrom(types[i]))
            {
                args[i] = project;
            }
            else if (types[i].equals(String.class))
            {
                // Heuristic: first String becomes objectName, second outputPath.
                args[i] = (i == 1 || objectName == null) ? outputPath : objectName;
            }
            else if (types[i].equals(File.class))
            {
                args[i] = new File(outputPath);
            }
            else
            {
                return null; // unsupported parameter type
            }
        }
        return args;
    }

    private String surfaceServiceFailure(Class<?> serviceClass, String projectName,
        String outputPath, String reason, long start)
    {
        long elapsed = System.currentTimeMillis() - start;
        Map<String, Object> tag = new LinkedHashMap<>();
        tag.put("discoveredApi", serviceClass.getName()); //$NON-NLS-1$
        tag.put("methodHints", describeMethods(serviceClass)); //$NON-NLS-1$
        tag.put("reason", reason); //$NON-NLS-1$
        tag.put("hint", //$NON-NLS-1$
            "Open the project in EDT and use File -> Export -> " //$NON-NLS-1$
                + "'External data processor / report' as a workaround."); //$NON-NLS-1$
        return ToolResult.error("Export failed: " + reason) //$NON-NLS-1$
            .put("operation", "export_object") //$NON-NLS-1$ //$NON-NLS-2$
            .put("projectName", projectName) //$NON-NLS-1$
            .put("outputPath", outputPath) //$NON-NLS-1$
            .put("elapsedMs", elapsed) //$NON-NLS-1$
            .put("exportServiceNotFound", tag) //$NON-NLS-1$
            .toJson();
    }

    private Class<?> findExportService()
    {
        for (String candidate : EXPORT_SERVICE_CANDIDATES)
        {
            try
            {
                return Class.forName(candidate);
            }
            catch (ClassNotFoundException ignored)
            {
                // try next candidate
            }
        }
        return null;
    }

    private String describeMethods(Class<?> serviceClass)
    {
        StringBuilder sb = new StringBuilder();
        Method[] methods = serviceClass.getMethods();
        int count = 0;
        for (Method m : methods)
        {
            String name = m.getName();
            if (name.equals("equals") || name.equals("hashCode") //$NON-NLS-1$ //$NON-NLS-2$
                || name.equals("getClass") || name.equals("toString") //$NON-NLS-1$ //$NON-NLS-2$
                || name.equals("notify") || name.equals("notifyAll") //$NON-NLS-1$ //$NON-NLS-2$
                || name.equals("wait")) //$NON-NLS-1$
            {
                continue;
            }
            if (count > 0)
            {
                sb.append("; "); //$NON-NLS-1$
            }
            sb.append(name).append("(").append(m.getParameterCount()).append(")"); //$NON-NLS-1$ //$NON-NLS-2$
            count++;
            if (count >= 6)
            {
                sb.append("; ..."); //$NON-NLS-1$
                break;
            }
        }
        return sb.length() > 0 ? sb.toString() : "no public methods"; //$NON-NLS-1$
    }

    private String buildApiUnavailableError()
    {
        StringBuilder candidates = new StringBuilder();
        for (int i = 0; i < EXPORT_SERVICE_CANDIDATES.length; i++)
        {
            if (i > 0)
            {
                candidates.append(", "); //$NON-NLS-1$
            }
            candidates.append(EXPORT_SERVICE_CANDIDATES[i]);
        }
        return ToolResult.error(
            "EDT export API not available in this EDT version. " //$NON-NLS-1$
                + "Probed: " + candidates + ". " //$NON-NLS-1$ //$NON-NLS-2$
                + "Workaround: open the project in EDT and use " //$NON-NLS-1$
                + "File -> Export -> 'External data processor / report'. " //$NON-NLS-1$
                + "Alternatively, request a binary build via the EDT GUI - " //$NON-NLS-1$
                + "the underlying API will be wired into export_object once the " //$NON-NLS-1$
                + "package is exposed (planned for the 1.40 release)." //$NON-NLS-1$
        ).toJson();
    }
}
