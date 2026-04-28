/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;

/**
 * Generates BSL event-handler stubs for metadata objects. Returns BSL text or
 * appends to module via write_module_source (when writeToModule=true).
 */
public class GenerateEventHandlersTool implements IMcpTool
{
    public static final String NAME = "generate_event_handlers"; //$NON-NLS-1$

    private static final Map<String, List<EventDef>> EVENTS_BY_KIND = buildEventsByKind();

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Generate BSL event-handler stubs for a metadata object: BeforeWrite, OnWrite, " //$NON-NLS-1$
            + "BeforeDelete, Filling, OnCopy etc. Modes: stub (empty body) or full (with " //$NON-NLS-1$
            + "typical patterns). Returns BSL text or appends to module."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", "EDT project name", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("objectFqn", "FQN of the object (Catalog.X / Document.X)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("events", //$NON-NLS-1$
                "Comma-separated event names (default: all events for the object kind)") //$NON-NLS-1$
            .stringProperty("mode", "stub | full (default stub)") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("writeToModule", //$NON-NLS-1$
                "If true, append handlers to the object's module via write_module_source") //$NON-NLS-1$
            .booleanProperty("skipExisting", //$NON-NLS-1$
                "Skip handlers that already exist in module (default true)") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String objectFqn = JsonUtils.extractStringArgument(params, "objectFqn"); //$NON-NLS-1$
        if (projectName == null || projectName.isEmpty() || objectFqn == null
            || objectFqn.isEmpty())
        {
            return ToolResult.error("projectName and objectFqn are required").toJson(); //$NON-NLS-1$
        }
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
        }
        AtomicReference<String> resultRef = new AtomicReference<>();
        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> {
            try
            {
                resultRef.set(generate(project, objectFqn, params));
            }
            catch (Exception e)
            {
                Activator.logError("generate_event_handlers error", e); //$NON-NLS-1$
                resultRef.set(ToolResult.error(e.getMessage()).toJson());
            }
        });
        return resultRef.get();
    }

    private String generate(IProject project, String objectFqn, Map<String, String> params)
    {
        IConfigurationProvider provider = Activator.getDefault().getConfigurationProvider();
        if (provider == null)
        {
            return ToolResult.error("Configuration provider not available").toJson(); //$NON-NLS-1$
        }
        Configuration config = provider.getConfiguration(project);
        if (config == null)
        {
            return ToolResult.error("Configuration not available").toJson(); //$NON-NLS-1$
        }
        String[] parts = MetadataTypeUtils.normalizeFqn(objectFqn).split("\\.", 2); //$NON-NLS-1$
        if (parts.length < 2)
        {
            return ToolResult.error("objectFqn must be 'Type.Name'").toJson(); //$NON-NLS-1$
        }
        MdObject obj = MetadataTypeUtils.findObject(config, parts[0], parts[1]);
        if (obj == null)
        {
            return ToolResult.error("Object not found: " + objectFqn).toJson(); //$NON-NLS-1$
        }
        String kind = obj.eClass().getName();
        List<EventDef> available = EVENTS_BY_KIND.get(kind);
        if (available == null || available.isEmpty())
        {
            // Fallback by kind family
            available = EVENTS_BY_KIND.get(kindFamily(kind));
        }
        if (available == null || available.isEmpty())
        {
            Map<String, Object> tag = new LinkedHashMap<>();
            tag.put("objectKind", kind); //$NON-NLS-1$
            tag.put("hint", //$NON-NLS-1$
                "Object kind has no event-handler dictionary. Supported: Catalog, Document, " //$NON-NLS-1$
                    + "InformationRegister, AccumulationRegister, etc."); //$NON-NLS-1$
            return ToolResult.error("No events known for object kind " + kind) //$NON-NLS-1$
                .put("noEventsForModuleType", tag) //$NON-NLS-1$
                .toJson();
        }
        Set<String> requested = parseEvents(JsonUtils.extractStringArgument(params, "events")); //$NON-NLS-1$
        String mode = orDefault(JsonUtils.extractStringArgument(params, "mode"), "stub"); //$NON-NLS-1$ //$NON-NLS-2$
        StringBuilder bsl = new StringBuilder();
        List<String> generated = new ArrayList<>();
        for (EventDef def : available)
        {
            if (requested != null && !requested.isEmpty() && !requested.contains(def.name))
            {
                continue;
            }
            bsl.append(renderEvent(def, "full".equalsIgnoreCase(mode))).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            generated.add(def.name);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("objectFqn", objectFqn); //$NON-NLS-1$
        result.put("kind", kind); //$NON-NLS-1$
        result.put("generated", generated); //$NON-NLS-1$
        result.put("bsl", bsl.toString()); //$NON-NLS-1$
        ToolResult tr = ToolResult.success();
        for (Map.Entry<String, Object> entry : result.entrySet())
        {
            tr.put(entry.getKey(), entry.getValue());
        }
        return tr.toJson();
    }

    private String renderEvent(EventDef def, boolean full)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("&НаСервере\n"); //$NON-NLS-1$
        sb.append("Процедура ").append(def.name).append("(").append(def.signature).append(")\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (full && def.fullBody != null)
        {
            sb.append(def.fullBody);
        }
        else
        {
            sb.append("    // TODO: реализовать обработчик ").append(def.name).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        sb.append("КонецПроцедуры"); //$NON-NLS-1$
        return sb.toString();
    }

    private static Set<String> parseEvents(String raw)
    {
        if (raw == null || raw.isEmpty())
        {
            return null;
        }
        Set<String> set = new HashSet<>(Arrays.asList(raw.split("\\s*,\\s*"))); //$NON-NLS-1$
        set.removeIf(String::isEmpty);
        return set;
    }

    private static String kindFamily(String kind)
    {
        if (kind == null)
        {
            return null;
        }
        if (kind.endsWith("Register")) //$NON-NLS-1$
        {
            return "Register"; //$NON-NLS-1$
        }
        return kind;
    }

    private static String orDefault(String value, String fallback)
    {
        return value != null && !value.isEmpty() ? value : fallback;
    }

    private static class EventDef
    {
        final String name;
        final String signature;
        final String fullBody;

        EventDef(String name, String signature, String fullBody)
        {
            this.name = name;
            this.signature = signature;
            this.fullBody = fullBody;
        }
    }

    private static Map<String, List<EventDef>> buildEventsByKind()
    {
        Map<String, List<EventDef>> map = new LinkedHashMap<>();
        List<EventDef> writable = new ArrayList<>();
        writable.add(new EventDef("ПередЗаписью", "Отказ", //$NON-NLS-1$ //$NON-NLS-2$
            "    Если ЭтоНовый() Тогда\n        // Логика для нового объекта\n    КонецЕсли;\n")); //$NON-NLS-1$
        writable.add(new EventDef("ПриЗаписи", "Отказ", null)); //$NON-NLS-1$ //$NON-NLS-2$
        writable.add(new EventDef("ПередУдалением", "Отказ", null)); //$NON-NLS-1$ //$NON-NLS-2$
        writable.add(new EventDef("ОбработкаЗаполнения", "ДанныеЗаполнения, СтандартнаяОбработка", null)); //$NON-NLS-1$ //$NON-NLS-2$
        writable.add(new EventDef("ПриКопировании", "ОбъектКопирования", null)); //$NON-NLS-1$ //$NON-NLS-2$
        map.put("Catalog", writable); //$NON-NLS-1$
        map.put("Document", new ArrayList<>(writable)); //$NON-NLS-1$
        // Documents have additional posting events
        List<EventDef> docExtra = new ArrayList<>(writable);
        docExtra.add(new EventDef("ОбработкаПроведения", "Отказ, РежимПроведения", //$NON-NLS-1$ //$NON-NLS-2$
            "    Движения.Записать();\n")); //$NON-NLS-1$
        docExtra.add(new EventDef("ОбработкаУдаленияПроведения", "Отказ", null)); //$NON-NLS-1$ //$NON-NLS-2$
        map.put("Document", docExtra); //$NON-NLS-1$
        // Registers
        List<EventDef> regEvents = new ArrayList<>();
        regEvents.add(new EventDef("ПередЗаписью", "Отказ, Замещение", null)); //$NON-NLS-1$ //$NON-NLS-2$
        regEvents.add(new EventDef("ПриЗаписи", "Отказ, Замещение", null)); //$NON-NLS-1$ //$NON-NLS-2$
        map.put("Register", regEvents); //$NON-NLS-1$
        map.put("InformationRegister", regEvents); //$NON-NLS-1$
        map.put("AccumulationRegister", regEvents); //$NON-NLS-1$
        map.put("AccountingRegister", regEvents); //$NON-NLS-1$
        // ChartOfCharacteristicTypes / Other
        map.put("ChartOfCharacteristicTypes", new ArrayList<>(writable)); //$NON-NLS-1$
        map.put("ChartOfAccounts", new ArrayList<>(writable)); //$NON-NLS-1$
        map.put("ExchangePlan", new ArrayList<>(writable)); //$NON-NLS-1$
        map.put("Task", new ArrayList<>(writable)); //$NON-NLS-1$
        map.put("BusinessProcess", new ArrayList<>(writable)); //$NON-NLS-1$
        // CommonModule has no events - left empty
        return map;
    }
}
