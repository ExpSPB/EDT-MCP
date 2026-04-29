/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;

import com._1c.g5.v8.dt.validation.marker.IMarkerManager;
import com._1c.g5.v8.dt.validation.marker.MarkerSeverity;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;
import com.e1c.g5.v8.dt.check.settings.CheckUid;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.preferences.ToolParameterSettings;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.session.SessionChangeTracker;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Tool to get detailed project errors with optional filters.
 * Uses EDT IMarkerManager for accessing configuration problems.
 */
public class GetProjectErrorsTool implements IMcpTool
{
    public static final String NAME = "get_project_errors"; //$NON-NLS-1$
    
    @Override
    public String getName()
    {
        return NAME;
    }
    
    @Override
    public String getDescription()
    {
        return "Get detailed configuration problems from EDT. " + //$NON-NLS-1$
               "Returns check code, description, object location, severity level " + //$NON-NLS-1$
               "(ERRORS, BLOCKER, CRITICAL, MAJOR, MINOR, TRIVIAL). " + //$NON-NLS-1$
               "Scope (default 'session'): only files modified in the current MCP session - " + //$NON-NLS-1$
               "useful right after write_module_source / edit_metadata. " + //$NON-NLS-1$
               "scope=object requires 'objects'; scope=project / scope=all scan everything " + //$NON-NLS-1$
               "(scope=project auto-summarizes when over 200 markers). " + //$NON-NLS-1$
               "Can filter by specific objects using FQN (e.g. 'Document.SalesOrder', 'Catalog.Products'). " + //$NON-NLS-1$
               "Russian type names are also supported (e.g. 'Документ.ПриходнаяНакладная', 'Справочник.Номенклатура')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", "Filter by project name (optional)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("severity", "Filter by severity: ERRORS, BLOCKER, CRITICAL, MAJOR, MINOR, TRIVIAL (optional)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("checkId", "Filter by check ID substring (e.g. 'ql-temp-table-index') (optional)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringArrayProperty("objects", "Filter by object FQNs (e.g. ['Document.SalesOrder', 'Catalog.Products']). Russian type names supported (e.g. 'Документ.ПродажаТоваров'). Returns errors only from these objects. Implies scope=object when set.") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("limit", "Maximum number of results (default: 100, max: 1000)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("scope", //$NON-NLS-1$
                "Marker scope: 'session' (default - only files changed in current MCP session " + //$NON-NLS-1$
                "via SessionChangeTracker), 'object' (requires 'objects'), 'project' " + //$NON-NLS-1$
                "(all markers in project, auto-summarizes when over 200), " + //$NON-NLS-1$
                "'all' (every open project). Setting 'objects' implies scope=object.") //$NON-NLS-1$
            .stringProperty("fileFilter", //$NON-NLS-1$
                "Substring filter on marker objectPresentation, applied on top of scope. " + //$NON-NLS-1$
                "E.g. 'CommonModule.Common' to keep only common modules.") //$NON-NLS-1$
            .booleanProperty("waitForRefresh", //$NON-NLS-1$
                "If true (default true), poll the marker stream up to 3x300ms after an empty " + //$NON-NLS-1$
                "first read to give EDT time to publish freshly-computed markers. " + //$NON-NLS-1$
                "Disable for very large projects when latency matters more than freshness.") //$NON-NLS-1$
            .build();
    }
    
    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String severity = JsonUtils.extractStringArgument(params, "severity"); //$NON-NLS-1$
        String checkId = JsonUtils.extractStringArgument(params, "checkId"); //$NON-NLS-1$
        String objectsJson = JsonUtils.extractStringArgument(params, "objects"); //$NON-NLS-1$
        String scope = JsonUtils.extractStringArgument(params, "scope"); //$NON-NLS-1$
        String fileFilter = JsonUtils.extractStringArgument(params, "fileFilter"); //$NON-NLS-1$
        boolean waitForRefresh = JsonUtils.extractBooleanArgument(params, "waitForRefresh", true); //$NON-NLS-1$

        // Check if project is ready for operations
        if (projectName != null && !projectName.isEmpty())
        {
            String notReadyError = ProjectStateChecker.checkReadyOrError(projectName);
            if (notReadyError != null)
            {
                return ToolResult.error(notReadyError).toJson();
            }
        }

        // Parse objects filter
        List<String> objects = parseObjectsList(objectsJson);

        // Resolve scope: explicit "objects" => scope=object regardless of input.
        // Default scope = "session" - matches conventional behaviour and avoids returning
        // thousands of pre-existing markers from a stock configuration.
        if (scope == null || scope.isEmpty())
        {
            scope = !objects.isEmpty() ? "object" : "session"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        else
        {
            scope = scope.toLowerCase();
        }

        int defaultLimit = ToolParameterSettings.getInstance()
            .getParameterValue(NAME, "limit", 100); //$NON-NLS-1$

        int limit = JsonUtils.extractIntArgument(params, "limit", defaultLimit); //$NON-NLS-1$
        limit = Math.min(Math.max(1, limit), 1000);

        return getProjectErrors(projectName, severity, checkId, objects, limit,
            scope, fileFilter, waitForRefresh);
    }
    
    /**
     * Parses the objects array from JSON string using Gson JsonParser.
     * 
     * @param objectsJson JSON array string like ["Document.SalesOrder", "Catalog.Products"]
     * @return list of object FQNs
     */
    private List<String> parseObjectsList(String objectsJson)
    {
        List<String> result = new ArrayList<>();
        if (objectsJson == null || objectsJson.isEmpty())
        {
            return result;
        }
        
        try
        {
            JsonElement element = JsonParser.parseString(objectsJson);
            if (element.isJsonArray())
            {
                JsonArray array = element.getAsJsonArray();
                for (JsonElement item : array)
                {
                    if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString())
                    {
                        result.add(item.getAsString());
                    }
                }
            }
        }
        catch (JsonParseException e)
        {
            Activator.logError("Error parsing objects JSON: " + objectsJson, e); //$NON-NLS-1$
        }
        return result;
    }
    
    /**
     * Backward-compatible wrapper without scope parameters.
     * Equivalent to {@code scope="object"} when {@code objects} is non-empty,
     * otherwise {@code scope="all"} (legacy behaviour: no session filter).
     */
    public static String getProjectErrors(String projectName, String severity, String checkId,
        List<String> objects, int limit)
    {
        String scope = (objects != null && !objects.isEmpty()) ? "object" : "all"; //$NON-NLS-1$ //$NON-NLS-2$
        return getProjectErrors(projectName, severity, checkId, objects, limit, scope, null, false);
    }

    /**
     * Gets project errors with filters using EDT IMarkerManager.
     *
     * @param projectName filter by project name (null for all)
     * @param severity filter by severity (ERRORS, BLOCKER, CRITICAL, MAJOR, MINOR, TRIVIAL)
     * @param checkId filter by check ID substring
     * @param objects filter by object FQNs (empty list for all objects)
     * @param limit maximum number of results
     * @param scope marker scope: session / object / project / all
     * @param fileFilter substring filter on marker objectPresentation (null = no filter)
     * @param waitForRefresh poll markers up to 3x300ms when initial read is empty
     * @return Markdown formatted string with error details
     */
    public static String getProjectErrors(String projectName, String severity, String checkId,
        List<String> objects, int limit, String scope, String fileFilter, boolean waitForRefresh)
    {
        try
        {
            IMarkerManager markerManager = Activator.getDefault().getMarkerManager();
            
            if (markerManager == null)
            {
                return "# Error\n\nIMarkerManager service is not available"; //$NON-NLS-1$
            }
            
            ICheckRepository checkRepository = Activator.getDefault().getCheckRepository();
            
            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            
            // Parse severity filter
            MarkerSeverity severityFilter = null;
            if (severity != null && !severity.isEmpty())
            {
                try
                {
                    severityFilter = MarkerSeverity.valueOf(severity.toUpperCase());
                }
                catch (IllegalArgumentException e)
                {
                    // Invalid severity, will show all
                }
            }
            
            // Validate project if specified
            if (projectName != null && !projectName.isEmpty())
            {
                IProject project = workspace.getRoot().getProject(projectName);
                if (project == null || !project.exists())
                {
                    return "# Error\n\nProject not found: " + projectName; //$NON-NLS-1$
                }
            }
            
            // Resolve session scope short-circuit
            final String resolvedScope = scope != null ? scope.toLowerCase() : "all"; //$NON-NLS-1$
            if ("session".equals(resolvedScope) && SessionChangeTracker.size() == 0) //$NON-NLS-1$
            {
                return "# No Session Changes\n\n" //$NON-NLS-1$
                    + "scope=session and no files have been modified in the current MCP session.\n\n" //$NON-NLS-1$
                    + "Use scope=project for the full project scan, or write a module first " //$NON-NLS-1$
                    + "via write_module_source / edit_metadata."; //$NON-NLS-1$
            }

            // Build session FQN set for scope=session (FQNs derived from modified file paths)
            final Set<String> sessionFqns = "session".equals(resolvedScope) //$NON-NLS-1$
                ? buildSessionFqnSet()
                : null;

            // Collect errors from EDT MarkerManager using proper stream operations
            final MarkerSeverity finalSeverityFilter = severityFilter;
            final String finalCheckId = checkId;
            final String finalProjectName = projectName;
            final String finalFileFilter = fileFilter != null && !fileFilter.isEmpty()
                ? fileFilter.toLowerCase() : null;
            // Normalize object FQNs to support both English and Russian metadata type names.
            // For each input FQN, generate all variants (original + English + Russian, lowercased)
            // so we can match markers regardless of the configuration language.
            // Using Set for deduplication of variants.
            final Set<String> finalObjects = new HashSet<>();
            if (objects != null)
            {
                for (String fqn : objects)
                {
                    finalObjects.addAll(MetadataTypeUtils.getAllFqnVariants(fqn));
                }
            }
            
            // Marker predicate, reused across retry attempts.
            final ICheckRepository finalCheckRepo = checkRepository;
            java.util.function.Predicate<com._1c.g5.v8.dt.validation.marker.Marker> predicate = marker -> {
                // Get project
                IProject markerProject = marker.getProject();
                if (markerProject == null)
                {
                    return false;
                }

                // Check project filter
                if (finalProjectName != null && !finalProjectName.isEmpty() &&
                    !markerProject.getName().equals(finalProjectName))
                {
                    return false;
                }

                // Check severity filter
                MarkerSeverity markerSeverity = marker.getSeverity();
                if (finalSeverityFilter != null && markerSeverity != finalSeverityFilter)
                {
                    return false;
                }

                // Check checkId filter
                String markerCheckId = marker.getCheckId();
                if (finalCheckId != null && !finalCheckId.isEmpty())
                {
                    if (markerCheckId == null ||
                        !markerCheckId.toLowerCase().contains(finalCheckId.toLowerCase()))
                    {
                        return false;
                    }
                }

                String objectPresentation = marker.getObjectPresentation();
                String presentationLower = objectPresentation != null
                    ? objectPresentation.toLowerCase() : ""; //$NON-NLS-1$

                // Check objects filter (FQN matching)
                if (!finalObjects.isEmpty())
                {
                    if (presentationLower.isEmpty())
                    {
                        return false;
                    }
                    boolean matchesAnyObject = false;
                    for (String fqnVariant : finalObjects)
                    {
                        if (presentationLower.contains(fqnVariant))
                        {
                            matchesAnyObject = true;
                            break;
                        }
                    }
                    if (!matchesAnyObject)
                    {
                        return false;
                    }
                }

                // scope=session: keep markers whose objectPresentation contains
                // any session-modified FQN.
                if (sessionFqns != null)
                {
                    if (presentationLower.isEmpty())
                    {
                        return false;
                    }
                    boolean matchesSession = false;
                    for (String sessionFqn : sessionFqns)
                    {
                        if (presentationLower.contains(sessionFqn))
                        {
                            matchesSession = true;
                            break;
                        }
                    }
                    if (!matchesSession)
                    {
                        return false;
                    }
                }

                // fileFilter: extra substring filter on objectPresentation.
                if (finalFileFilter != null && !presentationLower.contains(finalFileFilter))
                {
                    return false;
                }

                return true;
            };

            // Retry loop: poll markers up to 3x300ms when first read returns empty
            // and waitForRefresh=true. Helps with EDT publishing markers asynchronously
            // right after a write_module_source / edit_metadata operation.
            List<ErrorInfo> errors = collectMarkers(markerManager, predicate, finalCheckRepo, limit);
            if (errors.isEmpty() && waitForRefresh)
            {
                for (int attempt = 0; attempt < 3 && errors.isEmpty(); attempt++)
                {
                    try
                    {
                        Thread.sleep(300);
                    }
                    catch (InterruptedException ie)
                    {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    errors = collectMarkers(markerManager, predicate, finalCheckRepo, limit);
                }
            }

            // scope=project safeguard: if total markers in project exceed 200, summarize
            // instead of returning a noisy list. Forces caller to narrow with checkId/object filter.
            if ("project".equals(resolvedScope) && errors.size() >= limit) //$NON-NLS-1$
            {
                long totalMarkers = markerManager.markers()
                    .filter(m -> {
                        IProject mp = m.getProject();
                        return mp != null && (finalProjectName == null
                            || finalProjectName.isEmpty()
                            || mp.getName().equals(finalProjectName));
                    })
                    .count();
                if (totalMarkers > 200)
                {
                    return "# Too Many Project Markers\n\n" //$NON-NLS-1$
                        + "**Total markers in project:** " + totalMarkers + "\n" //$NON-NLS-1$ //$NON-NLS-2$
                        + "**Returned:** " + errors.size() + " (limited)\n\n" //$NON-NLS-1$ //$NON-NLS-2$
                        + "Narrow the scope: pass `objects=[...]`, `checkId=...`, " //$NON-NLS-1$
                        + "or use `scope=session` for in-session changes only."; //$NON-NLS-1$
                }
            }

            return formatMarkers(errors, limit, projectName, severity, objects, resolvedScope);
        }
        catch (Exception e)
        {
            Activator.logError("Error getting project errors", e); //$NON-NLS-1$
            return "# Error\n\nFailed to get project errors: " + e.getMessage(); //$NON-NLS-1$
        }
    }

    /**
     * Builds a set of metadata FQN variants from {@link SessionChangeTracker} paths.
     * Each modified workspace path is parsed as {@code /Project/src/Type/Name/...}
     * and converted to {@code Type.Name} (with English/Russian variants).
     */
    private static Set<String> buildSessionFqnSet()
    {
        Set<String> result = new HashSet<>();
        for (String path : SessionChangeTracker.getModifiedPaths())
        {
            // Path looks like "/ProjectName/src/Documents/MyDoc/ObjectModule.bsl"
            String normalized = path.replace('\\', '/');
            int srcIdx = normalized.indexOf("/src/"); //$NON-NLS-1$
            if (srcIdx < 0)
            {
                continue;
            }
            String afterSrc = normalized.substring(srcIdx + 5);
            String[] parts = afterSrc.split("/"); //$NON-NLS-1$
            if (parts.length < 2)
            {
                continue;
            }
            String englishType = MetadataTypeUtils.getTypeByDirectoryName(parts[0]);
            if (englishType == null)
            {
                continue;
            }
            String fqn = englishType + "." + parts[1]; //$NON-NLS-1$
            result.addAll(MetadataTypeUtils.getAllFqnVariants(fqn));
        }
        return result;
    }

    /**
     * Runs the marker stream once with the given predicate and projects to
     * {@link ErrorInfo}.
     */
    private static List<ErrorInfo> collectMarkers(IMarkerManager markerManager,
        java.util.function.Predicate<com._1c.g5.v8.dt.validation.marker.Marker> predicate,
        ICheckRepository checkRepo, int limit)
    {
        return markerManager.markers()
            .filter(predicate)
            .limit(limit)
            .map(marker -> {
                ErrorInfo error = new ErrorInfo();
                String shortUid = marker.getCheckId() != null ? marker.getCheckId() : ""; //$NON-NLS-1$
                error.checkCode = shortUid;

                // Try to convert short UID (e.g. "SU23") to symbolic check ID
                // (e.g. "bsl-legacy-check-expression-type")
                if (checkRepo != null && !shortUid.isEmpty() && marker.getProject() != null)
                {
                    try
                    {
                        CheckUid checkUid = checkRepo.getUidForShortUid(shortUid, marker.getProject());
                        if (checkUid != null)
                        {
                            error.checkId = checkUid.getCheckId();
                        }
                    }
                    catch (Exception e)
                    {
                        // Ignore - will use short UID instead
                    }
                }

                // Check if documentation exists for this check
                error.hasDocumentation = false;
                if (error.checkId != null && !error.checkId.isEmpty())
                {
                    error.hasDocumentation = GetCheckDescriptionTool.hasCheckDocumentation(error.checkId);
                }

                error.message = marker.getMessage() != null ? marker.getMessage() : ""; //$NON-NLS-1$
                error.objectPresentation = marker.getObjectPresentation() != null
                    ? marker.getObjectPresentation() : ""; //$NON-NLS-1$
                return error;
            })
            .collect(Collectors.toList());
    }

    /**
     * Renders the collected markers as a markdown report.
     */
    private static String formatMarkers(List<ErrorInfo> errors, int limit, String projectName,
        String severity, List<String> objects, String resolvedScope)
    {
        StringBuilder md = new StringBuilder();
        if (errors.isEmpty())
        {
            md.append("# No Errors Found\n\n"); //$NON-NLS-1$
            if (resolvedScope != null && !resolvedScope.isEmpty())
            {
                md.append("Scope: **").append(resolvedScope).append("**\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (projectName != null && !projectName.isEmpty())
            {
                md.append("Project: **").append(projectName).append("**\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (severity != null && !severity.isEmpty())
            {
                md.append("Severity filter: ").append(severity).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (objects != null && !objects.isEmpty())
            {
                md.append("Objects filter: ").append(String.join(", ", objects)).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            md.append("\nNo configuration problems match the specified criteria."); //$NON-NLS-1$
            return md.toString();
        }

        md.append("# Configuration Problems\n\n"); //$NON-NLS-1$
        md.append("**Scope:** ").append(resolvedScope != null ? resolvedScope : "all").append("\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        md.append("**Found:** ").append(errors.size()); //$NON-NLS-1$
        if (errors.size() >= limit)
        {
            md.append("+ (limited to ").append(limit).append(")"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        md.append("\n\n"); //$NON-NLS-1$

        // Build table matching EDT's Configuration Problems view
        md.append("| Description | Location | Check code | Has docs |\n"); //$NON-NLS-1$
        md.append("|-------------|----------|------------|----------|\n"); //$NON-NLS-1$

        for (ErrorInfo error : errors)
        {
            md.append("| ").append(MarkdownUtils.escapeForTable(error.message)); //$NON-NLS-1$
            md.append(" | ").append(MarkdownUtils.escapeForTable(error.objectPresentation)); //$NON-NLS-1$

            // Show symbolic check ID if available, otherwise show check code
            String displayCheckId = error.checkId != null && !error.checkId.isEmpty()
                ? error.checkId
                : error.checkCode;
            md.append(" | `").append(displayCheckId).append("`"); //$NON-NLS-1$ //$NON-NLS-2$

            // Add documentation availability flag
            md.append(" | ").append(error.hasDocumentation ? "true" : "false").append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
        return md.toString();
    }
    
    /**
     * Helper class to store error info.
     */
    private static class ErrorInfo
    {
        String checkCode;          // Short UID like "SU23"
        String checkId;            // Symbolic ID like "bsl-legacy-check-expression-type"
        String message;
        String objectPresentation;
        boolean hasDocumentation;  // Whether documentation exists for this check
    }
}
