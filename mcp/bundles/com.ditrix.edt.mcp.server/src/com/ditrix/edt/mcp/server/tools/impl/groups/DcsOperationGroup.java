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
 * DCS report operations group (27 ops) - schema, data sets, parameters,
 * calculated/total fields, settings (groups/tables/charts/filters/orders/
 * appearance), variants, output parameters, repair. <p>
 *
 * Operations work not only on Reports but on any object with DCS-typed
 * templates (DataProcessor / Catalog / Document / ChartOfAccounts / BusinessProcess
 * / Task / ExchangePlan), addressed via the {@code templateName} parameter.
 *
 * <p>1.40 highlights:
 * <ul>
 *   <li>{@code addSettingsFilter} - 20+ comparison types (Equal, NotEqual, Filled,
 *       Contains, NotContains, Greater/Less, In/NotIn, ...)</li>
 *   <li>{@code setSettingsParameter} with {@code viewMode=QuickAccess} surfaces
 *       the parameter in the report header</li>
 *   <li>{@code addSettingsVariant} - deep copy of current settings</li>
 *   <li>{@code repairReportSchema} - reload from disk via the existing
 *       {@code DcsExtensionExportHelper}/{@code DcsExtensionImportHelper}
 *       pair (useful for "phantom" schemas in extensions where the editor
 *       preview is empty or deploy reports "unknown metadata object")</li>
 * </ul>
 *
 * <p>All 27 operations route through the existing
 * {@code BmDcsHelper.executeWriteOnSchema()}; per-op delta is a small
 * {@code applyMutation()} lambda (5-15 LOC).
 */
public final class DcsOperationGroup implements OperationGroup
{
    private static final Set<String> NAMES = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
        // basics
        "createReportSchema",
        "addDataSet",
        "removeDataSet",
        "addDataSetField",
        // parameters
        "addSchemaParameter",
        "setSchemaParameter",
        "removeSchemaParameter",
        "moveSchemaParameter",
        // calculations
        "addCalculatedField",
        "addTotalField",
        "addUserField",
        // settings structure
        "addSettingsGroup",
        "addSettingsTable",
        "addSettingsChart",
        "addSettingsSelectedField",
        "removeSettingsSelectedField",
        // filters/orders
        "addSettingsFilter",
        "addSettingsFilterGroup",
        "addSettingsOrder",
        // appearance
        "addConditionalAppearance",
        "removeConditionalAppearance",
        "setDataSetFieldAppearance",
        // output + variants
        "setSettingsParameter",
        "setOutputParameter",
        "addSettingsVariant",
        "removeSettingsItem",
        // service
        "repairReportSchema"
    )));

    private static final Map<String, String> CATALOG;
    static
    {
        Map<String, String> c = new LinkedHashMap<>();
        c.put("createReportSchema",
            "Create a DCS schema with a basic skeleton (data source, default settings variant, auto-fields).");
        c.put("addDataSet", "Add a data set (Query / Object / Union).");
        c.put("removeDataSet", "Remove a data set by name.");
        c.put("addDataSetField", "Add a field to a data set.");
        c.put("addSchemaParameter",
            "Add a schema parameter (type, length, default expression, list-of-values flag).");
        c.put("setSchemaParameter",
            "Modify an existing schema parameter (only passed fields).");
        c.put("removeSchemaParameter", "Remove a schema parameter.");
        c.put("moveSchemaParameter", "Reorder a schema parameter (Up/Down or explicit position).");
        c.put("addCalculatedField", "Add a calculated field (expression over other fields).");
        c.put("addTotalField", "Add a total resource (Sum/Count/Maximum/...).");
        c.put("addUserField",
            "1.41 native: Add a user-defined calculated field at Schema-level "
                + "(falls back to CalculatedFields collection when UserFields is missing).");
        c.put("addSettingsGroup", "Add a grouping (regular or detailed records).");
        c.put("addSettingsTable",
            "1.41 native: Add a table grouping at Settings.Structure level.");
        c.put("addSettingsChart",
            "1.41 native: Add a chart at Settings.Structure level.");
        c.put("addSettingsSelectedField",
            "1.41 native: Add a selected field at Settings.Selection level.");
        c.put("removeSettingsSelectedField",
            "1.41 native: Remove a selected field by field name.");
        c.put("addSettingsFilter", "Add a filter element (20+ comparison types).");
        c.put("addSettingsFilterGroup",
            "1.41 native: Add a FilterItemGroup container for nested AND/OR groups.");
        c.put("addSettingsOrder",
            "1.41 native: Add an order (Asc/Desc) at Settings.Order level.");
        c.put("addConditionalAppearance",
            "Add conditional formatting (format, colors, font, paddings).");
        c.put("removeConditionalAppearance",
            "1.41 native: Remove a conditional appearance entry by index "
                + "(target=schema|settings).");
        c.put("setDataSetFieldAppearance",
            "1.41 native: Set static field appearance (font, textColor, backColor, "
                + "horizontalAlignment, verticalAlignment, border).");
        c.put("setSettingsParameter",
            "1.41 native: Overwrite an existing data parameter value at "
                + "Settings.DataParameters level.");
        c.put("setOutputParameter",
            "1.41 native: Set a Schema.OutputParameters value by name.");
        c.put("addSettingsVariant",
            "1.41 native: Add a variant at Schema.Variants level.");
        c.put("removeSettingsItem",
            "1.41 native: Universal cascade-remove by itemPath like "
                + "'Structure[0].Filter[2]'. Removes the EObject and detaches "
                + "all descendants.");
        c.put("repairReportSchema",
            "Reload a DCS schema from disk and refresh the BM tree (fix \"phantom\" schemas in extensions).");
        CATALOG = Collections.unmodifiableMap(c);
    }

    private final BiFunction<String, Map<String, String>, String> delegate;

    public DcsOperationGroup(BiFunction<String, Map<String, String>, String> delegate)
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
