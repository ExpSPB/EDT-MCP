/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.LinkedHashMap;
import java.util.Map;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.YaxunitHelp;

/**
 * 1.40 - Unified YAxUnit test runner. Replaces the legacy two-tool surface
 * ({@code run_yaxunit_tests} + {@code debug_yaxunit_tests}) with a single
 * {@code yaxunit_tests} entry point matching the unified API.
 * <p>
 * Modes:
 * <ul>
 *   <li>{@code mode=run} (default) - synchronous polling of an EDT runtime-client
 *       launch; returns Pending JSON when the timeout elapses; second call with
 *       same parameters fetches the JUnit report.</li>
 *   <li>{@code mode=debug} - launches in debug mode so that breakpoints set via
 *       {@code launch_debugger}/{@code set_breakpoint} fire normally; agent
 *       inspects state and resumes via the debug tools.</li>
 * </ul>
 *
 * <p>UX features:
 * <ul>
 *   <li>{@code help=topics|writing|assertions|setup|events|advanced} - returns
 *       a Markdown topic from {@link YaxunitHelp} without launching anything</li>
 *   <li>{@code updateBeforeLaunch=true} (default) - syncs the infobase before
 *       launching, avoiding the "Update configuration?" modal blocking the
 *       headless run (uses {@link com.ditrix.edt.mcp.server.utils.ApplicationUpdater})</li>
 *   <li>Pending JSON shape (run mode): {@code {status:Pending, runKey, reportDir,
 *       junitXml, hint}}</li>
 *   <li>0-tests hint: when JUnit XML reports zero suites/cases, the markdown
 *       body explains the three usual causes and points at {@code help=writing}</li>
 *   <li>Filter parity: extensions, modules, tests, suites, tags,
 *       contexts (Server/Client/ExternalConnection)</li>
 * </ul>
 *
 * <p>Implementation strategy: the tool delegates to the existing
 * {@link RunYaxunitTestsTool} / {@link DebugYaxunitTestsTool} which carry
 * the heavy lifting (launch tracking, JUnit parsing, report formatting).
 * The unified surface adds: help dispatch, mode routing, the optional
 * {@code updateBeforeLaunch} pre-step. Old tools remain registered as
 * deprecated aliases until 2.0 to preserve skill compatibility.
 */
public class YaxunitTestsTool implements IMcpTool
{
    public static final String NAME = "yaxunit_tests"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Unified YAxUnit test runner (1.40). " //$NON-NLS-1$
            + "Pass mode=run|debug to switch between synchronous polling and " //$NON-NLS-1$
            + "breakpoint-aware debug. Pass help=<topic> to load built-in YAxUnit guidance " //$NON-NLS-1$
            + "(topics/writing/assertions/setup/events/advanced). " //$NON-NLS-1$
            + "Filters: extensions, modules, tests, suites, tags, contexts (CSV). " //$NON-NLS-1$
            + "updateBeforeLaunch=true (default) auto-syncs the infobase before launching."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("mode", "Mode: run (default) or debug.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("help", //$NON-NLS-1$
                "Help topic: topics, writing, assertions, setup, events, advanced. " //$NON-NLS-1$
                + "When set, other parameters are ignored.")
            .stringProperty("launchConfigurationName", "EDT Run Configuration name (preferred).") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("projectName", "Project name (alternative to launchConfigurationName).") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("applicationId", "Application ID for the project.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("extensions", "CSV: extension names to run.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("modules", "CSV: common module names with tests.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("tests", "CSV: test FQNs (Module.Method).") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("suites", "CSV: suite names (upstream parity).") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("tags", "CSV: tag names (upstream parity).") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("contexts", //$NON-NLS-1$
                "CSV: contexts (Server/Client/ExternalConnection). API parity.") //$NON-NLS-1$
            .stringProperty("timeout", "Polling window seconds (default 60).") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("updateBeforeLaunch", //$NON-NLS-1$
                "Default true. Set false to skip pre-launch infobase sync.")
            .build();
    }

    @Override
    public String execute(Map<String, String> params)
    {
        // Help dispatch first - other parameters ignored when help is set
        String helpTopic = JsonUtils.extractStringArgument(params, "help"); //$NON-NLS-1$
        if (helpTopic != null && !helpTopic.isEmpty())
        {
            return renderHelp(helpTopic);
        }

        // Mode dispatch
        String mode = JsonUtils.extractStringArgument(params, "mode"); //$NON-NLS-1$
        if (mode == null || mode.isEmpty())
        {
            mode = "run";
        }
        mode = mode.toLowerCase().trim();

        // updateBeforeLaunch handling - delegate already triggers it when
        // applicable; here we just record the requested behavior for the
        // structured response. Default true matches the upstream surface.
        boolean updateBeforeLaunch = JsonUtils.extractBooleanArgument(params, "updateBeforeLaunch", true);
        if (updateBeforeLaunch)
        {
            // Inject into params so downstream tool can act on it.
            // Existing DebugLaunchTool already supports this flag; Run tool
            // gains it via a helper method in 1.40.
            Map<String, String> forwarded = new LinkedHashMap<>(params);
            forwarded.putIfAbsent("updateBeforeLaunch", "true");
            params = forwarded;
        }

        switch (mode)
        {
            case "run":
                return new RunYaxunitTestsTool().execute(params);
            case "debug":
                return new DebugYaxunitTestsTool().execute(params);
            default:
                return ToolResult.error("Unknown mode: '" + mode //$NON-NLS-1$
                    + "'. Use 'run' or 'debug'.")
                    .put("operation", NAME)
                    .toJson();
        }
    }

    /**
     * Renders a help topic via {@link YaxunitHelp}. Returns markdown wrapped
     * in a JSON envelope so MCP clients can consume both formats.
     */
    private String renderHelp(String topic)
    {
        String body = YaxunitHelp.getTopic(topic);
        if (body == null)
        {
            return ToolResult.error("Unknown help topic: '" + topic + "'.")
                .put("operation", NAME)
                .put("availableTopics", YaxunitHelp.availableTopics())
                .put("hint", "Use yaxunit_tests help=topics for the list of available topics.")
                .toJson();
        }
        return ToolResult.success()
            .put("operation", NAME)
            .put("status", "Help")
            .put("topic", topic.toLowerCase().trim())
            .put("body", body)
            .put("availableTopics", YaxunitHelp.availableTopics())
            .toJson();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }
}
