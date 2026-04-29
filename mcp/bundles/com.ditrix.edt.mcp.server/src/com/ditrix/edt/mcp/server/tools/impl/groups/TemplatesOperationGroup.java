/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl.groups;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Print form templates group (4 operations) - addTemplate, setTemplateCell,
 * mergeTemplateCells, drawTemplate. <p>
 *
 * Backed by an extended {@code BmTemplateHelper} (probe pattern already in
 * place from 1.34, real cell-level mutation lands in 1.40 via
 * {@code SpreadsheetDocument} + {@code ITemplateLayoutService}). When EDT
 * runtime is missing the layout service, operations return graceful
 * {@code mxlApiNotFound} error tag with a GUI-fallback hint.
 */
public final class TemplatesOperationGroup implements OperationGroup
{
    private static final Set<String> NAMES = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
        "addTemplate",
        "setTemplateCell",
        "mergeTemplateCells",
        "drawTemplate"
    )));

    private static final Map<String, String> CATALOG;
    static
    {
        Map<String, String> c = new LinkedHashMap<>();
        c.put("addTemplate",
            "Create a template. 10 supported types (Spreadsheet/Text/DCS/BinaryData/HTML/Geo/Graph/ActiveDocument/ExternalComponent/AppearanceTemplate).");
        c.put("setTemplateCell",
            "Set the content of one spreadsheet cell: text, font, color, alignment, parameter mark.");
        c.put("mergeTemplateCells",
            "Merge a range of spreadsheet cells (from column A row N to column B row M).");
        c.put("drawTemplate",
            "Batch fill a spreadsheet template: cells array + merges + column widths + row heights + named areas (for ПолучитьОбласть).");
        CATALOG = Collections.unmodifiableMap(c);
    }

    private final BiFunction<String, Map<String, String>, String> delegate;

    public TemplatesOperationGroup(BiFunction<String, Map<String, String>, String> delegate)
    {
        this.delegate = delegate;
    }

    @Override
    public Set<String> getOperationNames()
    {
        return NAMES;
    }

    @Override
    public String dispatch(String op, Map<String, String> params)
    {
        return delegate.apply(op, params);
    }

    @Override
    public Map<String, String> getCatalog()
    {
        return CATALOG;
    }
}
