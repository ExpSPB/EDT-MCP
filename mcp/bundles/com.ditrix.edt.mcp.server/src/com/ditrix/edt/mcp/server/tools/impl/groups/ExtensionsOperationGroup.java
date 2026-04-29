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
 * Configuration extension operations group (5 ops) - object adoption from
 * the base configuration into the extension. <p>
 *
 * <ul>
 *   <li>{@code adoptObject} - adopt one object (with optional recursive=true)</li>
 *   <li>{@code adoptObjects} - batch adopt an explicit list (no auto-recursion)</li>
 *   <li>{@code adoptChild} - adopt a child element (attribute/TS/form/command/dimension/resource)</li>
 *   <li>{@code adoptFormItem} - adopt an item inside an adopted form</li>
 *   <li>{@code adoptModule} - enable module participation in extension (auto when write_module_source touches an adopted module)</li>
 * </ul>
 *
 * <p>1.40 defensive layers:
 * <ul>
 *   <li>3.8.1 - {@code BmEventSubscriptionHelper.normalizeHandler()} auto-prefixes
 *       {@code "Method"} to {@code "CommonModule.X.Method"} (rejected by platform on UpdateDBCfg)</li>
 *   <li>3.8.2 - {@code BmCommonModuleGuards.checkExtensionCommonModule()} - early-fail
 *       for {@code privileged=true} and {@code global=true+server=true} in extension projects</li>
 * </ul>
 */
public final class ExtensionsOperationGroup implements OperationGroup
{
    private static final Set<String> NAMES = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
        "adoptObject",
        "adoptObjects",
        "adoptChild",
        "adoptFormItem",
        "adoptModule"
    )));

    private static final Map<String, String> CATALOG;
    static
    {
        Map<String, String> c = new LinkedHashMap<>();
        c.put("adoptObject",
            "Adopt one object from the base configuration. With recursive=true also adopts all children.");
        c.put("adoptObjects",
            "Batch adopt an explicit list of objects (no auto-recursion). Result per object.");
        c.put("adoptChild",
            "Adopt a child element: attribute, tabular section, form, command, dimension, resource.");
        c.put("adoptFormItem",
            "Adopt an item inside an adopted form (main attribute, command, parameter).");
        c.put("adoptModule",
            "Enable module participation in extension (auto-applied by write_module_source when applicable).");
        CATALOG = Collections.unmodifiableMap(c);
    }

    private final BiFunction<String, Map<String, String>, String> delegate;

    public ExtensionsOperationGroup(BiFunction<String, Map<String, String>, String> delegate)
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
