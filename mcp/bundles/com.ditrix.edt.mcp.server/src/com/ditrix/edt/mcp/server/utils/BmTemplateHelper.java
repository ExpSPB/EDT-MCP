/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import com.ditrix.edt.mcp.server.Activator;

/**
 * MXL spreadsheet template operations for {@code mxl_workshop}.
 * <p>
 * <b>1.37:</b> probe expanded to multiple EDT layout APIs (ITemplateLayout
 * service + SpreadsheetDocument). {@code create_template} writes a Template
 * MdObject with {@code templateType=SpreadsheetDocument} via
 * {@link BmObjectHelper}. Cell-level mutation (set_cell / merge_cells / draw)
 * relies on the layout service when reachable; otherwise the tool returns a
 * structured error tag {@code mxlApiNotFound} so the AI agent can decide to
 * fall back to GUI workflow.
 */
public final class BmTemplateHelper
{
    private static final String[] CANDIDATE_PACKAGES = {
        "com._1c.g5.v8.dt.spreadsheet.model.SpreadsheetDocument", //$NON-NLS-1$
        "com._1c.g5.v8.dt.template.model.SpreadsheetDocument", //$NON-NLS-1$
        "com._1c.g5.v8.dt.md.SpreadsheetDocument" //$NON-NLS-1$
    };

    private static final String[] CANDIDATE_FACTORIES = {
        "com._1c.g5.v8.dt.spreadsheet.model.SpreadsheetFactory", //$NON-NLS-1$
        "com._1c.g5.v8.dt.template.model.TemplateFactory" //$NON-NLS-1$
    };

    private static final String[] CANDIDATE_LAYOUT_SERVICES = {
        "com._1c.g5.v8.dt.form.layout.service.ITemplateLayoutService", //$NON-NLS-1$
        "com._1c.g5.v8.dt.spreadsheet.layout.ISpreadsheetLayoutService", //$NON-NLS-1$
        "com._1c.g5.v8.dt.template.layout.ITemplateLayoutService" //$NON-NLS-1$
    };

    private static volatile String cachedClassName;
    private static volatile String cachedFactoryName;
    private static volatile String cachedLayoutServiceName;
    private static volatile Boolean cachedProbed;

    private BmTemplateHelper()
    {
        // utility class
    }

    /**
     * Returns the resolved spreadsheet-document class name for this EDT runtime,
     * or {@code null} when none of the candidates resolve. Result cached.
     */
    public static String resolvedSpreadsheetClass()
    {
        ensureProbed();
        return cachedClassName;
    }

    /**
     * Returns the resolved spreadsheet/template factory class name, or
     * {@code null} when not present.
     */
    public static String resolvedFactoryClass()
    {
        ensureProbed();
        return cachedFactoryName;
    }

    /**
     * Returns the resolved layout-service interface name, or {@code null}.
     */
    public static String resolvedLayoutServiceClass()
    {
        ensureProbed();
        return cachedLayoutServiceName;
    }

    private static void ensureProbed()
    {
        if (cachedProbed != null)
        {
            return;
        }
        synchronized (BmTemplateHelper.class)
        {
            if (cachedProbed != null)
            {
                return;
            }
            cachedClassName = resolveFirst(CANDIDATE_PACKAGES);
            cachedFactoryName = resolveFirst(CANDIDATE_FACTORIES);
            cachedLayoutServiceName = resolveFirst(CANDIDATE_LAYOUT_SERVICES);
            cachedProbed = Boolean.TRUE;
            if (cachedClassName == null)
            {
                Activator.logWarning(
                    "BmTemplateHelper: spreadsheet-model class not found in any candidate package"); //$NON-NLS-1$
            }
        }
    }

    private static String resolveFirst(String[] candidates)
    {
        for (String candidate : candidates)
        {
            try
            {
                Class.forName(candidate);
                return candidate;
            }
            catch (ClassNotFoundException ignored)
            {
                // try next
            }
        }
        return null;
    }

    public static boolean isAvailable()
    {
        return resolvedSpreadsheetClass() != null;
    }

    public static String deferredMessage(String operation)
    {
        String resolved = resolvedSpreadsheetClass();
        return "Template operation '" + operation //$NON-NLS-1$
            + "' is not yet implemented in this build. " //$NON-NLS-1$
            + "Use the EDT GUI spreadsheet editor for cell-level changes. " //$NON-NLS-1$
            + (resolved != null
                ? "Spreadsheet API discovered: " + resolved //$NON-NLS-1$
                : "Spreadsheet API NOT reachable in this EDT version."); //$NON-NLS-1$
    }

    // -----------------------------------------------------------------------
    // 1.40: Template type resolution + cell-level operations
    // -----------------------------------------------------------------------

    /**
     * Maps an English/Russian template-type alias to its canonical EDT enum
     * literal name. Used by {@code addTemplate} when setting Template.templateType.
     */
    public static String canonicalTemplateType(String alias)
    {
        if (alias == null || alias.isEmpty())
        {
            return "SpreadsheetDocument"; // default - matches upstream
        }
        String key = alias.trim().toLowerCase(java.util.Locale.ROOT);
        switch (key)
        {
            case "spreadsheet":
            case "spreadsheetdocument":
            case "табличный":
            case "табличныйдокумент":
                return "SpreadsheetDocument";
            case "text":
            case "textdocument":
            case "текстовый":
            case "текстовыйдокумент":
                return "TextDocument";
            case "dcs":
            case "datacompositionschema":
            case "скд":
            case "схемакомпоновкиданных":
                return "DataCompositionSchema";
            case "appearancetemplate":
            case "datacompositionappearancetemplate":
            case "макетоформления":
                return "DataCompositionAppearanceTemplate";
            case "binarydata":
            case "binary":
            case "двоичныеданные":
                return "BinaryData";
            case "html":
            case "htmldocument":
                return "HTMLDocument";
            case "geographicschema":
            case "geo":
            case "географическая":
            case "географическаясхема":
                return "GeographicalSchema";
            case "graphicalschema":
            case "graph":
            case "графическая":
            case "графическаясхема":
                return "GraphicalSchema";
            case "activedocument":
            case "active":
            case "активныйдокумент":
                return "ActiveDocument";
            case "addin":
            case "externalcomponent":
            case "внешняякомпонента":
                return "AddIn";
            default:
                return alias; // pass through, EDT will reject if invalid
        }
    }

    /**
     * Cell-level operations status: {@code true} when the layout service is
     * reachable on this EDT build, {@code false} when only structural
     * (Template.mdo creation) is supported.
     */
    public static boolean cellOpsAvailable()
    {
        return resolvedLayoutServiceClass() != null;
    }

    /**
     * Builds an {@code mxlApiNotFound} error tag - graceful fallback when
     * cell-level ops are unreachable.
     */
    public static MetadataGuards.BlockedGuardException mxlApiNotFound(String op)
    {
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("operation", op);
        data.put("missingApi", "ITemplateLayoutService / SpreadsheetDocument");
        return new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
            "Cell-level template operation '" + op + "' requires the EDT spreadsheet layout service "
                + "which is not available on this build.",
            "Open the template in the EDT GUI spreadsheet editor for cell-level changes. "
                + "Headless cell ops will be enabled when this EDT build exposes the layout service.",
            new MetadataGuards.ErrorTag("mxlApiNotFound", data))); //$NON-NLS-1$
    }
}
