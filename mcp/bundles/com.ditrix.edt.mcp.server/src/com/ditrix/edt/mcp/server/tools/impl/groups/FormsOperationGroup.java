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
 * Form operations group: 15 operations covering form creation,
 * attributes (including dynamic-list columns), elements (field/group/button/
 * table/decoration/radioButton), property modifications (with formatHelp for
 * format/editFormat, picture-without-representation guard), command handlers,
 * picture lookup, and the SettingsComposer-on-form scaffold.
 *
 * <p>1.40 enhancements:
 * <ul>
 *   <li>{@code FormBaseSetup.applyDefaults(Form)} - 11 base properties for
 *       Generic+empty layout (defensive layer 3.8.3)</li>
 *   <li>22 stock button commands with auto-icon (PostAndClose, Write, Copy,
 *       SetDeletionMark, Generate, Refresh, ...)</li>
 *   <li>{@code formatHelp} field in setProperty response when format/editFormat is set</li>
 *   <li>"picture without representation" warning for buttons</li>
 *   <li>{@code listPictures} - search across 763 stock EDT pictures + CommonPicture.*</li>
 *   <li>{@code setupSettingsComposerOnForm} - SettingsComposer attribute + UI tables + ExtInfo
 *       + ready-to-use BSL example for ПриСозданииНаСервере</li>
 * </ul>
 *
 * <p>Migrated from the legacy {@code EditFormTool} (1.32) which becomes a
 * deprecated alias in 1.40 - operation names {@code addField/addGroup/addButton/
 * addTable/addDecoration/removeItem} already match camelCase convention, so the alias
 * delegates without renaming.
 */
public final class FormsOperationGroup implements OperationGroup
{
    private static final Set<String> NAMES = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
        "createForm",
        "addFormAttribute",
        "addFormAttributeColumn",
        "addDynamicListTable",
        "addField",
        "addGroup",
        "addButton",
        "addTable",
        "addDecoration",
        "addRadioButton",
        "setProperty",
        "setFormItemProperty",
        "addCommandHandler",
        "addFormCommand",
        "listPictures",
        "setupSettingsComposerOnForm",
        "removeFormItem"
    )));

    private static final Map<String, String> CATALOG;
    static
    {
        Map<String, String> c = new LinkedHashMap<>();
        c.put("createForm",
            "Create a form via the EDT generator. All form types. setAsDefault=true marks it default.");
        c.put("addFormAttribute",
            "Add a form attribute (filter/temporary table/dynamic list).");
        c.put("addFormAttributeColumn",
            "1.41: Add a column to an existing form table-attribute. Idempotent. "
                + "Surfaces formApiNotFound tag when EDT does not expose "
                + "FormFactory.createFormAttributeColumn.");
        c.put("addDynamicListTable",
            "1.41: Add a FormAttribute(DynamicList) plus a UI Table bound to it. "
                + "Sets mainTable, autoSaveCustomization=true, dynamicDataRead=true, "
                + "customQuery=false on the ExtInfo (best-effort).");
        c.put("addField",
            "Add an input field (type auto-detected from data path).");
        c.put("addGroup",
            "Add a group: usual / pages / page / column / command bar / button group.");
        c.put("addButton",
            "Add a button with a custom command OR a stock platform command (22 supported with auto-icon).");
        c.put("addTable",
            "Add a table for a tabular section or attribute-table (columns auto-detected).");
        c.put("addDecoration",
            "Add a decoration (label - picture decoration is deferred).");
        c.put("addRadioButton",
            "Add a radio button (dots or toggle) with arbitrary variants.");
        c.put("setProperty",
            "Universal property setter for any form item. format/editFormat returns formatHelp tag.");
        c.put("setFormItemProperty",
            "Alias for setProperty (legacy name).");
        c.put("addCommandHandler",
            "Create a button command handler with the right directive (supports &Before/&After/&Instead for adopted forms).");
        c.put("addFormCommand",
            "Alias for addCommandHandler (legacy name).");
        c.put("listPictures",
            "Search across 763 stock EDT pictures by name. CommonPicture.* used directly without lookup.");
        c.put("setupSettingsComposerOnForm",
            "1.41: Add a DataCompositionSettingsComposer FormAttribute + Settings/UserSettings UI tables + ExtInfo. "
                + "Response includes RU and EN BSL snippets ready to paste into ProcedureOnCreateAtServer.");
        c.put("removeFormItem",
            "Remove a form item by name.");
        CATALOG = Collections.unmodifiableMap(c);
    }

    private final BiFunction<String, Map<String, String>, String> delegate;

    public FormsOperationGroup(BiFunction<String, Map<String, String>, String> delegate)
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
