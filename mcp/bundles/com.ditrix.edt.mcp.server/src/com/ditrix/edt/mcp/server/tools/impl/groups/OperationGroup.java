/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl.groups;

import java.util.Map;
import java.util.Set;

/**
 * Contract for a logical group of {@code edit_metadata} operations.
 * <p>
 * Each group owns a disjoint set of operation names (e.g. ObjectOperationGroup
 * owns createObject/setObjectProperty/addObjectAttribute/...; DcsOperationGroup
 * owns createReportSchema/addDataSet/...). The dispatcher in EditMetadataTool
 * iterates registered groups, finds the first one claiming the operation via
 * {@link #getOperationNames()}, and delegates to {@link #dispatch(String, Map)}.
 * <p>
 * Groups also publish a human-readable catalog (operation -&gt; one-line
 * description) for the {@code help} system through {@link #getCatalog()}.
 * <p>
 * Conventions:
 * <ul>
 *   <li>Operation names follow camelCase convention (e.g. {@code addObjectAttribute},
 *       not {@code add_object_attribute}). EditMetadataTool.OPERATIONS keeps a
 *       snake_case alias map for backwards compatibility with existing skills.</li>
 *   <li>{@code dispatch} returns a JSON string ({@link com.ditrix.edt.mcp.server.protocol.ToolResult}
 *       serialized) with success/error envelope, never plain text.</li>
 *   <li>Implementations must honour {@code dryRun} parameter when applicable
 *       and surface {@code propertyMismatch} / {@code idempotentSkip} tags.</li>
 *   <li>Groups should be stateless singletons - no per-call mutable fields.</li>
 * </ul>
 */
public interface OperationGroup
{
    /**
     * Returns the set of operation names this group claims to handle.
     * Names are camelCase, case-sensitive, must be disjoint across all groups.
     *
     * @return immutable set of operation names
     */
    Set<String> getOperationNames();

    /**
     * Dispatches an operation invocation.
     * Called only when {@code op} is contained in {@link #getOperationNames()}.
     *
     * @param op     operation name (always one of {@link #getOperationNames()})
     * @param params raw parameters from the MCP call (string-string map)
     * @return JSON-serialized {@link com.ditrix.edt.mcp.server.protocol.ToolResult}
     */
    String dispatch(String op, Map<String, String> params);

    /**
     * Returns a human-readable catalog of operations:
     * key = operation name, value = one-line description for the help system.
     * <p>
     * Result is consumed by EditMetadataTool when rendering the {@code help}
     * topic and the global operations catalog.
     *
     * @return immutable operation -&gt; description map
     */
    Map<String, String> getCatalog();
}
