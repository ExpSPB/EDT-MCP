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
 * Common (cross-cutting) operations group: {@code moveItem}, {@code removeItem}.
 * <p>
 * These are universal dispatchers - they detect context (form item / settings
 * tree / metadata object) by the FQN shape and delegate to the appropriate
 * specialized helper.
 */
public final class CommonOperationGroup implements OperationGroup
{
    private static final Set<String> NAMES = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
        "moveItem",
        "removeItem"
    )));

    private static final Map<String, String> CATALOG;
    static
    {
        Map<String, String> c = new LinkedHashMap<>();
        c.put("moveItem",
            "Move an element to another container at a specific position. Universal across forms, settings, content lists.");
        c.put("removeItem",
            "Remove an element by name. Universal - context detected from FQN shape.");
        CATALOG = Collections.unmodifiableMap(c);
    }

    private final BiFunction<String, Map<String, String>, String> delegate;

    public CommonOperationGroup(BiFunction<String, Map<String, String>, String> delegate)
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
