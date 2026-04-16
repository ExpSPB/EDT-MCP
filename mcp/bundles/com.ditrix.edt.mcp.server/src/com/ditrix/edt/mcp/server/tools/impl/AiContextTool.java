/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.bsl.model.Function;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.Pragma;
import com._1c.g5.v8.dt.bsl.model.RegionPreprocessor;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.metadata.MetadataFormatterRegistry;
import com.ditrix.edt.mcp.server.utils.FrontMatter;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;

/**
 * Context aggregator tool that collects metadata, modules, structure, and source code
 * for a 1C metadata object or module in a single call.
 * <p>
 * Combines functionality of get_metadata_details, list_modules, and get_module_structure
 * to reduce round-trips when investigating a metadata object.
 */
public class AiContextTool implements IMcpTool
{
    public static final String NAME = "ai_context"; //$NON-NLS-1$

    private static final int MAX_RECURSION_DEPTH = 20;
    private static final int DEFAULT_MAX_METHODS = 30;

    /**
     * Classification of the target parameter.
     */
    private enum TargetType
    {
        /** FQN like "Catalog.Products" or "Document.SalesOrder" */
        METADATA_OBJECT,
        /** FQN like "CommonModule.MyModule" */
        COMMON_MODULE,
        /** Path like "CommonModules/MyModule/Module.bsl" */
        MODULE_PATH
    }

    /**
     * Information about a discovered BSL module.
     */
    private static class ModuleInfo
    {
        String relativePath; // from src/
        String moduleType;   // ObjectModule, ManagerModule, FormModule, etc.
        IFile file;
        int lineCount;
    }

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Aggregate context about a 1C metadata object or module in one call. " + //$NON-NLS-1$
               "Returns metadata details, list of BSL modules, and module structure. " + //$NON-NLS-1$
               "Combines get_metadata_details + list_modules + get_module_structure " + //$NON-NLS-1$
               "to reduce round-trips. Use 'depth' to control detail level."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("target", //$NON-NLS-1$
                "FQN like 'Catalog.Products', 'CommonModule.MyModule', " + //$NON-NLS-1$
                "or path like 'CommonModules/MyModule/Module.bsl'. " + //$NON-NLS-1$
                "Russian type names supported (e.g. '\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A.\u041D\u043E\u043C\u0435\u043D\u043A\u043B\u0430\u0442\u0443\u0440\u0430'). Required.", //$NON-NLS-1$
                true)
            .stringProperty("depth", //$NON-NLS-1$
                "Detail level: 'minimal' (metadata + module list), " + //$NON-NLS-1$
                "'standard' (+ method structure, default), " + //$NON-NLS-1$
                "'full' (+ source code)") //$NON-NLS-1$
            .stringProperty("focusMethod", //$NON-NLS-1$
                "Method name for detailed analysis - only this method's source is included in 'full' mode") //$NON-NLS-1$
            .booleanProperty("includeSource", //$NON-NLS-1$
                "Include source code regardless of depth. Default: false for minimal/standard, true for full") //$NON-NLS-1$
            .integerProperty("maxMethods", //$NON-NLS-1$
                "Max methods to show per module in structure. Default: 30") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public String getResultFileName(Map<String, String> params)
    {
        String target = JsonUtils.extractStringArgument(params, "target"); //$NON-NLS-1$
        if (target != null && !target.isEmpty())
        {
            String safeName = target.replace("/", "-").replace("\\", "-") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                .replace(".", "-").toLowerCase(); //$NON-NLS-1$ //$NON-NLS-2$
            return "ai-context-" + safeName + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "ai-context.md"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String target = JsonUtils.extractStringArgument(params, "target"); //$NON-NLS-1$
        String depth = JsonUtils.extractStringArgument(params, "depth"); //$NON-NLS-1$
        String focusMethod = JsonUtils.extractStringArgument(params, "focusMethod"); //$NON-NLS-1$
        boolean includeSource = JsonUtils.extractBooleanArgument(params, "includeSource", false); //$NON-NLS-1$
        int maxMethods = JsonUtils.extractIntArgument(params, "maxMethods", DEFAULT_MAX_METHODS); //$NON-NLS-1$

        // Validate required parameters
        if (projectName == null || projectName.isEmpty())
        {
            return "Error: projectName is required"; //$NON-NLS-1$
        }
        if (target == null || target.isEmpty())
        {
            return "Error: target is required. Examples: 'Catalog.Products', " + //$NON-NLS-1$
                   "'CommonModule.MyModule', 'CommonModules/MyModule/Module.bsl'"; //$NON-NLS-1$
        }

        // Normalize depth
        if (depth == null || depth.isEmpty())
        {
            depth = "standard"; //$NON-NLS-1$
        }
        depth = depth.toLowerCase();
        if (!"minimal".equals(depth) && !"standard".equals(depth) && !"full".equals(depth)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            depth = "standard"; //$NON-NLS-1$
        }

        // For full depth, include source by default
        if ("full".equals(depth)) //$NON-NLS-1$
        {
            includeSource = true;
        }

        maxMethods = Math.max(1, Math.min(maxMethods, 200));

        // Classify target
        TargetType targetType = classifyTarget(target);

        // Execute on UI thread
        AtomicReference<String> resultRef = new AtomicReference<>();
        final String depthFinal = depth;
        final boolean includeSourceFinal = includeSource;
        final int maxMethodsFinal = maxMethods;
        final TargetType targetTypeFinal = targetType;

        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> {
            try
            {
                String result = executeInternal(projectName, target, targetTypeFinal,
                    depthFinal, focusMethod, includeSourceFinal, maxMethodsFinal);
                resultRef.set(result);
            }
            catch (Exception e)
            {
                Activator.logError("Error in ai_context", e); //$NON-NLS-1$
                resultRef.set("Error: " + e.getMessage()); //$NON-NLS-1$
            }
        });

        return resultRef.get();
    }

    // ========== Target classification ==========

    /**
     * Classifies the target string into one of the three target types.
     */
    private TargetType classifyTarget(String target)
    {
        // Path-based target
        if (target.contains("/") || target.endsWith(".bsl")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return TargetType.MODULE_PATH;
        }

        // Split by dot for FQN analysis
        int dotIdx = target.indexOf('.');
        if (dotIdx > 0)
        {
            String typePart = target.substring(0, dotIdx);
            String normalized = MetadataTypeUtils.toEnglishSingular(typePart);
            if ("CommonModule".equals(normalized)) //$NON-NLS-1$
            {
                return TargetType.COMMON_MODULE;
            }
        }

        // Default: metadata object (Type.Name)
        return TargetType.METADATA_OBJECT;
    }

    // ========== Main execution ==========

    private String executeInternal(String projectName, String target, TargetType targetType,
        String depth, String focusMethod, boolean includeSource, int maxMethods)
    {
        // Resolve project
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return "Error: Project not found: " + projectName; //$NON-NLS-1$
        }

        StringBuilder md = new StringBuilder();
        FrontMatter fm = FrontMatter.create()
            .put("tool", NAME) //$NON-NLS-1$
            .put("target", target) //$NON-NLS-1$
            .put("targetType", targetType.name()) //$NON-NLS-1$
            .put("depth", depth) //$NON-NLS-1$
            .put("projectName", projectName); //$NON-NLS-1$

        // Parse target parts
        String mdTypeName = null;
        String mdObjectName = null;
        String directoryName = null;

        if (targetType == TargetType.METADATA_OBJECT || targetType == TargetType.COMMON_MODULE)
        {
            int dotIdx = target.indexOf('.');
            if (dotIdx <= 0)
            {
                return "Error: Invalid FQN: " + target + ". Expected format: Type.Name"; //$NON-NLS-1$ //$NON-NLS-2$
            }
            mdTypeName = target.substring(0, dotIdx);
            mdObjectName = target.substring(dotIdx + 1);

            // Normalize type
            String normalized = MetadataTypeUtils.toEnglishSingular(mdTypeName);
            if (normalized != null)
            {
                mdTypeName = normalized;
            }
            directoryName = MetadataTypeUtils.getDirectoryName(mdTypeName);
        }

        md.append("# Context: ").append(target).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        // ---- METADATA section ----
        if (targetType != TargetType.MODULE_PATH)
        {
            appendMetadata(md, project, mdTypeName, mdObjectName, fm);
        }

        // ---- MODULES section ----
        List<ModuleInfo> modules = discoverModules(project, targetType, target,
            directoryName, mdObjectName);
        appendModulesList(md, modules, fm);

        // ---- STRUCTURE section (standard+) ----
        if (!"minimal".equals(depth)) //$NON-NLS-1$
        {
            int totalMethods = 0;
            for (ModuleInfo module : modules)
            {
                totalMethods += appendModuleStructure(md, project, module, maxMethods);
            }
            fm.put("totalMethods", totalMethods); //$NON-NLS-1$
        }

        // ---- SOURCE section (full or includeSource) ----
        if (includeSource)
        {
            appendSourceCode(md, modules, focusMethod);
        }

        return fm.wrapContent(md.toString());
    }

    // ========== Metadata ==========

    /**
     * Appends metadata details for the target object.
     */
    private void appendMetadata(StringBuilder md, IProject project, String mdType,
        String mdName, FrontMatter fm)
    {
        IConfigurationProvider configProvider = Activator.getDefault().getConfigurationProvider();
        if (configProvider == null)
        {
            md.append("## Metadata\n\n"); //$NON-NLS-1$
            md.append("*Configuration provider not available*\n\n"); //$NON-NLS-1$
            return;
        }

        Configuration config = configProvider.getConfiguration(project);
        if (config == null)
        {
            md.append("## Metadata\n\n"); //$NON-NLS-1$
            md.append("*Could not get configuration for project*\n\n"); //$NON-NLS-1$
            return;
        }

        // Determine language
        String language = "ru"; //$NON-NLS-1$
        if (config.getDefaultLanguage() != null)
        {
            language = config.getDefaultLanguage().getName();
        }

        MdObject mdObject = MetadataTypeUtils.findObject(config, mdType, mdName);
        if (mdObject == null)
        {
            md.append("## Metadata\n\n"); //$NON-NLS-1$
            md.append("*Object not found: ").append(mdType).append(".").append(mdName).append("*\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

            // Suggest similar names
            List<String> similar = MetadataTypeUtils.findSimilarObjects(config, mdType, mdName, 5);
            if (!similar.isEmpty())
            {
                md.append("**Similar objects:** "); //$NON-NLS-1$
                for (int i = 0; i < similar.size(); i++)
                {
                    if (i > 0)
                    {
                        md.append(", "); //$NON-NLS-1$
                    }
                    md.append(similar.get(i));
                }
                md.append("\n\n"); //$NON-NLS-1$
            }
            return;
        }

        md.append("## Metadata\n\n"); //$NON-NLS-1$
        String formatted = MetadataFormatterRegistry.format(mdObject, true, language);
        md.append(formatted);
        md.append("\n"); //$NON-NLS-1$
    }

    // ========== Module discovery ==========

    /**
     * Discovers BSL modules for the target.
     */
    private List<ModuleInfo> discoverModules(IProject project, TargetType targetType,
        String target, String directoryName, String objectName)
    {
        List<ModuleInfo> modules = new ArrayList<>();

        switch (targetType)
        {
            case MODULE_PATH:
                discoverSingleModule(project, modules, target);
                break;

            case COMMON_MODULE:
                discoverCommonModuleModules(project, modules, objectName);
                break;

            case METADATA_OBJECT:
                if (directoryName != null && objectName != null)
                {
                    discoverObjectModules(project, modules, directoryName, objectName);
                }
                break;
        }

        return modules;
    }

    /**
     * Discovers a single module by path.
     */
    private void discoverSingleModule(IProject project, List<ModuleInfo> modules, String modulePath)
    {
        IFile file = project.getFile(new Path("src").append(modulePath)); //$NON-NLS-1$
        if (file.exists())
        {
            ModuleInfo info = new ModuleInfo();
            info.relativePath = modulePath;
            info.moduleType = determineModuleType(modulePath);
            info.file = file;
            info.lineCount = countLines(file);
            modules.add(info);
        }
    }

    /**
     * Discovers modules for a common module (just Module.bsl).
     */
    private void discoverCommonModuleModules(IProject project, List<ModuleInfo> modules,
        String moduleName)
    {
        String path = "CommonModules/" + moduleName + "/Module.bsl"; //$NON-NLS-1$ //$NON-NLS-2$
        IFile file = project.getFile(new Path("src").append(path)); //$NON-NLS-1$
        if (file.exists())
        {
            ModuleInfo info = new ModuleInfo();
            info.relativePath = path;
            info.moduleType = "Module"; //$NON-NLS-1$
            info.file = file;
            info.lineCount = countLines(file);
            modules.add(info);
        }
    }

    /**
     * Discovers all BSL modules under a metadata object directory.
     */
    private void discoverObjectModules(IProject project, List<ModuleInfo> modules,
        String directoryName, String objectName)
    {
        String basePath = directoryName + "/" + objectName; //$NON-NLS-1$
        IContainer folder = project.getFolder(new Path("src").append(basePath)); //$NON-NLS-1$
        if (!folder.exists())
        {
            return;
        }

        try
        {
            scanBslFilesRecursive(folder, modules, basePath, 0);
        }
        catch (Exception e)
        {
            Activator.logError("Error scanning BSL modules for " + basePath, e); //$NON-NLS-1$
        }
    }

    /**
     * Recursively scans for .bsl files under a container.
     */
    private void scanBslFilesRecursive(IContainer container, List<ModuleInfo> modules,
        String basePath, int depth)
        throws Exception
    {
        if (depth > MAX_RECURSION_DEPTH)
        {
            return;
        }

        for (IResource member : container.members())
        {
            if (member instanceof IFile)
            {
                IFile file = (IFile) member;
                if (file.getName().endsWith(".bsl")) //$NON-NLS-1$
                {
                    String fullPath = file.getProjectRelativePath().toString();
                    String modulePath = fullPath.startsWith("src/") //$NON-NLS-1$
                        ? fullPath.substring(4) : fullPath;

                    ModuleInfo info = new ModuleInfo();
                    info.relativePath = modulePath;
                    info.moduleType = determineModuleType(modulePath, basePath);
                    info.file = file;
                    info.lineCount = countLines(file);
                    modules.add(info);
                }
            }
            else if (member instanceof IContainer)
            {
                scanBslFilesRecursive((IContainer) member, modules, basePath, depth + 1);
            }
        }
    }

    /**
     * Determines module type from full path (for MODULE_PATH targets).
     */
    private String determineModuleType(String modulePath)
    {
        String fileName = modulePath.contains("/") //$NON-NLS-1$
            ? modulePath.substring(modulePath.lastIndexOf('/') + 1)
            : modulePath;

        String baseName = fileName.endsWith(".bsl") //$NON-NLS-1$
            ? fileName.substring(0, fileName.length() - 4)
            : fileName;

        if ("Module".equals(baseName)) //$NON-NLS-1$
        {
            if (modulePath.contains("Forms/")) //$NON-NLS-1$
            {
                return "FormModule"; //$NON-NLS-1$
            }
            if (modulePath.startsWith("CommonModules/")) //$NON-NLS-1$
            {
                return "Module"; //$NON-NLS-1$
            }
            return "Module"; //$NON-NLS-1$
        }

        return baseName;
    }

    /**
     * Determines module type from path relative to a base path.
     */
    private String determineModuleType(String modulePath, String basePath)
    {
        String relativePath = modulePath.substring(basePath.length());
        if (relativePath.startsWith("/")) //$NON-NLS-1$
        {
            relativePath = relativePath.substring(1);
        }

        String fileName = relativePath.contains("/") //$NON-NLS-1$
            ? relativePath.substring(relativePath.lastIndexOf('/') + 1)
            : relativePath;

        String baseName = fileName.endsWith(".bsl") //$NON-NLS-1$
            ? fileName.substring(0, fileName.length() - 4)
            : fileName;

        if ("Module".equals(baseName)) //$NON-NLS-1$
        {
            if (relativePath.startsWith("Forms/")) //$NON-NLS-1$
            {
                return "FormModule"; //$NON-NLS-1$
            }
            return "Module"; //$NON-NLS-1$
        }

        return baseName;
    }

    /**
     * Counts lines in a file.
     */
    private int countLines(IFile file)
    {
        try
        {
            List<String> lines = BslModuleUtils.readFileLines(file);
            return lines.size();
        }
        catch (Exception e)
        {
            return 0;
        }
    }

    // ========== Output formatting ==========

    /**
     * Appends the modules list section.
     */
    private void appendModulesList(StringBuilder md, List<ModuleInfo> modules, FrontMatter fm)
    {
        fm.put("moduleCount", modules.size()); //$NON-NLS-1$

        md.append("## Modules (").append(modules.size()).append(")\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        if (modules.isEmpty())
        {
            md.append("No BSL modules found.\n\n"); //$NON-NLS-1$
            return;
        }

        md.append("| Module | Path | Lines |\n"); //$NON-NLS-1$
        md.append("|--------|------|-------|\n"); //$NON-NLS-1$

        for (ModuleInfo module : modules)
        {
            md.append("| ").append(MarkdownUtils.escapeForTable(module.moduleType)); //$NON-NLS-1$
            md.append(" | ").append(MarkdownUtils.escapeForTable(module.relativePath)); //$NON-NLS-1$
            md.append(" | ").append(module.lineCount); //$NON-NLS-1$
            md.append(" |\n"); //$NON-NLS-1$
        }
        md.append("\n"); //$NON-NLS-1$
    }

    // ========== Module structure ==========

    /**
     * Appends module structure (methods, regions) for a single module.
     *
     * @return number of methods found
     */
    private int appendModuleStructure(StringBuilder md, IProject project,
        ModuleInfo moduleInfo, int maxMethods)
    {
        md.append("## Structure: ").append(moduleInfo.moduleType); //$NON-NLS-1$
        md.append(" (").append(moduleInfo.lineCount).append(" lines)\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        // Try EMF-based structure extraction
        Module module = BslModuleUtils.loadModule(project, moduleInfo.relativePath);
        if (module != null)
        {
            return appendStructureFromEmf(md, module, moduleInfo, maxMethods);
        }

        // Fallback: text-based parsing
        return appendStructureFromText(md, moduleInfo, maxMethods);
    }

    /**
     * Extracts structure using EMF AST model.
     */
    private int appendStructureFromEmf(StringBuilder md, Module module,
        ModuleInfo moduleInfo, int maxMethods)
    {
        // Collect regions
        List<RegionInfo> regions = collectRegions(module);
        if (!regions.isEmpty())
        {
            md.append("### Regions\n\n"); //$NON-NLS-1$
            for (RegionInfo region : regions)
            {
                md.append("- ").append(region.name); //$NON-NLS-1$
                md.append(" (line ").append(region.startLine); //$NON-NLS-1$
                md.append("-").append(region.endLine).append(")\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            md.append("\n"); //$NON-NLS-1$
        }

        // Collect methods
        List<MethodInfo> methods = collectMethods(module, regions);

        if (methods.isEmpty())
        {
            md.append("No methods found.\n\n"); //$NON-NLS-1$
            return 0;
        }

        int shown = Math.min(methods.size(), maxMethods);

        md.append("### Methods"); //$NON-NLS-1$
        if (shown < methods.size())
        {
            md.append(" (showing ").append(shown).append(" of ").append(methods.size()).append(")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        md.append("\n\n"); //$NON-NLS-1$

        md.append("| # | Type | Name | Export | Context | Lines | Parameters | Region |\n"); //$NON-NLS-1$
        md.append("|---|------|------|--------|---------|-------|------------|--------|\n"); //$NON-NLS-1$

        for (int i = 0; i < shown; i++)
        {
            MethodInfo m = methods.get(i);
            md.append("| ").append(i + 1); //$NON-NLS-1$
            md.append(" | ").append(m.isFunction ? "Function" : "Procedure"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            md.append(" | ").append(MarkdownUtils.escapeForTable(m.name)); //$NON-NLS-1$
            md.append(" | ").append(m.isExport ? "Yes" : "-"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            md.append(" | ").append(m.executionContext != null //$NON-NLS-1$
                ? MarkdownUtils.escapeForTable(m.executionContext) : "-"); //$NON-NLS-1$
            md.append(" | ").append(m.startLine).append("-").append(m.endLine); //$NON-NLS-1$ //$NON-NLS-2$
            md.append(" | ").append(MarkdownUtils.escapeForTable(m.paramsString)); //$NON-NLS-1$
            md.append(" | ").append(m.region != null //$NON-NLS-1$
                ? MarkdownUtils.escapeForTable(m.region) : "-"); //$NON-NLS-1$
            md.append(" |\n"); //$NON-NLS-1$
        }
        md.append("\n"); //$NON-NLS-1$

        return methods.size();
    }

    /**
     * Extracts structure using text-based regex parsing (fallback).
     */
    private int appendStructureFromText(StringBuilder md, ModuleInfo moduleInfo, int maxMethods)
    {
        List<String> lines;
        try
        {
            lines = BslModuleUtils.readFileLines(moduleInfo.file);
        }
        catch (Exception e)
        {
            md.append("*Could not read module*\n\n"); //$NON-NLS-1$
            return 0;
        }

        // Parse methods
        List<MethodInfo> methods = new ArrayList<>();
        String currentMethodName = null;
        boolean currentIsFunction = false;
        String currentParams = "-"; //$NON-NLS-1$
        int currentStartLine = 0;

        for (int i = 0; i < lines.size(); i++)
        {
            String line = lines.get(i);
            int lineNum = i + 1;

            java.util.regex.Matcher startMatcher = BslModuleUtils.METHOD_START_PATTERN.matcher(line);
            if (startMatcher.find())
            {
                currentMethodName = startMatcher.group(1);
                currentIsFunction = BslModuleUtils.FUNC_KEYWORD_PATTERN.matcher(line).find();
                currentStartLine = lineNum;

                // Extract params text
                String paramsText = startMatcher.group(2);
                if (paramsText != null)
                {
                    paramsText = paramsText.replaceAll("\\)\\s*(Экспорт|Export)?\\s*$", "").trim(); //$NON-NLS-1$ //$NON-NLS-2$
                    currentParams = paramsText.isEmpty() ? "-" : paramsText; //$NON-NLS-1$
                }
                continue;
            }

            if (BslModuleUtils.METHOD_END_PATTERN.matcher(line).find() && currentMethodName != null)
            {
                MethodInfo info = new MethodInfo();
                info.name = currentMethodName;
                info.isFunction = currentIsFunction;
                info.isExport = false; // Can't reliably determine from text
                info.startLine = currentStartLine;
                info.endLine = lineNum;
                info.paramsString = currentParams;
                info.region = BslModuleUtils.findRegionForLine(lines, currentStartLine);
                methods.add(info);
                currentMethodName = null;
            }
        }

        if (methods.isEmpty())
        {
            md.append("No methods found.\n\n"); //$NON-NLS-1$
            return 0;
        }

        int shown = Math.min(methods.size(), maxMethods);

        md.append("### Methods"); //$NON-NLS-1$
        if (shown < methods.size())
        {
            md.append(" (showing ").append(shown).append(" of ").append(methods.size()).append(")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        md.append("\n\n"); //$NON-NLS-1$

        md.append("| # | Type | Name | Lines | Parameters | Region |\n"); //$NON-NLS-1$
        md.append("|---|------|------|-------|------------|--------|\n"); //$NON-NLS-1$

        for (int i = 0; i < shown; i++)
        {
            MethodInfo m = methods.get(i);
            md.append("| ").append(i + 1); //$NON-NLS-1$
            md.append(" | ").append(m.isFunction ? "Function" : "Procedure"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            md.append(" | ").append(MarkdownUtils.escapeForTable(m.name)); //$NON-NLS-1$
            md.append(" | ").append(m.startLine).append("-").append(m.endLine); //$NON-NLS-1$ //$NON-NLS-2$
            md.append(" | ").append(MarkdownUtils.escapeForTable(m.paramsString)); //$NON-NLS-1$
            md.append(" | ").append(m.region != null //$NON-NLS-1$
                ? MarkdownUtils.escapeForTable(m.region) : "-"); //$NON-NLS-1$
            md.append(" |\n"); //$NON-NLS-1$
        }
        md.append("\n"); //$NON-NLS-1$

        return methods.size();
    }

    // ========== Source code ==========

    /**
     * Appends source code for all modules (or just focusMethod).
     */
    private void appendSourceCode(StringBuilder md, List<ModuleInfo> modules, String focusMethod)
    {
        for (ModuleInfo module : modules)
        {
            List<String> lines;
            try
            {
                lines = BslModuleUtils.readFileLines(module.file);
            }
            catch (Exception e)
            {
                md.append("## Source: ").append(module.moduleType).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
                md.append("*Could not read source*\n\n"); //$NON-NLS-1$
                continue;
            }

            if (focusMethod != null && !focusMethod.isEmpty())
            {
                appendFocusMethodSource(md, module, lines, focusMethod);
            }
            else
            {
                md.append("## Source: ").append(module.moduleType).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
                md.append("```bsl\n"); //$NON-NLS-1$
                for (String line : lines)
                {
                    md.append(line).append("\n"); //$NON-NLS-1$
                }
                md.append("```\n\n"); //$NON-NLS-1$
            }
        }
    }

    /**
     * Appends source code for a specific method.
     */
    private void appendFocusMethodSource(StringBuilder md, ModuleInfo module,
        List<String> lines, String focusMethod)
    {
        int methodStart = -1;
        int methodEnd = -1;

        for (int i = 0; i < lines.size(); i++)
        {
            String line = lines.get(i);

            if (methodStart < 0)
            {
                java.util.regex.Matcher matcher = BslModuleUtils.METHOD_START_PATTERN.matcher(line);
                if (matcher.find() && focusMethod.equalsIgnoreCase(matcher.group(1)))
                {
                    methodStart = i;
                }
            }
            else if (BslModuleUtils.METHOD_END_PATTERN.matcher(line).find())
            {
                methodEnd = i;
                break;
            }
        }

        if (methodStart < 0)
        {
            md.append("## Source: ").append(module.moduleType); //$NON-NLS-1$
            md.append(" (method '").append(focusMethod).append("' not found)\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }

        if (methodEnd < 0)
        {
            methodEnd = lines.size() - 1;
        }

        // Include doc-comments before method
        int docStart = methodStart;
        for (int i = methodStart - 1; i >= 0; i--)
        {
            String line = lines.get(i).trim();
            if (line.startsWith("//")) //$NON-NLS-1$
            {
                docStart = i;
            }
            else if (line.isEmpty())
            {
                continue;
            }
            else
            {
                break;
            }
        }

        md.append("## Source: ").append(module.moduleType); //$NON-NLS-1$
        md.append(" - ").append(focusMethod); //$NON-NLS-1$
        md.append(" (lines ").append(docStart + 1).append("-").append(methodEnd + 1).append(")\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        md.append("```bsl\n"); //$NON-NLS-1$
        for (int i = docStart; i <= methodEnd; i++)
        {
            md.append(lines.get(i)).append("\n"); //$NON-NLS-1$
        }
        md.append("```\n\n"); //$NON-NLS-1$
    }

    // ========== EMF data collection ==========

    /**
     * Collects region information from the BSL AST.
     */
    private List<RegionInfo> collectRegions(Module module)
    {
        List<RegionInfo> regions = new ArrayList<>();

        try
        {
            for (var iter = module.eAllContents(); iter.hasNext();)
            {
                EObject obj = iter.next();
                if (obj instanceof RegionPreprocessor region)
                {
                    RegionInfo info = new RegionInfo();
                    info.name = region.getName();
                    info.startLine = BslModuleUtils.getStartLine(region);
                    info.endLine = computeRegionEndLine(region, info.startLine);
                    if (info.name != null && !info.name.isEmpty() && info.startLine > 0)
                    {
                        regions.add(info);
                    }
                }
            }
        }
        catch (Exception e)
        {
            Activator.logError("Error collecting regions in ai_context", e); //$NON-NLS-1$
        }

        return regions;
    }

    /**
     * Computes the end line of a region by scanning contained EObjects.
     */
    private int computeRegionEndLine(RegionPreprocessor region, int startLine)
    {
        int endLine = startLine;
        for (var iter = region.eAllContents(); iter.hasNext();)
        {
            int childEnd = BslModuleUtils.getEndLine(iter.next());
            if (childEnd > endLine)
            {
                endLine = childEnd;
            }
        }
        return endLine > startLine ? endLine + 1 : startLine + 1;
    }

    /**
     * Collects method information from the BSL AST.
     */
    private List<MethodInfo> collectMethods(Module module, List<RegionInfo> regions)
    {
        List<MethodInfo> methods = new ArrayList<>();

        for (Method method : module.allMethods())
        {
            try
            {
                MethodInfo info = new MethodInfo();
                info.name = method.getName();
                info.isFunction = method instanceof Function;
                info.isExport = method.isExport();
                info.startLine = BslModuleUtils.getStartLine(method);
                info.endLine = BslModuleUtils.getEndLine(method);
                info.paramsString = BslModuleUtils.buildParamsString(method);
                info.executionContext = collectPragmas(method);
                info.region = findContainingRegion(info.startLine, regions);
                methods.add(info);
            }
            catch (Exception e)
            {
                Activator.logError("Error processing method: " + method.getName(), e); //$NON-NLS-1$
            }
        }

        return methods;
    }

    /**
     * Collects pragma annotations (&AtServer, &AtClient, etc.) for a method.
     */
    private String collectPragmas(Method method)
    {
        try
        {
            EList<Pragma> pragmas = method.getPragmas();
            if (pragmas != null && !pragmas.isEmpty())
            {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < pragmas.size(); i++)
                {
                    if (i > 0)
                    {
                        sb.append(", "); //$NON-NLS-1$
                    }
                    Pragma pragma = pragmas.get(i);
                    sb.append("&").append(pragma.getSymbol()); //$NON-NLS-1$
                }
                return sb.toString();
            }
        }
        catch (Exception e)
        {
            // Pragmas may not be available in all module types
        }
        return null;
    }

    /**
     * Finds the innermost region containing the given line.
     */
    private String findContainingRegion(int line, List<RegionInfo> regions)
    {
        String bestRegion = null;
        int bestRange = Integer.MAX_VALUE;
        for (RegionInfo region : regions)
        {
            if (line >= region.startLine && line <= region.endLine)
            {
                int range = region.endLine - region.startLine;
                if (range < bestRange)
                {
                    bestRange = range;
                    bestRegion = region.name;
                }
            }
        }
        return bestRegion;
    }

    // ========== Internal data structures ==========

    private static class MethodInfo
    {
        String name;
        boolean isFunction;
        boolean isExport;
        int startLine;
        int endLine;
        String executionContext;
        String region;
        String paramsString;
    }

    private static class RegionInfo
    {
        String name;
        int startLine;
        int endLine;
    }
}
