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
 * Specialized object operations group:
 * register fields (4 register kinds), enum values, subsystem content,
 * role rights, defined type composition, event subscription handlers.
 *
 * <p>1.40 helpers:
 * <ul>
 *   <li>{@code BmRightsHelper} for {@code setRoleRight} (Role + child element rights)</li>
 *   <li>{@code BmDefinedTypeHelper} for {@code setDefinedTypeTypes}</li>
 *   <li>{@code BmSubsystemHelper} for {@code addSubsystemContent}/{@code removeSubsystemContent}</li>
 *   <li>{@code BmEventSubscriptionHelper} for {@code addEventSubscriptionHandler} (with handler auto-prefix 3.8.1)</li>
 *   <li>extended {@code BmRegisterHelper} for register fields of all 4 kinds + chart-of-accounts indicators</li>
 * </ul>
 *
 * Snake_case aliases (add_register_field / set_role_right / etc.) are kept in
 * EditMetadataTool's OPERATIONS map for backwards compatibility with skills.
 */
public final class SpecializedOperationGroup implements OperationGroup
{
    private static final Set<String> NAMES = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
        "addRegisterField",
        "removeRegisterField",
        "addEnumValue",
        "addSubsystemContent",
        "removeSubsystemContent",
        "setRoleRight",
        "setDefinedTypeTypes",
        "addEventSubscriptionHandler"
    )));

    private static final Map<String, String> CATALOG;
    static
    {
        Map<String, String> c = new LinkedHashMap<>();
        c.put("addRegisterField",
            "Add a field (resource/dimension/attribute) to a register or chart-of-accounts indicator.");
        c.put("removeRegisterField",
            "Remove a register field.");
        c.put("addEnumValue",
            "Add a value to an Enumeration. Batch supported.");
        c.put("addSubsystemContent",
            "Include an object in subsystem content. Idempotent.");
        c.put("removeSubsystemContent",
            "Remove an object from subsystem content.");
        c.put("setRoleRight",
            "Set role right on object/attribute/tabular section/column/command. Russian and English right names.");
        c.put("setDefinedTypeTypes",
            "Set the type composition of a DefinedType (array of FQNs).");
        c.put("addEventSubscriptionHandler",
            "Add a stub procedure to a common module for an EventSubscription handler. Idempotent.");
        CATALOG = Collections.unmodifiableMap(c);
    }

    private final BiFunction<String, Map<String, String>, String> delegate;

    public SpecializedOperationGroup(BiFunction<String, Map<String, String>, String> delegate)
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
