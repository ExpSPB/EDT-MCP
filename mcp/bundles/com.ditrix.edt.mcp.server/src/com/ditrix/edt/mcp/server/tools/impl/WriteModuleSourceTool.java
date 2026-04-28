/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.validation.marker.IMarkerManager;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BmExportHelper;
import com.ditrix.edt.mcp.server.utils.FileMarkers;
import com.ditrix.edt.mcp.server.utils.FrontMatter;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;

/**
 * Tool to write BSL source code to 1C metadata object modules.
 * Supports 7 modes: searchReplace (content-based, default), replace (full file),
 * append, replaceLines, replaceMethod, insertBefore, insertAfter.
 * Optionally validates BSL syntax (balanced block keywords) before writing.
 * Can resolve module path from objectName + moduleType.
 */
public class WriteModuleSourceTool implements IMcpTool
{
    public static final String NAME = "write_module_source"; //$NON-NLS-1$

    private static final String MODE_REPLACE = "replace"; //$NON-NLS-1$
    private static final String MODE_APPEND = "append"; //$NON-NLS-1$
    private static final String MODE_SEARCH_REPLACE = "searchReplace"; //$NON-NLS-1$
    private static final String MODE_REPLACE_LINES = "replaceLines"; //$NON-NLS-1$
    private static final String MODE_REPLACE_METHOD = "replaceMethod"; //$NON-NLS-1$
    private static final String MODE_INSERT_BEFORE = "insertBefore"; //$NON-NLS-1$
    private static final String MODE_INSERT_AFTER = "insertAfter"; //$NON-NLS-1$

    /** Maximum source length to prevent accidental huge writes */
    private static final int MAX_SOURCE_LENGTH = 500_000;

    /** UTF-8 BOM bytes */
    private static final byte[] UTF8_BOM = { (byte)0xEF, (byte)0xBB, (byte)0xBF };

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Write BSL source code to 1C metadata object modules. " + //$NON-NLS-1$
            "7 modes: searchReplace (find oldSource and replace with source, default), " + //$NON-NLS-1$
            "replace (replace entire file), append (add to end), " + //$NON-NLS-1$
            "replaceLines (replace line range), replaceMethod (replace entire method by name), " + //$NON-NLS-1$
            "insertBefore (insert source before line N), insertAfter (insert source after line N). " + //$NON-NLS-1$
            "Specify modulePath or objectName + moduleType. " + //$NON-NLS-1$
            "Automatically checks BSL syntax (balanced Procedure/EndProcedure, " + //$NON-NLS-1$
            "Function/EndFunction, If/EndIf, etc.) before writing - " + //$NON-NLS-1$
            "blocks write on errors. Pass skipSyntaxCheck=true to force. " + //$NON-NLS-1$
            "Use dryRun=true to preview changes without writing."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("modulePath", //$NON-NLS-1$
                "Path to module from src/ folder, e.g. " + //$NON-NLS-1$
                "'Documents/MyDoc/ObjectModule.bsl' or " + //$NON-NLS-1$
                "'CommonModules/MyModule/Module.bsl'. " + //$NON-NLS-1$
                "Alternative: use objectName + moduleType.") //$NON-NLS-1$
            .stringProperty("objectName", //$NON-NLS-1$
                "Full object name, e.g. 'Document.MyDoc', " + //$NON-NLS-1$
                "'DataProcessor.MyProcessor'. " + //$NON-NLS-1$
                "Supports Russian names (e.g. 'Документ.МойДок'). " + //$NON-NLS-1$
                "Alternative to modulePath.") //$NON-NLS-1$
            .stringProperty("moduleType", //$NON-NLS-1$
                "Module type (used with objectName): ObjectModule (default), " + //$NON-NLS-1$
                "ManagerModule, FormModule, CommandModule, RecordSetModule.") //$NON-NLS-1$
            .stringProperty("source", //$NON-NLS-1$
                "BSL source code to write (required). " + //$NON-NLS-1$
                "For replace: complete module content. " + //$NON-NLS-1$
                "For searchReplace: new code replacing oldSource. " + //$NON-NLS-1$
                "For append: code to add.", true) //$NON-NLS-1$
            .stringProperty("oldSource", //$NON-NLS-1$
                "Existing code to find and replace (required for searchReplace mode). " + //$NON-NLS-1$
                "Must match exactly one location in the file. " + //$NON-NLS-1$
                "Proves that you have read the current file content.") //$NON-NLS-1$
            .stringProperty("mode", //$NON-NLS-1$
                "Write mode: 'searchReplace' (find oldSource and replace with source, default), " + //$NON-NLS-1$
                "'replace' (replace entire file), 'append' (add to end), " + //$NON-NLS-1$
                "'replaceLines' (replace line range), 'replaceMethod' (replace entire method by name), " + //$NON-NLS-1$
                "'insertBefore' (insert source before line N), 'insertAfter' (insert source after line N).") //$NON-NLS-1$
            .stringProperty("formName", //$NON-NLS-1$
                "Form name, required when moduleType=FormModule " + //$NON-NLS-1$
                "(e.g. 'ItemForm').") //$NON-NLS-1$
            .stringProperty("commandName", //$NON-NLS-1$
                "Command name, required when moduleType=CommandModule " + //$NON-NLS-1$
                "(e.g. 'FillByTemplate').") //$NON-NLS-1$
            .integerProperty("lineFrom", //$NON-NLS-1$
                "Start line number for replaceLines mode (1-based, inclusive)") //$NON-NLS-1$
            .integerProperty("lineTo", //$NON-NLS-1$
                "End line number for replaceLines mode (1-based, inclusive)") //$NON-NLS-1$
            .stringProperty("methodName", //$NON-NLS-1$
                "Method name for replaceMethod mode (finds Procedure/Function by name)") //$NON-NLS-1$
            .integerProperty("line", //$NON-NLS-1$
                "1-based line number for insertBefore/insertAfter modes. " + //$NON-NLS-1$
                "insertBefore: source is placed before line N (existing line N becomes N+sourceLines). " + //$NON-NLS-1$
                "insertAfter: source is placed after line N (existing line N+1 shifts down).") //$NON-NLS-1$
            .booleanProperty("dryRun", //$NON-NLS-1$
                "Preview changes without writing. Returns diff stats " + //$NON-NLS-1$
                "(linesBefore, linesAfter, removedLines, addedLines)") //$NON-NLS-1$
            .booleanProperty("skipSyntaxCheck", //$NON-NLS-1$
                "Skip BSL syntax validation (default: false). " + //$NON-NLS-1$
                "By default, checks balanced Procedure/EndProcedure, " + //$NON-NLS-1$
                "Function/EndFunction, If/EndIf, While/EndDo, " + //$NON-NLS-1$
                "For/EndDo, Try/EndTry. Set true to force write.") //$NON-NLS-1$
            .booleanProperty("validateAfterWrite", //$NON-NLS-1$
                "Run EDT validation after a successful write and embed the result " + //$NON-NLS-1$
                "in the response (default: true). Adds a 'validation' block with " + //$NON-NLS-1$
                "errors / warnings / codeStyle counts and a hint. " + //$NON-NLS-1$
                "Pass false for batch sequences and call get_project_errors at the end.") //$NON-NLS-1$
            .booleanProperty("confirmFullReplace", //$NON-NLS-1$
                "Required (set to true) when a write removes more than 50% of " + //$NON-NLS-1$
                "an existing module. Prevents accidental destruction of " + //$NON-NLS-1$
                "hundreds of lines when only one method or fragment was intended " + //$NON-NLS-1$
                "to change. Reads at >30% emit a non-blocking 'protection' warning.") //$NON-NLS-1$
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
        String modulePath = JsonUtils.extractStringArgument(params, "modulePath"); //$NON-NLS-1$
        if (modulePath != null && !modulePath.isEmpty())
        {
            String safeName = modulePath.replace("/", "-").replace("\\", "-").toLowerCase(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            return "write-" + safeName + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "write-module-source.md"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        // 1. Extract parameters
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String modulePath = JsonUtils.extractStringArgument(params, "modulePath"); //$NON-NLS-1$
        String objectName = JsonUtils.extractStringArgument(params, "objectName"); //$NON-NLS-1$
        String moduleType = JsonUtils.extractStringArgument(params, "moduleType"); //$NON-NLS-1$
        String source = JsonUtils.extractStringArgument(params, "source"); //$NON-NLS-1$
        String oldSource = JsonUtils.extractStringArgument(params, "oldSource"); //$NON-NLS-1$
        String mode = JsonUtils.extractStringArgument(params, "mode"); //$NON-NLS-1$
        String formName = JsonUtils.extractStringArgument(params, "formName"); //$NON-NLS-1$
        String commandName = JsonUtils.extractStringArgument(params, "commandName"); //$NON-NLS-1$
        int lineFrom = JsonUtils.extractIntArgument(params, "lineFrom", -1); //$NON-NLS-1$
        int lineTo = JsonUtils.extractIntArgument(params, "lineTo", -1); //$NON-NLS-1$
        String methodName = JsonUtils.extractStringArgument(params, "methodName"); //$NON-NLS-1$
        int line = JsonUtils.extractIntArgument(params, "line", -1); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        boolean skipSyntaxCheck = JsonUtils.extractBooleanArgument(params, "skipSyntaxCheck", false); //$NON-NLS-1$
        boolean validateAfterWrite = JsonUtils.extractBooleanArgument(params, "validateAfterWrite", true); //$NON-NLS-1$
        boolean confirmFullReplace = JsonUtils.extractBooleanArgument(params, "confirmFullReplace", false); //$NON-NLS-1$

        // 2. Validate required parameters
        if (projectName == null || projectName.isEmpty())
        {
            return "Error: projectName is required"; //$NON-NLS-1$
        }
        if (source == null)
        {
            return "Error: source is required"; //$NON-NLS-1$
        }
        if (source.length() > MAX_SOURCE_LENGTH)
        {
            return "Error: source exceeds maximum allowed length (" + MAX_SOURCE_LENGTH + " characters)"; //$NON-NLS-1$ //$NON-NLS-2$
        }

        // Default mode
        if (mode == null || mode.isEmpty())
        {
            mode = MODE_SEARCH_REPLACE;
        }

        // Validate mode
        if (!MODE_REPLACE.equals(mode) && !MODE_APPEND.equals(mode)
            && !MODE_SEARCH_REPLACE.equals(mode)
            && !MODE_REPLACE_LINES.equals(mode) && !MODE_REPLACE_METHOD.equals(mode)
            && !MODE_INSERT_BEFORE.equals(mode) && !MODE_INSERT_AFTER.equals(mode))
        {
            return "Error: invalid mode '" + mode + "'. " + //$NON-NLS-1$ //$NON-NLS-2$
                "Allowed: searchReplace, replace, append, replaceLines, replaceMethod, " + //$NON-NLS-1$
                "insertBefore, insertAfter"; //$NON-NLS-1$
        }

        // Validate oldSource for searchReplace mode
        if (MODE_SEARCH_REPLACE.equals(mode))
        {
            if (oldSource == null || oldSource.isEmpty())
            {
                return "Error: oldSource is required for searchReplace mode"; //$NON-NLS-1$
            }
        }

        // 3. Resolve modulePath
        if (modulePath == null || modulePath.isEmpty())
        {
            if (objectName == null || objectName.isEmpty())
            {
                return "Error: either modulePath or objectName is required"; //$NON-NLS-1$
            }
            String resolved = resolveModulePath(objectName, moduleType, formName, commandName);
            if (resolved.startsWith("Error:")) //$NON-NLS-1$
            {
                return resolved;
            }
            modulePath = resolved;
        }

        // Validate modulePath: prevent path traversal
        if (modulePath.contains("..")) //$NON-NLS-1$
        {
            return "Error: modulePath must not contain '..'"; //$NON-NLS-1$
        }

        // Validate modulePath: only .bsl files allowed
        if (!modulePath.endsWith(".bsl")) //$NON-NLS-1$
        {
            return "Error: only .bsl module files can be written"; //$NON-NLS-1$
        }

        // 4. Validate project
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return "Error: Project not found: " + projectName; //$NON-NLS-1$
        }

        // 5. Get file
        IFile file = project.getFile(new Path("src").append(modulePath)); //$NON-NLS-1$
        boolean fileExists = file.exists();

        // For non-replace modes, file must exist
        if (!fileExists && !MODE_REPLACE.equals(mode))
        {
            return "Error: File not found: src/" + modulePath + //$NON-NLS-1$
                ". Only 'replace' mode can create new files."; //$NON-NLS-1$
        }

        try
        {
            // Normalize source: \r\n -> \n
            source = source.replace("\r\n", "\n"); //$NON-NLS-1$ //$NON-NLS-2$

            // 6. Read current content (if file exists)
            List<String> originalLines;
            boolean hasBom;
            if (fileExists)
            {
                originalLines = BslModuleUtils.readFileLines(file);
                hasBom = detectBom(file);
            }
            else
            {
                originalLines = new ArrayList<>();
                hasBom = true; // New BSL files should have BOM
            }

            // 7. Compute new content based on mode
            List<String> newLines;
            int totalOriginal = originalLines.size();

            switch (mode)
            {
                case MODE_REPLACE:
                    newLines = splitSourceLines(source);
                    break;

                case MODE_APPEND:
                    newLines = new ArrayList<>(originalLines);
                    newLines.addAll(splitSourceLines(source));
                    break;

                case MODE_SEARCH_REPLACE:
                {
                    // Normalize oldSource
                    oldSource = oldSource.replace("\r\n", "\n"); //$NON-NLS-1$ //$NON-NLS-2$

                    // Join original lines into single string for content-based search
                    String currentContent = String.join("\n", originalLines); //$NON-NLS-1$

                    // Find oldSource in current content
                    int idx = currentContent.indexOf(oldSource);
                    if (idx < 0)
                    {
                        return "Error: oldSource not found in current file content. " + //$NON-NLS-1$
                            "The file may have changed since last read, or the oldSource text " + //$NON-NLS-1$
                            "does not match exactly. Please read the file again with read_module_source."; //$NON-NLS-1$
                    }

                    // Check for multiple occurrences
                    int secondIdx = currentContent.indexOf(oldSource, idx + 1);
                    if (secondIdx >= 0)
                    {
                        return "Error: oldSource found multiple times in the file (" + //$NON-NLS-1$
                            countOccurrences(currentContent, oldSource) +
                            " occurrences). Provide a larger, more specific oldSource fragment " + //$NON-NLS-1$
                            "that matches exactly one location."; //$NON-NLS-1$
                    }

                    // Perform replacement
                    String newContent = currentContent.substring(0, idx)
                        + source
                        + currentContent.substring(idx + oldSource.length());

                    newLines = splitSourceLines(newContent);
                    break;
                }

                case MODE_REPLACE_LINES:
                {
                    if (lineFrom < 1 || lineTo < lineFrom)
                    {
                        return "Error: invalid line range: lineFrom=" + lineFrom + //$NON-NLS-1$
                            ", lineTo=" + lineTo + //$NON-NLS-1$
                            ". lineFrom must be >= 1 and lineTo must be >= lineFrom"; //$NON-NLS-1$
                    }
                    if (lineTo > totalOriginal)
                    {
                        return "Error: lineTo (" + lineTo + ") exceeds file length (" + //$NON-NLS-1$ //$NON-NLS-2$
                            totalOriginal + " lines)"; //$NON-NLS-1$
                    }
                    newLines = new ArrayList<>();
                    // Lines before (0-indexed: 0 to lineFrom-2)
                    newLines.addAll(originalLines.subList(0, lineFrom - 1));
                    // New content
                    newLines.addAll(splitSourceLines(source));
                    // Lines after (0-indexed: lineTo to end)
                    if (lineTo < totalOriginal)
                    {
                        newLines.addAll(originalLines.subList(lineTo, totalOriginal));
                    }
                    break;
                }

                case MODE_INSERT_BEFORE:
                {
                    if (line < 1)
                    {
                        return "Error: line is required for insertBefore mode " + //$NON-NLS-1$
                            "and must be >= 1 (1-based)"; //$NON-NLS-1$
                    }
                    if (line > totalOriginal)
                    {
                        return "Error: line (" + line + ") exceeds file length (" + //$NON-NLS-1$ //$NON-NLS-2$
                            totalOriginal + " lines). " + //$NON-NLS-1$
                            "Use 'append' mode to add at end."; //$NON-NLS-1$
                    }
                    newLines = new ArrayList<>();
                    // Lines before insertion point (0..line-1 exclusive of line index line-1)
                    newLines.addAll(originalLines.subList(0, line - 1));
                    // New content
                    newLines.addAll(splitSourceLines(source));
                    // Original line N and the rest
                    newLines.addAll(originalLines.subList(line - 1, totalOriginal));
                    break;
                }

                case MODE_INSERT_AFTER:
                {
                    if (line < 1)
                    {
                        return "Error: line is required for insertAfter mode " + //$NON-NLS-1$
                            "and must be >= 1 (1-based)"; //$NON-NLS-1$
                    }
                    if (line > totalOriginal)
                    {
                        return "Error: line (" + line + ") exceeds file length (" + //$NON-NLS-1$ //$NON-NLS-2$
                            totalOriginal + " lines). " + //$NON-NLS-1$
                            "Use 'append' mode to add at end."; //$NON-NLS-1$
                    }
                    newLines = new ArrayList<>();
                    // Lines up to and including line N (0-indexed: 0..line)
                    newLines.addAll(originalLines.subList(0, line));
                    // New content
                    newLines.addAll(splitSourceLines(source));
                    // Lines after N
                    if (line < totalOriginal)
                    {
                        newLines.addAll(originalLines.subList(line, totalOriginal));
                    }
                    break;
                }

                case MODE_REPLACE_METHOD:
                {
                    if (methodName == null || methodName.isEmpty())
                    {
                        return "Error: methodName is required for replaceMethod mode"; //$NON-NLS-1$
                    }
                    // Find method boundaries
                    int methodStart = -1;
                    int methodEnd = -1;
                    for (int i = 0; i < totalOriginal; i++)
                    {
                        Matcher m = BslModuleUtils.METHOD_START_PATTERN.matcher(originalLines.get(i));
                        if (m.find() && m.group(1).equalsIgnoreCase(methodName))
                        {
                            methodStart = i;
                            // Check for compiler directive on previous line(s)
                            int directiveStart = methodStart;
                            for (int k = methodStart - 1; k >= 0; k--)
                            {
                                String prevLine = originalLines.get(k).trim();
                                if (prevLine.startsWith("&") || prevLine.startsWith("#")) //$NON-NLS-1$ //$NON-NLS-2$
                                {
                                    directiveStart = k;
                                }
                                else if (prevLine.isEmpty())
                                {
                                    // Skip empty lines between directive and method
                                    continue;
                                }
                                else
                                {
                                    break;
                                }
                            }
                            methodStart = directiveStart;
                            break;
                        }
                    }
                    if (methodStart < 0)
                    {
                        return "Error: method '" + methodName + "' not found in module"; //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    // Find end
                    for (int i = methodStart + 1; i < totalOriginal; i++)
                    {
                        if (BslModuleUtils.METHOD_END_PATTERN.matcher(originalLines.get(i)).find())
                        {
                            methodEnd = i;
                            break;
                        }
                    }
                    if (methodEnd < 0)
                    {
                        return "Error: could not find EndProcedure/EndFunction for method '" + //$NON-NLS-1$
                            methodName + "'"; //$NON-NLS-1$
                    }
                    // Build new content
                    newLines = new ArrayList<>();
                    newLines.addAll(originalLines.subList(0, methodStart));
                    newLines.addAll(splitSourceLines(source));
                    if (methodEnd + 1 < totalOriginal)
                    {
                        newLines.addAll(originalLines.subList(methodEnd + 1, totalOriginal));
                    }
                    break;
                }

                default:
                    return "Error: unsupported mode: " + mode; //$NON-NLS-1$
            }

            // 8. Protection: warn at >30%, hard-stop at >50% unless confirmFullReplace=true.
            //    Skipped for explicit MODE_REPLACE, where wholesale replacement is intended.
            String protectionWarning = null;
            if (totalOriginal > 10 && !MODE_REPLACE.equals(mode))
            {
                int removed = totalOriginal - newLines.size();
                if (removed > 0)
                {
                    int removalPercent = (int)Math.round(100.0 * removed / totalOriginal);
                    if (removalPercent > 50 && !confirmFullReplace)
                    {
                        return "Error: this change would remove " + removalPercent + "% of the module (" //$NON-NLS-1$ //$NON-NLS-2$
                            + removed + " of " + totalOriginal + " lines). " //$NON-NLS-1$ //$NON-NLS-2$
                            + "Pass confirmFullReplace=true to proceed, " //$NON-NLS-1$
                            + "or use a more targeted mode (replaceMethod, replaceLines, " //$NON-NLS-1$
                            + "searchReplace) to change a smaller fragment."; //$NON-NLS-1$
                    }
                    if (removalPercent > 30)
                    {
                        protectionWarning = "WARNING: this change removes " + removalPercent //$NON-NLS-1$
                            + "% of the module (from " + totalOriginal //$NON-NLS-1$
                            + " to " + newLines.size() + " lines)"; //$NON-NLS-1$ //$NON-NLS-2$
                    }
                }
            }

            // 9. Dry run - preview without writing
            if (dryRun)
            {
                FrontMatter dryFm = FrontMatter.create()
                    .put("tool", NAME) //$NON-NLS-1$
                    .put("projectName", projectName) //$NON-NLS-1$
                    .put("modulePath", modulePath) //$NON-NLS-1$
                    .put("mode", mode) //$NON-NLS-1$
                    .put("status", "preview") //$NON-NLS-1$ //$NON-NLS-2$
                    .put("dryRun", true) //$NON-NLS-1$
                    .put("linesBefore", totalOriginal) //$NON-NLS-1$
                    .put("linesAfter", newLines.size()) //$NON-NLS-1$
                    .put("lineDelta", newLines.size() - totalOriginal); //$NON-NLS-1$

                if (protectionWarning != null)
                {
                    dryFm.put("protection", protectionWarning); //$NON-NLS-1$
                }

                // Show first 50 lines of preview
                StringBuilder preview = new StringBuilder();
                preview.append("## Dry Run Preview\n\n"); //$NON-NLS-1$
                if (protectionWarning != null)
                {
                    preview.append("**").append(protectionWarning).append("**\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                preview.append("Lines: ").append(totalOriginal) //$NON-NLS-1$
                    .append(" -> ").append(newLines.size()).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

                return dryFm.wrapContent(preview.toString());
            }

            // 10. BSL syntax check
            if (!skipSyntaxCheck)
            {
                BslSyntaxChecker.CheckResult checkResult = BslSyntaxChecker.check(newLines);
                if (!checkResult.isValid())
                {
                    StringBuilder sb = new StringBuilder();
                    sb.append("Error: BSL syntax check failed. Write blocked.\n\n"); //$NON-NLS-1$
                    sb.append("**Errors:**\n"); //$NON-NLS-1$
                    for (String error : checkResult.getErrors())
                    {
                        sb.append("- ").append(error).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    sb.append("\nPass skipSyntaxCheck=true to force write."); //$NON-NLS-1$
                    return sb.toString();
                }
            }

            // 11. Write file
            writeFile(file, newLines, hasBom, fileExists);

            // 11b. Persistence sync - force BM to flush in-memory module to disk index
            //      so subsequent reads (validation, F7, deploy) see the new content.
            //      This is the prerequisite for reliable validateAfterWrite below.
            String moduleFqn = resolveFqnForValidation(objectName, modulePath);
            PersistenceResult persistence = forceExportModule(project, moduleFqn);

            // 12. Optional EDT validation feedback
            FileMarkers.Grouped validation = null;
            if (validateAfterWrite)
            {
                validation = collectValidation(project, objectName, modulePath);
            }

            // 13. Build frontmatter
            FrontMatter fm = FrontMatter.create()
                .put("tool", NAME) //$NON-NLS-1$
                .put("projectName", projectName) //$NON-NLS-1$
                .put("modulePath", modulePath) //$NON-NLS-1$
                .put("mode", mode) //$NON-NLS-1$
                .put("status", "success") //$NON-NLS-1$ //$NON-NLS-2$
                .put("linesAfter", newLines.size()) //$NON-NLS-1$
                .put("syntaxCheck", skipSyntaxCheck ? "skipped" : "passed"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

            if (fileExists)
            {
                fm.put("linesBefore", totalOriginal); //$NON-NLS-1$
            }
            else
            {
                fm.put("newFile", true); //$NON-NLS-1$
            }

            if (protectionWarning != null)
            {
                fm.put("protection", protectionWarning); //$NON-NLS-1$
            }

            if (validation != null)
            {
                fm.put("validationErrors", validation.errorCount()); //$NON-NLS-1$
                fm.put("validationWarnings", validation.warningCount()); //$NON-NLS-1$
                fm.put("validationCodeStyle", validation.codeStyleCount()); //$NON-NLS-1$
                fm.put("validationHint", buildValidationHint(validation)); //$NON-NLS-1$
            }

            fm.put("persistenceSyncOk", persistence.ok); //$NON-NLS-1$
            fm.put("persistenceSyncMs", persistence.elapsedMs); //$NON-NLS-1$
            if (persistence.detail != null && !persistence.detail.isEmpty())
            {
                fm.put("persistenceSyncDetail", persistence.detail); //$NON-NLS-1$
            }

            // 14. Return success
            StringBuilder body = new StringBuilder("File written successfully"); //$NON-NLS-1$
            if (protectionWarning != null)
            {
                body.append("\n\n**").append(protectionWarning).append("**"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (validation != null)
            {
                appendValidationSection(body, validation);
            }
            return fm.wrapContent(body.toString());
        }
        catch (Exception e)
        {
            return "Error writing file: " + e.getMessage(); //$NON-NLS-1$
        }
    }

    /**
     * Result of {@link #forceExportModule(IProject, String)}.
     */
    private static final class PersistenceResult
    {
        boolean ok;
        long elapsedMs;
        String detail; // human-readable diagnostic when not ok or skipped
    }

    /**
     * Delegates to {@link BmExportHelper#forceExportAndWait} so that every
     * write triggers the same wait-for-segment behaviour as the rest of the
     * BM-mutating tools (1.34+). Falls back to a "skipped" persistence result
     * when {@code IBmModelManager} is unavailable or no FQN can be resolved.
     */
    private PersistenceResult forceExportModule(IProject project, String moduleFqn)
    {
        PersistenceResult result = new PersistenceResult();
        long start = System.currentTimeMillis();

        if (project == null || moduleFqn == null || moduleFqn.isEmpty())
        {
            result.ok = false;
            result.detail = "skipped: project or moduleFqn unavailable"; //$NON-NLS-1$
            result.elapsedMs = System.currentTimeMillis() - start;
            return result;
        }

        IBmModelManager mgr = Activator.getDefault().getBmModelManager();
        if (mgr == null)
        {
            result.ok = false;
            result.detail = "skipped: IBmModelManager not available"; //$NON-NLS-1$
            result.elapsedMs = System.currentTimeMillis() - start;
            return result;
        }

        BmExportHelper.Result r = BmExportHelper.forceExportAndWait(mgr, project, moduleFqn);
        result.ok = r.isOk();
        result.elapsedMs = r.totalMs;
        if (r.error != null)
        {
            result.detail = r.error;
        }
        else if (!r.waitComputationOk)
        {
            result.detail = "forceExport ok, waitComputation timed out (" //$NON-NLS-1$
                + r.waitComputationMs + " ms)"; //$NON-NLS-1$
        }
        return result;
    }

    /**
     * Collects EDT validation markers for the just-written module.
     * <p>
     * Resolves a target FQN to filter markers by {@code marker.getObjectPresentation()}.
     * Uses {@code objectName} when supplied; otherwise infers from {@code modulePath}.
     * Performs up to 3 short polls with 300ms sleep to allow EDT to publish markers
     * after the file write.
     */
    private FileMarkers.Grouped collectValidation(IProject project,
        String objectName, String modulePath)
    {
        try
        {
            IMarkerManager markerManager = Activator.getDefault().getMarkerManager();
            if (markerManager == null)
            {
                return null;
            }

            String fqn = resolveFqnForValidation(objectName, modulePath);
            if (fqn == null || fqn.isEmpty())
            {
                return null;
            }

            // Up to 3 polls to give EDT time to refresh markers after disk write
            List<FileMarkers.MarkerInfo> markers = null;
            for (int attempt = 0; attempt < 3; attempt++)
            {
                if (attempt > 0)
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
                }
                markers = FileMarkers.getMarkersByObjectPresentation(
                    markerManager, project, fqn, null, 200);
                if (!markers.isEmpty())
                {
                    break;
                }
            }
            return FileMarkers.groupBySeverity(markers != null ? markers : new ArrayList<>());
        }
        catch (Exception e)
        {
            Activator.logWarning("validateAfterWrite skipped: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Builds a marker-filter FQN. Prefers {@code objectName} (e.g. "Document.MyDoc"),
     * falls back to inferring "Type.Name" from {@code modulePath}
     * (e.g. "Documents/MyDoc/ObjectModule.bsl" -> "Document.MyDoc").
     */
    private String resolveFqnForValidation(String objectName, String modulePath)
    {
        if (objectName != null && !objectName.isEmpty())
        {
            return objectName;
        }
        if (modulePath == null || modulePath.isEmpty())
        {
            return null;
        }
        String[] parts = modulePath.replace('\\', '/').split("/"); //$NON-NLS-1$
        if (parts.length < 2)
        {
            return null;
        }
        String dirName = parts[0];
        String namePart = parts[1];
        String typePart = MetadataTypeUtils.getTypeByDirectoryName(dirName);
        if (typePart == null)
        {
            return null;
        }
        return typePart + "." + namePart; //$NON-NLS-1$
    }

    private String buildValidationHint(FileMarkers.Grouped g)
    {
        if (g.isEmpty())
        {
            return "No problems found"; //$NON-NLS-1$
        }
        StringBuilder sb = new StringBuilder();
        if (g.errorCount() > 0)
        {
            sb.append(g.errorCount()).append(" error(s) - must fix before continuing. "); //$NON-NLS-1$
        }
        if (g.warningCount() > 0)
        {
            sb.append(g.warningCount()).append(" warning(s) - review and decide. "); //$NON-NLS-1$
        }
        if (g.codeStyleCount() > 0)
        {
            sb.append(g.codeStyleCount()).append(" style hint(s) - optional. "); //$NON-NLS-1$
        }
        return sb.toString().trim();
    }

    private void appendValidationSection(StringBuilder body, FileMarkers.Grouped g)
    {
        if (g.isEmpty())
        {
            body.append("\n\n## Validation\n\nNo problems found."); //$NON-NLS-1$
            return;
        }
        body.append("\n\n## Validation\n"); //$NON-NLS-1$
        appendMarkerList(body, "Errors", g.errors); //$NON-NLS-1$
        appendMarkerList(body, "Warnings", g.warnings); //$NON-NLS-1$
        appendMarkerList(body, "Code style", g.codeStyle); //$NON-NLS-1$
    }

    private void appendMarkerList(StringBuilder body, String title,
        List<FileMarkers.MarkerInfo> list)
    {
        if (list.isEmpty())
        {
            return;
        }
        body.append("\n### ").append(title).append(" (").append(list.size()).append(")\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (FileMarkers.MarkerInfo m : list)
        {
            body.append("- "); //$NON-NLS-1$
            if (m.line > 0)
            {
                body.append("L").append(m.line).append(" "); //$NON-NLS-1$ //$NON-NLS-2$
            }
            body.append(m.message);
            if (m.checkId != null && !m.checkId.isEmpty())
            {
                body.append(" `").append(m.checkId).append("`"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            body.append("\n"); //$NON-NLS-1$
        }
    }

    /**
     * Counts the number of occurrences of a substring in a string.
     */
    private int countOccurrences(String text, String search)
    {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(search, idx)) >= 0)
        {
            count++;
            idx++;
        }
        return count;
    }

    /**
     * Resolves objectName + moduleType to a module file path relative to src/.
     */
    private String resolveModulePath(String objectName, String moduleType,
        String formName, String commandName)
    {
        // Parse objectName: "Document.MyDoc" -> typePart="Document", namePart="MyDoc"
        int dotIndex = objectName.indexOf('.'); //$NON-NLS-1$
        if (dotIndex <= 0 || dotIndex >= objectName.length() - 1)
        {
            return "Error: objectName must be in format 'Type.Name' " + //$NON-NLS-1$
                "(e.g. 'Document.MyDoc', 'CommonModule.MyModule')"; //$NON-NLS-1$
        }

        String typePart = objectName.substring(0, dotIndex);
        String namePart = objectName.substring(dotIndex + 1);

        // Resolve type to English singular
        String englishType = MetadataTypeUtils.toEnglishSingular(typePart);
        if (englishType == null)
        {
            return "Error: unknown metadata type: " + typePart; //$NON-NLS-1$
        }

        // Get directory name
        String dirName = MetadataTypeUtils.getDirectoryName(typePart);
        if (dirName == null)
        {
            return "Error: metadata type '" + typePart + "' has no source directory"; //$NON-NLS-1$ //$NON-NLS-2$
        }

        // Determine default moduleType based on metadata type
        if (moduleType == null || moduleType.isEmpty())
        {
            if ("CommonModule".equals(englishType) //$NON-NLS-1$
                || "CommonForm".equals(englishType) //$NON-NLS-1$
                || "WebService".equals(englishType) //$NON-NLS-1$
                || "HTTPService".equals(englishType)) //$NON-NLS-1$
            {
                moduleType = "Module"; //$NON-NLS-1$
            }
            else if ("CommonCommand".equals(englishType)) //$NON-NLS-1$
            {
                moduleType = "CommandModule"; //$NON-NLS-1$
            }
            else
            {
                moduleType = "ObjectModule"; //$NON-NLS-1$
            }
        }

        // Build path based on moduleType
        switch (moduleType)
        {
            case "Module": //$NON-NLS-1$
                return dirName + "/" + namePart + "/Module.bsl"; //$NON-NLS-1$ //$NON-NLS-2$

            case "ObjectModule": //$NON-NLS-1$
                return dirName + "/" + namePart + "/ObjectModule.bsl"; //$NON-NLS-1$ //$NON-NLS-2$

            case "ManagerModule": //$NON-NLS-1$
                return dirName + "/" + namePart + "/ManagerModule.bsl"; //$NON-NLS-1$ //$NON-NLS-2$

            case "RecordSetModule": //$NON-NLS-1$
                return dirName + "/" + namePart + "/RecordSetModule.bsl"; //$NON-NLS-1$ //$NON-NLS-2$

            case "FormModule": //$NON-NLS-1$
                // CommonForms don't need formName — path is always Module.bsl
                if ("CommonForm".equals(englishType)) //$NON-NLS-1$
                {
                    return dirName + "/" + namePart + "/Module.bsl"; //$NON-NLS-1$ //$NON-NLS-2$
                }
                if (formName == null || formName.isEmpty())
                {
                    return "Error: formName is required when moduleType=FormModule"; //$NON-NLS-1$
                }
                return dirName + "/" + namePart + "/Forms/" + formName + "/Module.bsl"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

            case "CommandModule": //$NON-NLS-1$
                // CommonCommands don't need commandName — path is always CommandModule.bsl
                if ("CommonCommand".equals(englishType)) //$NON-NLS-1$
                {
                    return dirName + "/" + namePart + "/CommandModule.bsl"; //$NON-NLS-1$ //$NON-NLS-2$
                }
                if (commandName == null || commandName.isEmpty())
                {
                    return "Error: commandName is required when moduleType=CommandModule"; //$NON-NLS-1$
                }
                return dirName + "/" + namePart + "/Commands/" + commandName + "/CommandModule.bsl"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

            default:
                return "Error: unknown moduleType: " + moduleType + //$NON-NLS-1$
                    ". Allowed: ObjectModule, ManagerModule, FormModule, " + //$NON-NLS-1$
                    "CommandModule, RecordSetModule, Module"; //$NON-NLS-1$
        }
    }

    /**
     * Splits source code into lines, handling trailing newline artifact.
     */
    private List<String> splitSourceLines(String source)
    {
        if (source.isEmpty())
        {
            return new ArrayList<>();
        }

        String[] parts = source.split("\n", -1); //$NON-NLS-1$
        List<String> lines = new ArrayList<>(Arrays.asList(parts));

        // If source ends with \n, split produces a trailing empty element.
        // Remove it to avoid adding an extra blank line.
        if (source.endsWith("\n") && lines.size() > 1 //$NON-NLS-1$
            && lines.get(lines.size() - 1).isEmpty())
        {
            lines.remove(lines.size() - 1);
        }

        return lines;
    }

    /**
     * Detects if the file starts with UTF-8 BOM.
     */
    private boolean detectBom(IFile file)
    {
        try (InputStream is = file.getContents();
             BufferedInputStream bis = new BufferedInputStream(is))
        {
            byte[] bom = new byte[3];
            int read = bis.read(bom);
            return read == 3
                && (bom[0] & 0xFF) == 0xEF
                && (bom[1] & 0xFF) == 0xBB
                && (bom[2] & 0xFF) == 0xBF;
        }
        catch (Exception e)
        {
            // Default: assume BOM for BSL files
            return true;
        }
    }

    /**
     * Writes lines to the file, preserving BOM if needed.
     */
    private void writeFile(IFile file, List<String> lines, boolean withBom,
        boolean fileExists) throws Exception
    {
        String content = String.join("\n", lines); //$NON-NLS-1$

        // Ensure file ends with newline
        if (!content.endsWith("\n")) //$NON-NLS-1$
        {
            content += "\n"; //$NON-NLS-1$
        }

        byte[] contentBytes = content.getBytes("UTF-8"); //$NON-NLS-1$

        byte[] output;
        if (withBom)
        {
            output = new byte[UTF8_BOM.length + contentBytes.length];
            System.arraycopy(UTF8_BOM, 0, output, 0, UTF8_BOM.length);
            System.arraycopy(contentBytes, 0, output, UTF8_BOM.length, contentBytes.length);
        }
        else
        {
            output = contentBytes;
        }

        InputStream stream = new ByteArrayInputStream(output);

        if (fileExists)
        {
            file.setContents(stream, IResource.FORCE | IResource.KEEP_HISTORY, null);
        }
        else
        {
            // Create parent directories if needed
            createParentFolders(file);
            file.create(stream, true, null);
        }
    }

    /**
     * Recursively creates parent folders for the given file.
     */
    private void createParentFolders(IFile file) throws Exception
    {
        IFolder parent = (IFolder)file.getParent();
        if (parent != null && !parent.exists())
        {
            createFolder(parent);
        }
    }

    /**
     * Recursively creates a folder and its parents.
     */
    private void createFolder(IFolder folder) throws Exception
    {
        if (folder.exists())
        {
            return;
        }
        if (folder.getParent() instanceof IFolder)
        {
            IFolder parentFolder = (IFolder)folder.getParent();
            if (!parentFolder.exists())
            {
                createFolder(parentFolder);
            }
        }
        folder.create(true, true, null);
    }
}
