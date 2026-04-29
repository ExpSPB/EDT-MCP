/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl.groups;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Object metadata operations group: createObject, setObjectProperty,
 * addObjectAttribute / removeObjectAttribute (with auto-cleanup of form items
 * referencing the removed attribute), addTabularSection / removeTabularSection,
 * addTabularSectionAttribute / removeTabularSectionAttribute. <p>
 *
 * 1.40 enhancements:
 * <ul>
 *   <li>idempotent skip with {@code propertyMismatch} tag when the same name
 *       is requested with different properties (via
 *       {@code BmObjectHelper.executeWriteOnObject} idempotencyProperties overload)</li>
 *   <li>cascade form cleanup at removeObjectAttribute /
 *       removeTabularSection / removeTabularSectionAttribute (requires
 *       {@code cascadeForms=true} or {@code force=true})</li>
 *   <li>{@code BmCommonFormPostCreate} post-step for {@code createObject CommonForm.X}
 *       (defensive layer 3.8.4)</li>
 *   <li>{@code BmCommonModuleGuards} pre-check for CommonModule in extension
 *       (defensive layer 3.8.2)</li>
 * </ul>
 *
 * <p>Implementation note (1.40 transitional): until {@code EditMetadataTool} is
 * fully refactored, this group acts as a thin delegator to the legacy
 * {@code opXxx} methods on {@code EditMetadataTool}. The delegate is supplied
 * by the constructor through a {@link BiFunction} {@code (op, params) -> json}
 * to avoid a hard reference cycle. Once the full refactor lands, this class
 * will own the implementations directly.
 */
public final class ObjectOperationGroup implements OperationGroup
{
    private static final Set<String> NAMES = Collections.unmodifiableSet(new LinkedHashSet<>(java.util.Arrays.asList(
        "createObject",
        "setObjectProperty",
        "addObjectAttribute",
        "removeObjectAttribute",
        "addTabularSection",
        "removeTabularSection",
        "addTabularSectionAttribute",
        "removeTabularSectionAttribute"
    )));

    private static final Map<String, String> CATALOG;
    static
    {
        Map<String, String> c = new LinkedHashMap<>();
        c.put("createObject",
            "Create a new metadata object (Catalog, Document, Enum, Register, etc.). 30+ supported types.");
        c.put("setObjectProperty",
            "Change an object property (hierarchy, code type, code/description length, posting flag, picture, etc.).");
        c.put("addObjectAttribute",
            "Add an attribute to an object (Catalog, Document, Register, etc.). Idempotent with propertyMismatch.");
        c.put("removeObjectAttribute",
            "Remove an attribute. With cascadeForms=true also cleans up form items that reference it.");
        c.put("addTabularSection",
            "Add a tabular section (with optional initial columns).");
        c.put("removeTabularSection",
            "Remove a tabular section (cascade-cleans form tables when cascadeForms=true).");
        c.put("addTabularSectionAttribute",
            "Add a column to an existing tabular section.");
        c.put("removeTabularSectionAttribute",
            "Remove a column from a tabular section (cascade-cleans form columns).");
        CATALOG = Collections.unmodifiableMap(c);
    }

    private final BiFunction<String, Map<String, String>, String> delegate;

    public ObjectOperationGroup(BiFunction<String, Map<String, String>, String> delegate)
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
