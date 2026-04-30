/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BmExportHelper;
import com.ditrix.edt.mcp.server.utils.PendingExportRegistry;

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
        "com._1c.g5.v8.dt.bm.serializer.IBinaryDataExporter", //$NON-NLS-1$
        // 1.41: extra candidates probed against EDT 2026.1 namespaces
        "com._1c.g5.v8.dt.epf.export.EpfExportService", //$NON-NLS-1$
        "com._1c.g5.v8.dt.metadata.export.IMetadataExporter", //$NON-NLS-1$
        "com._1c.g5.v8.dt.bm.export.IBmExporter" //$NON-NLS-1$
    };

    /** 1.41: clamp range for timeoutSeconds on the Pending mechanism. */
    private static final int MIN_TIMEOUT_SECONDS = 5;
    private static final int MAX_TIMEOUT_SECONDS = 120;
    private static final long DEFAULT_TIMEOUT_MS = 30_000L;

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
                    + "Extension determines the kind: .epf for ExternalDataProcessor, " //$NON-NLS-1$
                    + ".erf for ExternalReport. If omitted, the kind is auto-detected " //$NON-NLS-1$
                    + "from the project nature and the proper extension is appended.", true) //$NON-NLS-1$
            .stringProperty("objectName", //$NON-NLS-1$
                "Object name within the project (optional). Required only when the " //$NON-NLS-1$
                    + "project contains more than one external object.") //$NON-NLS-1$
            .stringProperty("timeoutSeconds", //$NON-NLS-1$
                "1.41: Soft timeout in seconds before returning a Pending JSON " //$NON-NLS-1$
                    + "with a runKey. Default: 30. Range: 5-120 (clamped). Calling " //$NON-NLS-1$
                    + "again with the same params resumes waiting.") //$NON-NLS-1$
            .stringProperty("runKey", //$NON-NLS-1$
                "1.41: Resume polling a previously-issued export. Pass the runKey " //$NON-NLS-1$
                    + "returned by an earlier Pending response; other params are " //$NON-NLS-1$
                    + "ignored. Returns the result if ready, or another Pending JSON.") //$NON-NLS-1$
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
        // 1.41: explicit retry mode
        String runKeyParam = JsonUtils.extractStringArgument(params, "runKey"); //$NON-NLS-1$
        if (runKeyParam != null && !runKeyParam.isEmpty())
        {
            return resumePending(runKeyParam, params);
        }

        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String outputPathRaw = JsonUtils.extractStringArgument(params, "outputPath"); //$NON-NLS-1$
        String objectName = JsonUtils.extractStringArgument(params, "objectName"); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required").toJson(); //$NON-NLS-1$
        }
        if (outputPathRaw == null || outputPathRaw.isEmpty())
        {
            return ToolResult.error(
                "outputPath is required. Specify an absolute path with .epf or .erf extension, " //$NON-NLS-1$
                    + "e.g. outputPath=\"C:/build/MyReport.erf\".").toJson(); //$NON-NLS-1$
        }

        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
        }

        // 1.41: detect kind from extension or project nature, normalize outputPath
        KindResolution kind = resolveKind(project, outputPathRaw);
        if (kind.error != null)
        {
            return ToolResult.error(kind.error)
                .put("operation", NAME)
                .put("kindMismatch", kind.diagnosticTag())
                .toJson();
        }
        final String outputPath = kind.normalizedPath;
        final String detectedKind = kind.kindLabel;

        // 1.41: external object projects almost always contain a single object
        // with the same name as the project; infer when caller omitted it.
        final String resolvedObjectName = (objectName == null || objectName.isEmpty())
            ? project.getName() : objectName;

        // Pending registry dispatch — runKey from normalized path so retries
        // with the bare path produce the same key as retries with the
        // extension that the first call appended.
        String runKey = PendingExportRegistry.computeRunKey(projectName,
            resolvedObjectName, outputPath);
        long timeoutMs = parseTimeoutMs(params, DEFAULT_TIMEOUT_MS);

        PendingExportRegistry registry = PendingExportRegistry.getInstance();
        registry.pruneExpired();

        PendingExportRegistry.PendingEntry entry = registry.getOrStart(runKey,
            () -> doExport(project, projectName, resolvedObjectName, outputPath, detectedKind));

        String result = entry.await(timeoutMs);
        if (result != null)
        {
            registry.remove(runKey);
            return result;
        }
        return buildPendingJson(runKey, entry, projectName, outputPath, detectedKind, timeoutMs);
    }

    /**
     * 1.41: synchronous export work (runs inside the registry executor).
     * Performs BM sync (forceExportAndWait), reflection probe, OSGi service
     * lookup, and method invocation.
     */
    private String doExport(IProject project, String projectName, String objectName,
        String outputPath, String detectedKind)
    {
        long start = System.currentTimeMillis();

        // 1.41: synchronize BM state to disk before reflection probe so newly
        // edited objects show up to the export service. objectName is already
        // resolved (caller-supplied or inferred from project.getName()) by
        // execute() before this method runs.
        IBmModelManager bmManager = Activator.getDefault().getBmModelManager();
        if (bmManager != null && objectName != null && !objectName.isEmpty())
        {
            String fqn = inferFqnForKind(detectedKind, objectName);
            if (fqn != null)
            {
                BmExportHelper.forceExportAndWait(bmManager, project,
                    Collections.singletonList(fqn), 5_000L);
            }
        }

        Class<?> serviceClass = findExportService();
        if (serviceClass == null)
        {
            return buildApiUnavailableError();
        }
        Activator.logInfo("ExportObjectTool: discovered candidate API " //$NON-NLS-1$
            + serviceClass.getName() + " (project=" + projectName //$NON-NLS-1$
            + ", outputPath=" + outputPath //$NON-NLS-1$
            + ", objectName=" + objectName //$NON-NLS-1$
            + ", detectedKind=" + detectedKind + ")"); //$NON-NLS-1$ //$NON-NLS-2$

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
                    .put("operation", NAME) //$NON-NLS-1$
                    .put("projectName", projectName) //$NON-NLS-1$
                    .put("outputPath", outputPath) //$NON-NLS-1$
                    .put("detectedKind", detectedKind) //$NON-NLS-1$
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

    /**
     * 1.41: poll a previously-issued runKey. Returns the cached result (and
     * removes the entry) or a fresh Pending JSON.
     */
    private String resumePending(String runKey, Map<String, String> params)
    {
        PendingExportRegistry registry = PendingExportRegistry.getInstance();
        registry.pruneExpired();
        PendingExportRegistry.PendingEntry entry = registry.get(runKey);
        if (entry == null)
        {
            return ToolResult.error("runKey not found - the export either completed " //$NON-NLS-1$
                + "and was already retrieved, or was abandoned and evicted by TTL. " //$NON-NLS-1$
                + "Issue a new request without runKey to start over.") //$NON-NLS-1$
                .put("operation", NAME) //$NON-NLS-1$
                .put("runKey", runKey) //$NON-NLS-1$
                .toJson();
        }
        long timeoutMs = parseTimeoutMs(params, DEFAULT_TIMEOUT_MS);
        String result = entry.await(timeoutMs);
        if (result != null)
        {
            registry.remove(runKey);
            return result;
        }
        return buildPendingJson(runKey, entry, null, null, null, timeoutMs);
    }

    private long parseTimeoutMs(Map<String, String> params, long defaultMs)
    {
        String t = JsonUtils.extractStringArgument(params, "timeoutSeconds"); //$NON-NLS-1$
        if (t == null || t.isEmpty())
        {
            return defaultMs;
        }
        try
        {
            int seconds = (int) Double.parseDouble(t);
            seconds = Math.max(MIN_TIMEOUT_SECONDS, Math.min(seconds, MAX_TIMEOUT_SECONDS));
            return seconds * 1000L;
        }
        catch (NumberFormatException e)
        {
            return defaultMs;
        }
    }

    private String buildPendingJson(String runKey, PendingExportRegistry.PendingEntry entry,
        String projectName, String outputPath, String detectedKind, long timeoutMs)
    {
        ToolResult tr = ToolResult.success()
            .put("operation", NAME) //$NON-NLS-1$
            .put("status", "Pending") //$NON-NLS-1$ //$NON-NLS-2$
            .put("runKey", runKey) //$NON-NLS-1$
            .put("elapsedMs", entry.elapsedMs()) //$NON-NLS-1$
            .put("waitedMs", timeoutMs) //$NON-NLS-1$
            .put("hint", "Export still running. Call this tool again with runKey=\"" //$NON-NLS-1$ //$NON-NLS-2$
                + runKey + "\" to resume waiting (or with the same params - they " //$NON-NLS-1$
                + "produce the same runKey)."); //$NON-NLS-1$
        if (projectName != null)
        {
            tr.put("projectName", projectName); //$NON-NLS-1$
        }
        if (outputPath != null)
        {
            tr.put("outputPath", outputPath); //$NON-NLS-1$
        }
        if (detectedKind != null)
        {
            tr.put("detectedKind", detectedKind); //$NON-NLS-1$
        }
        return tr.toJson();
    }

    /**
     * 1.41: holder for kind/extension resolution result.
     */
    private static final class KindResolution
    {
        String kindLabel;        // "ExternalDataProcessor" / "ExternalReport"
        String normalizedPath;   // outputPath with proper extension
        String error;
        String requestedExtension;
        String detectedFromNature;

        Map<String, Object> diagnosticTag()
        {
            Map<String, Object> m = new LinkedHashMap<>();
            if (requestedExtension != null)
            {
                m.put("requestedExtension", requestedExtension); //$NON-NLS-1$
            }
            if (detectedFromNature != null)
            {
                m.put("detectedFromNature", detectedFromNature); //$NON-NLS-1$
            }
            if (kindLabel != null)
            {
                m.put("expectedExtension", kindLabel.equals("ExternalReport") //$NON-NLS-1$ //$NON-NLS-2$
                    ? ".erf" : ".epf"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return m;
        }
    }

    /**
     * 1.41: resolve the export kind from the requested {@code outputPath}
     * extension (.epf or .erf) or, when missing, from the project nature.
     * Validates that the requested extension matches the detected kind.
     */
    private KindResolution resolveKind(IProject project, String outputPathRaw)
    {
        KindResolution kr = new KindResolution();
        String lower = outputPathRaw.toLowerCase();
        String detectedFromNature = detectKindFromNatures(project);
        kr.detectedFromNature = detectedFromNature;

        boolean hasEpf = lower.endsWith(".epf"); //$NON-NLS-1$
        boolean hasErf = lower.endsWith(".erf"); //$NON-NLS-1$

        if (hasEpf || hasErf)
        {
            kr.requestedExtension = hasEpf ? ".epf" : ".erf"; //$NON-NLS-1$ //$NON-NLS-2$
            kr.kindLabel = hasEpf ? "ExternalDataProcessor" : "ExternalReport"; //$NON-NLS-1$ //$NON-NLS-2$
            kr.normalizedPath = outputPathRaw;
            // If we detected a project nature, validate it matches.
            if (detectedFromNature != null && !detectedFromNature.equals(kr.kindLabel))
            {
                kr.error = "kindMismatch: outputPath has extension " //$NON-NLS-1$
                    + kr.requestedExtension + " but project '" //$NON-NLS-1$
                    + project.getName() + "' looks like " //$NON-NLS-1$
                    + detectedFromNature + " (use ." //$NON-NLS-1$
                    + (detectedFromNature.equals("ExternalReport") ? "erf" : "epf") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + " instead)"; //$NON-NLS-1$
            }
            return kr;
        }

        // 1.41: refuse paths whose filename already carries a non-.epf/.erf
        // extension, otherwise the auto-append below would silently produce
        // garbage like "y.txt.epf".
        int slash = Math.max(outputPathRaw.lastIndexOf('/'), outputPathRaw.lastIndexOf('\\'));
        String fileNamePart = slash >= 0 ? outputPathRaw.substring(slash + 1) : outputPathRaw;
        int dot = fileNamePart.lastIndexOf('.');
        if (dot > 0 && dot < fileNamePart.length() - 1)
        {
            String unknownExt = fileNamePart.substring(dot);
            kr.requestedExtension = unknownExt;
            kr.error = "Unsupported outputPath extension '" + unknownExt //$NON-NLS-1$
                + "'. Use .epf for ExternalDataProcessor or .erf for ExternalReport, " //$NON-NLS-1$
                + "or a path without an extension (auto-appended from project nature)."; //$NON-NLS-1$
            return kr;
        }

        // No extension: use detected kind to append it.
        if (detectedFromNature != null)
        {
            kr.kindLabel = detectedFromNature;
            kr.normalizedPath = outputPathRaw
                + (detectedFromNature.equals("ExternalReport") ? ".erf" : ".epf"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return kr;
        }

        kr.error = "Cannot determine export kind: outputPath has no .epf/.erf extension " //$NON-NLS-1$
            + "and the project nature did not reveal an ExternalDataProcessor / " //$NON-NLS-1$
            + "ExternalReport. Pass an outputPath with the proper extension."; //$NON-NLS-1$
        return kr;
    }

    /**
     * Probe project natures for {@code ExternalDataProcessor} or
     * {@code ExternalReport}. Returns the kind label or {@code null} when
     * the project is a regular configuration / extension / unknown.
     */
    private String detectKindFromNatures(IProject project)
    {
        try
        {
            String[] natures = project.getDescription().getNatureIds();
            for (String n : natures)
            {
                String lc = n.toLowerCase();
                // Order matters: test the longer/more specific substring first
                // to avoid false positives from a nature ID that happens to
                // contain "externalreport" as a sub-substring.
                if (lc.contains("externaldataprocessor")) //$NON-NLS-1$
                {
                    return "ExternalDataProcessor"; //$NON-NLS-1$
                }
                if (lc.contains("externalreport")) //$NON-NLS-1$
                {
                    return "ExternalReport"; //$NON-NLS-1$
                }
            }
        }
        catch (Exception ignored)
        {
            // project closed or in transient state - fall through to null
        }
        return null;
    }

    /**
     * 1.41: best-effort FQN for {@link BmExportHelper#forceExportAndWait}.
     * External processors / reports use kind-prefixed FQNs in EDT BM
     * (e.g. {@code ExternalDataProcessor.MyTool}).
     */
    private String inferFqnForKind(String kindLabel, String objectName)
    {
        if (kindLabel == null || objectName == null || objectName.isEmpty())
        {
            return null;
        }
        return kindLabel + "." + objectName; //$NON-NLS-1$
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
        // 1.41: unified under exportApiNotFound with phase=invocation so AI
        // clients can branch on a single tag name (probe vs invocation).
        tag.put("phase", "invocation"); //$NON-NLS-1$ //$NON-NLS-2$
        tag.put("discoveredApi", serviceClass.getName()); //$NON-NLS-1$
        tag.put("methodHints", describeMethods(serviceClass)); //$NON-NLS-1$
        tag.put("reason", reason); //$NON-NLS-1$
        tag.put("hint", //$NON-NLS-1$
            "Open the project in EDT and use File -> Export -> " //$NON-NLS-1$
                + "'External data processor / report' as a workaround."); //$NON-NLS-1$
        return ToolResult.error("Export failed: " + reason) //$NON-NLS-1$
            .put("operation", NAME) //$NON-NLS-1$
            .put("projectName", projectName) //$NON-NLS-1$
            .put("outputPath", outputPath) //$NON-NLS-1$
            .put("elapsedMs", elapsed) //$NON-NLS-1$
            .put("exportApiNotFound", tag) //$NON-NLS-1$
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
        java.util.List<String> tried = java.util.Arrays.asList(EXPORT_SERVICE_CANDIDATES);
        Map<String, Object> tag = new LinkedHashMap<>();
        tag.put("phase", "probe"); //$NON-NLS-1$ //$NON-NLS-2$
        tag.put("triedServices", tried); //$NON-NLS-1$
        tag.put("hint", //$NON-NLS-1$
            "Open the project in EDT and use File -> Export -> " //$NON-NLS-1$
                + "'External data processor / report' as a workaround."); //$NON-NLS-1$
        return ToolResult.error(
            "EDT export API not available in this EDT version. " //$NON-NLS-1$
                + "Probed " + EXPORT_SERVICE_CANDIDATES.length + " candidate services: " //$NON-NLS-1$ //$NON-NLS-2$
                + candidates + ". Use the EDT GUI fallback: " //$NON-NLS-1$
                + "File -> Export -> 'External data processor / report'." //$NON-NLS-1$
        )
            .put("operation", NAME) //$NON-NLS-1$
            .put("exportApiNotFound", tag) //$NON-NLS-1$
            .toJson();
    }
}
