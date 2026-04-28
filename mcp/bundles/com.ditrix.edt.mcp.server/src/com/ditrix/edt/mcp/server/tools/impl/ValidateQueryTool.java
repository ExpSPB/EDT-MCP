/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.swt.widgets.Display;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.resource.IResourceSetProvider;
import org.eclipse.xtext.util.CancelIndicator;
import org.eclipse.xtext.validation.CheckMode;
import org.eclipse.xtext.validation.IResourceValidator;
import org.eclipse.xtext.validation.Issue;

import com._1c.g5.v8.dt.ql.dcs.resource.QlDcsResource;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Tool to validate 1C:Enterprise query language (QL) text in the context of a project.
 * Uses Xtext QL infrastructure to parse and validate query text, returning syntax and
 * semantic errors with line numbers and messages.
 */
public class ValidateQueryTool implements IMcpTool
{
    public static final String NAME = "validate_query"; //$NON-NLS-1$
    
    /** URI used to look up the QlDcs language IResourceServiceProvider */
    private static final URI QLDCS_LOOKUP_URI = URI.createURI("/nopr/querywizard_validate.qldcs"); //$NON-NLS-1$
    
    @Override
    public String getName()
    {
        return NAME;
    }
    
    @Override
    public String getDescription()
    {
        return "Validate 1C:Enterprise query language (QL) text in the context of a project. " + //$NON-NLS-1$
               "Parses query text and returns syntax and semantic errors with line numbers. " + //$NON-NLS-1$
               "Supports both regular queries and DCS (Data Composition System) queries."; //$NON-NLS-1$
    }
    
    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("queryText", //$NON-NLS-1$
                "Query text to validate (required). The full text of the 1C query, e.g. " + //$NON-NLS-1$
                "'SELECT Ref FROM Catalog.Products WHERE Description LIKE &SearchString'", true) //$NON-NLS-1$
            .booleanProperty("dcsMode", //$NON-NLS-1$
                "DCS (Data Composition System) mode. Set to true for queries used in " + //$NON-NLS-1$
                "data composition schemas. Allows additional DCS-specific syntax. Default: false") //$NON-NLS-1$
            .booleanProperty("fix", //$NON-NLS-1$
                "Auto-fix obvious syntax errors (1.39): keyword typos (ВЫБРАТ -> ВЫБРАТЬ), " //$NON-NLS-1$
                    + "empty trailing WHERE removal. Conservative — never changes semantics. " //$NON-NLS-1$
                    + "Returns fixedQuery in response. Default: false.") //$NON-NLS-1$
            .booleanProperty("fixedOnly", //$NON-NLS-1$
                "When fix=true, return only the fixed query text without diagnostics. Default: false.") //$NON-NLS-1$
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
        String queryText = JsonUtils.extractStringArgument(params, "queryText"); //$NON-NLS-1$
        boolean dcsMode = JsonUtils.extractBooleanArgument(params, "dcsMode", false); //$NON-NLS-1$
        boolean fix = JsonUtils.extractBooleanArgument(params, "fix", false); //$NON-NLS-1$
        boolean fixedOnly = JsonUtils.extractBooleanArgument(params, "fixedOnly", false); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required").toJson(); //$NON-NLS-1$
        }

        if (queryText == null || queryText.isEmpty())
        {
            return ToolResult.error("queryText is required").toJson(); //$NON-NLS-1$
        }

        // Find the project
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
        }
        if (!project.isOpen())
        {
            return ToolResult.error("Project is closed: " + projectName).toJson(); //$NON-NLS-1$
        }

        // 1.39: when fix=true apply conservative auto-fixes BEFORE validation
        if (fix)
        {
            QueryFixResult fixResult = applyConservativeFixes(queryText);
            if (fixedOnly)
            {
                return ToolResult.success()
                    .put("fix", true) //$NON-NLS-1$
                    .put("fixedQuery", fixResult.fixedQuery) //$NON-NLS-1$
                    .put("fixesApplied", fixResult.fixesApplied) //$NON-NLS-1$
                    .toJson();
            }
            // Validate the fixed query and combine
            String validation = validateQuery(project, fixResult.fixedQuery, dcsMode);
            try
            {
                com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(validation)
                    .getAsJsonObject();
                obj.addProperty("fix", true); //$NON-NLS-1$
                obj.addProperty("fixedQuery", fixResult.fixedQuery); //$NON-NLS-1$
                obj.addProperty("originalQuery", queryText); //$NON-NLS-1$
                com.google.gson.JsonArray applied = new com.google.gson.JsonArray();
                for (Map<String, String> entry : fixResult.fixesApplied)
                {
                    com.google.gson.JsonObject e = new com.google.gson.JsonObject();
                    for (Map.Entry<String, String> kv : entry.entrySet())
                    {
                        e.addProperty(kv.getKey(), kv.getValue());
                    }
                    applied.add(e);
                }
                obj.add("fixesApplied", applied); //$NON-NLS-1$
                obj.addProperty("fixable", !fixResult.fixesApplied.isEmpty()); //$NON-NLS-1$
                return obj.toString();
            }
            catch (Exception e)
            {
                return validation;
            }
        }

        return validateQuery(project, queryText, dcsMode);
    }

    /**
     * Container for fix results.
     */
    private static class QueryFixResult
    {
        String fixedQuery;
        List<Map<String, String>> fixesApplied = new ArrayList<>();
    }

    /**
     * Conservative auto-fix transformers. Each must NEVER change semantics.
     * Returns the (possibly fixed) query plus a list of applied fixes.
     */
    private static QueryFixResult applyConservativeFixes(String queryText)
    {
        QueryFixResult result = new QueryFixResult();
        String current = queryText;
        // 1) Keyword typo fixes - exact-match replacement of common typos
        Map<String, String> typos = new java.util.LinkedHashMap<>();
        typos.put("ВЫБРАТ ", "ВЫБРАТЬ "); //$NON-NLS-1$ //$NON-NLS-2$
        typos.put("ВЫБРОТЬ ", "ВЫБРАТЬ "); //$NON-NLS-1$ //$NON-NLS-2$
        typos.put("ГЬДЕ ", "ГДЕ "); //$NON-NLS-1$ //$NON-NLS-2$
        typos.put("ГДЕЕ ", "ГДЕ "); //$NON-NLS-1$ //$NON-NLS-2$
        typos.put("СОЕДЕНИ", "СОЕДИНЕНИ"); //$NON-NLS-1$ //$NON-NLS-2$
        typos.put("ЛЕВО СОЕД", "ЛЕВОЕ СОЕД"); //$NON-NLS-1$ //$NON-NLS-2$
        typos.put("ОБЪЕДИНИ ", "ОБЪЕДИНИТЬ "); //$NON-NLS-1$ //$NON-NLS-2$
        typos.put("ИСТЬ ", "ЕСТЬ "); //$NON-NLS-1$ //$NON-NLS-2$
        typos.put("УПОРЯДОЧИТ ", "УПОРЯДОЧИТЬ "); //$NON-NLS-1$ //$NON-NLS-2$
        typos.put("СГРУПИРОВАТЬ", "СГРУППИРОВАТЬ"); //$NON-NLS-1$ //$NON-NLS-2$
        typos.put("ИМЕЮЩИИ ", "ИМЕЮЩИЕ "); //$NON-NLS-1$ //$NON-NLS-2$
        for (Map.Entry<String, String> entry : typos.entrySet())
        {
            if (current.contains(entry.getKey()))
            {
                current = current.replace(entry.getKey(), entry.getValue());
                Map<String, String> fix = new java.util.LinkedHashMap<>();
                fix.put("rule", "TYPO_KEYWORD"); //$NON-NLS-1$ //$NON-NLS-2$
                fix.put("from", entry.getKey().trim()); //$NON-NLS-1$
                fix.put("to", entry.getValue().trim()); //$NON-NLS-1$
                result.fixesApplied.add(fix);
            }
        }
        // 2) Empty trailing WHERE - "ВЫБРАТЬ ... ИЗ X ГДЕ" with nothing after WHERE
        java.util.regex.Pattern emptyWhere = java.util.regex.Pattern
            .compile("(\\bГДЕ\\s*$|\\bWHERE\\s*$)", //$NON-NLS-1$
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher mEmpty = emptyWhere.matcher(current.trim());
        if (mEmpty.find())
        {
            current = current.substring(0, current.length() - mEmpty.group().length()).trim();
            Map<String, String> fix = new java.util.LinkedHashMap<>();
            fix.put("rule", "EMPTY_WHERE"); //$NON-NLS-1$ //$NON-NLS-2$
            fix.put("description", "Removed empty trailing WHERE/ГДЕ"); //$NON-NLS-1$ //$NON-NLS-2$
            result.fixesApplied.add(fix);
        }
        result.fixedQuery = current;
        return result;
    }
    
    /**
     * Validates query text against the project context.
     * Must run on the UI thread for proper Xtext resource access.
     * 
     * @param project the project context
     * @param queryText the query text to validate
     * @param dcsMode whether to use DCS validation mode
     * @return JSON result with validation errors
     */
    private String validateQuery(IProject project, String queryText, boolean dcsMode)
    {
        final AtomicReference<String> resultRef = new AtomicReference<>();
        
        Display display = Display.getDefault();
        if (Display.getCurrent() != null)
        {
            // Already on UI thread
            resultRef.set(doValidateQuery(project, queryText, dcsMode));
        }
        else
        {
            display.syncExec(() -> resultRef.set(doValidateQuery(project, queryText, dcsMode)));
        }
        
        return resultRef.get();
    }
    
    /**
     * Performs the actual validation. Executed on UI thread.
     * 
     * @param project the project context
     * @param queryText the query text to validate
     * @param dcsMode whether to use DCS validation mode
     * @return JSON result with validation errors
     */
    private String doValidateQuery(IProject project, String queryText, boolean dcsMode)
    {
        XtextResource resource = null;
        try
        {
            // Get the QlDcs language resource service provider
            IResourceServiceProvider resourceServiceProvider =
                IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(QLDCS_LOOKUP_URI);
            
            if (resourceServiceProvider == null)
            {
                return ToolResult.error("QlDcs language support not available. " + //$NON-NLS-1$
                    "Please ensure the QL plugin is installed.").toJson(); //$NON-NLS-1$
            }
            
            // Get IResourceSetProvider for project context
            IResourceSetProvider resourceSetProvider =
                resourceServiceProvider.get(IResourceSetProvider.class);
            
            if (resourceSetProvider == null)
            {
                return ToolResult.error("Failed to get resource set provider").toJson(); //$NON-NLS-1$
            }
            
            // Create resource set bound to the project
            ResourceSet resourceSet = resourceSetProvider.get(project);
            
            // Create a unique URI for this validation resource
            URI resourceUri = URI.createPlatformResourceURI(
                "/" + project.getName() + "/mcp_validate_query_" + System.currentTimeMillis() + ".qldcs", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                true);
            
            // Create the Xtext resource
            resource = (XtextResource) resourceSet.createResource(resourceUri);
            
            // Configure QlDcsResource if applicable
            if (resource instanceof QlDcsResource)
            {
                QlDcsResource qlResource = (QlDcsResource) resource;
                qlResource.addOptions("DcsValidationModeOption", dcsMode); //$NON-NLS-1$
                qlResource.setPreComputeAnnounceAlias(dcsMode);
            }
            
            // Load the query text into the resource
            try (InputStream inputStream = new ByteArrayInputStream(
                queryText.getBytes(StandardCharsets.UTF_8)))
            {
                resource.load(inputStream, null);
            }
            
            // Get the resource validator
            IResourceValidator validator = resourceServiceProvider.get(IResourceValidator.class);
            if (validator == null)
            {
                return ToolResult.error("Failed to get resource validator").toJson(); //$NON-NLS-1$
            }
            
            // Collect syntax errors from resource diagnostics
            List<QueryIssue> issues = new ArrayList<>();
            
            for (Resource.Diagnostic error : resource.getErrors())
            {
                issues.add(new QueryIssue(
                    "ERROR", //$NON-NLS-1$
                    error.getMessage(),
                    error.getLine(),
                    error.getColumn(),
                    -1
                ));
            }
            
            for (Resource.Diagnostic warning : resource.getWarnings())
            {
                issues.add(new QueryIssue(
                    "WARNING", //$NON-NLS-1$
                    warning.getMessage(),
                    warning.getLine(),
                    warning.getColumn(),
                    -1
                ));
            }
            
            // Run full validation (semantic checks)
            List<Issue> validationIssues = validator.validate(resource, CheckMode.ALL, CancelIndicator.NullImpl);
            
            for (Issue issue : validationIssues)
            {
                String severity;
                switch (issue.getSeverity())
                {
                    case ERROR:
                        severity = "ERROR"; //$NON-NLS-1$
                        break;
                    case WARNING:
                        severity = "WARNING"; //$NON-NLS-1$
                        break;
                    case INFO:
                        severity = "INFO"; //$NON-NLS-1$
                        break;
                    default:
                        severity = "WARNING"; //$NON-NLS-1$
                        break;
                }
                
                issues.add(new QueryIssue(
                    severity,
                    issue.getMessage(),
                    issue.getLineNumber() != null ? issue.getLineNumber() : -1,
                    issue.getColumn() != null ? issue.getColumn() : -1,
                    issue.getOffset() != null ? issue.getOffset() : -1
                ));
            }
            
            // Build the result
            return buildResult(queryText, issues, dcsMode);
        }
        catch (IOException e)
        {
            Activator.logError("Error loading query text into resource", e); //$NON-NLS-1$
            return ToolResult.error("Failed to load query text: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("Error validating query", e); //$NON-NLS-1$
            return ToolResult.error("Validation error: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
        finally
        {
            // Clean up the resource
            if (resource != null)
            {
                try
                {
                    ResourceSet resourceSet = resource.getResourceSet();
                    resource.unload();
                    if (resourceSet != null)
                    {
                        resourceSet.getResources().remove(resource);
                    }
                }
                catch (Exception e)
                {
                    Activator.logError("Error cleaning up validation resource", e); //$NON-NLS-1$
                }
            }
        }
    }
    
    /**
     * Builds JSON result from validation issues.
     * 
     * @param queryText the original query text
     * @param issues list of validation issues
     * @param dcsMode whether DCS mode was used
     * @return JSON result string
     */
    private String buildResult(String queryText, List<QueryIssue> issues, boolean dcsMode)
    {
        JsonObject result = new JsonObject();
        result.addProperty("success", true); //$NON-NLS-1$
        result.addProperty("valid", issues.isEmpty()); //$NON-NLS-1$
        result.addProperty("dcsMode", dcsMode); //$NON-NLS-1$
        result.addProperty("errorCount", //$NON-NLS-1$
            issues.stream().filter(i -> "ERROR".equals(i.severity)).count()); //$NON-NLS-1$
        result.addProperty("warningCount", //$NON-NLS-1$
            issues.stream().filter(i -> "WARNING".equals(i.severity)).count()); //$NON-NLS-1$
        result.addProperty("infoCount", //$NON-NLS-1$
            issues.stream().filter(i -> "INFO".equals(i.severity)).count()); //$NON-NLS-1$
        
        if (!issues.isEmpty())
        {
            JsonArray issuesArray = new JsonArray();
            for (QueryIssue issue : issues)
            {
                JsonObject issueObj = new JsonObject();
                issueObj.addProperty("severity", issue.severity); //$NON-NLS-1$
                issueObj.addProperty("message", issue.message); //$NON-NLS-1$
                if (issue.line > 0)
                {
                    issueObj.addProperty("line", issue.line); //$NON-NLS-1$
                }
                if (issue.column > 0)
                {
                    issueObj.addProperty("column", issue.column); //$NON-NLS-1$
                }
                if (issue.offset >= 0)
                {
                    issueObj.addProperty("offset", issue.offset); //$NON-NLS-1$
                }
                issuesArray.add(issueObj);
            }
            result.add("issues", issuesArray); //$NON-NLS-1$
        }
        else
        {
            result.add("issues", new JsonArray()); //$NON-NLS-1$
        }
        
        return result.toString();
    }
    
    /**
     * Internal representation of a validation issue.
     */
    private static class QueryIssue
    {
        final String severity;
        final String message;
        final int line;
        final int column;
        final int offset;
        
        QueryIssue(String severity, String message, int line, int column, int offset)
        {
            this.severity = severity;
            this.message = message;
            this.line = line;
            this.column = column;
            this.offset = offset;
        }
    }
}
