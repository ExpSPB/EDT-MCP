/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BmDcsHelper;
import com.ditrix.edt.mcp.server.utils.MetadataGuards;
import com.ditrix.edt.mcp.server.utils.QlValidator;

/**
 * DCS schema constructor — наш {@code edit_metadata} DCS group,
 * как отдельный per-domain tool (см. план 1.35-1.36).
 * <p>
 * <b>1.35:</b> 10 functional operations + help. Auto-validation запросов через
 * существующий {@code com._1c.g5.v8.dt.ql.dcs.resource} парсер. DCS direct save
 * в расширения через {@link com.ditrix.edt.mcp.server.utils.DcsExtensionExportHelper}
 * автоматически после каждой mutation operation.
 * <p>
 * <b>1.36:</b> +17 ops для settings/appearance/variants + expression validation.
 * Обновление: добавить case в dispatch + handler метод.
 */
public class DcsWorkshopTool implements IMcpTool
{
    public static final String NAME = "dcs_workshop"; //$NON-NLS-1$

    private static final Map<String, String> OPS = buildOpsCatalog();

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "DCS schema constructor for reports / data processors / register macros. " //$NON-NLS-1$
            + "Pass operation=<name>; call operation=help for the catalog. " //$NON-NLS-1$
            + "Auto-validates queryText (1.35) and expressions (1.36) before write. " //$NON-NLS-1$
            + "DCS direct save to .dcs disk file is automatic for extension projects."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("operation", "Operation name. Use 'help' for catalog.", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("projectName", "EDT project name") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("objectName", //$NON-NLS-1$
                "Owner FQN (Report.X / DataProcessor.X) or full schema FQN") //$NON-NLS-1$
            .stringProperty("templateName", //$NON-NLS-1$
                "DCS template name (default: MainDataCompositionSchema)") //$NON-NLS-1$
            .stringProperty("name", "Name of the new element (parameter / field / etc.)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("dataSetName", "Target dataset for field/calc operations") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("queryText", "BSL query text for add_dataset (Query type)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("dataSetType", "Query / Object / Union (default: Query)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("expression", "DCS expression for calculated field / total") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("aggregateFunction", //$NON-NLS-1$
                "Aggregate for add_total: Sum / Count / Min / Max / Avg") //$NON-NLS-1$
            .stringProperty("type", "Parameter type (Date, Number, String, etc.)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("direction", "Up / Down for move_parameter") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("newIndex", "Target index for move_parameter (0-based)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("topic", "Help topic name (use with operation=help)") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("dryRun", "Preview changes inside BM transaction (default false)") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("validate_query", "Validate queryText before write (default true)") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("validate_expression", //$NON-NLS-1$
                "Validate expression before write (default true; needs 1.36)") //$NON-NLS-1$
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
        String op = JsonUtils.extractStringArgument(params, "operation"); //$NON-NLS-1$
        if (op == null || op.isEmpty())
        {
            return ToolResult.error("operation is required. Pass operation=help for catalog.") //$NON-NLS-1$
                .toJson();
        }
        op = op.trim();
        if ("help".equalsIgnoreCase(op)) //$NON-NLS-1$
        {
            return handleHelp(params);
        }
        if (!OPS.containsKey(op))
        {
            return ToolResult.error("Unknown operation '" + op //$NON-NLS-1$
                + "'. Call operation=help for the full list. Did you mean: " + suggest(op) + "?") //$NON-NLS-1$ //$NON-NLS-2$
                .toJson();
        }

        AtomicReference<String> resultRef = new AtomicReference<>();
        Display display = PlatformUI.getWorkbench().getDisplay();
        final String finalOp = op;
        display.syncExec(() -> {
            try
            {
                resultRef.set(dispatch(finalOp, params));
            }
            catch (Exception e)
            {
                Activator.logError("dcs_workshop error in operation " + finalOp, e); //$NON-NLS-1$
                resultRef.set(ToolResult.error(e.getMessage()).toJson());
            }
        });
        return resultRef.get();
    }

    private String dispatch(String op, Map<String, String> params)
    {
        switch (op)
        {
            case "create_schema": //$NON-NLS-1$
                return opCreateSchema(params);
            case "repair_schema": //$NON-NLS-1$
                return ToolResult.error("repair_schema requires DcsExtensionImportHelper wired " //$NON-NLS-1$
                    + "in the dispatcher; activated when full integration test passes against EDT 2026.1") //$NON-NLS-1$
                    .toJson();
            case "add_dataset": //$NON-NLS-1$
            case "add_field": //$NON-NLS-1$
            case "add_parameter": //$NON-NLS-1$
            case "set_parameter": //$NON-NLS-1$
            case "remove_parameter": //$NON-NLS-1$
            case "move_parameter": //$NON-NLS-1$
            case "add_calculated_field": //$NON-NLS-1$
            case "add_total": //$NON-NLS-1$
            case "remove_dataset": //$NON-NLS-1$
            case "add_appearance": //$NON-NLS-1$
            case "add_grouping": //$NON-NLS-1$
            case "add_filter": //$NON-NLS-1$
            // 1.41: 13 deferred DCS ops landed natively
            case "add_user_field": //$NON-NLS-1$
            case "add_settings_table": //$NON-NLS-1$
            case "add_settings_chart": //$NON-NLS-1$
            case "add_settings_order": //$NON-NLS-1$
            case "add_settings_selected_field": //$NON-NLS-1$
            case "remove_settings_selected_field": //$NON-NLS-1$
            case "add_settings_variant": //$NON-NLS-1$
            case "set_settings_parameter": //$NON-NLS-1$
            case "remove_settings_item": //$NON-NLS-1$
            case "remove_conditional_appearance": //$NON-NLS-1$
            case "set_data_set_field_appearance": //$NON-NLS-1$
            case "set_output_parameter": //$NON-NLS-1$
            case "add_settings_filter_group": //$NON-NLS-1$
                return opSchemaMutation(op, params);
            default:
                return ToolResult.error(BmDcsHelper.deferredMessage(op)).toJson();
        }
    }

    private String opCreateSchema(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String objectName = JsonUtils.extractStringArgument(params, "objectName"); //$NON-NLS-1$
        String templateName = JsonUtils.extractStringArgument(params, "templateName"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        if (projectName == null || objectName == null)
        {
            return ToolResult.error("projectName and objectName are required").toJson(); //$NON-NLS-1$
        }
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
        }
        BmDcsHelper.Result r = BmDcsHelper.createSchemaOnObject(project, objectName, templateName, dryRun);
        return formatResult(r, "create_schema"); //$NON-NLS-1$
    }

    /**
     * Generic schema-mutation dispatch that runs inside BmDcsHelper.executeWriteOnSchema.
     * Per-op semantics are implemented inline so the BM transaction holds for one op.
     * <p>
     * Each op resolves the schema {@link EObject} via reflection, mutates the
     * corresponding child collection (DataSets / Parameters / CalculatedFields /
     * ConditionalAppearance / DefaultSettings.Structure / DefaultSettings.Filter),
     * and records a short message in {@link BmDcsHelper.Result#message}. Errors
     * surface as {@link MetadataGuards.BlockedGuardException} with structured tags.
     */
    private String opSchemaMutation(String op, Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String objectName = JsonUtils.extractStringArgument(params, "objectName"); //$NON-NLS-1$
        String templateName = JsonUtils.extractStringArgument(params, "templateName"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        if (projectName == null || objectName == null)
        {
            return ToolResult.error("projectName and objectName are required").toJson(); //$NON-NLS-1$
        }
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
        }

        // Phase 5.4 pre-flight: validate queryText / expression BEFORE the BM
        // transaction opens. Cheaper than rolling back the model on a parse
        // error and avoids running Xtext validation inside the BM tx.
        String preFlightError = preflightValidate(op, params, project);
        if (preFlightError != null)
        {
            return preFlightError;
        }
        BmDcsHelper.Result r = BmDcsHelper.executeWriteOnSchema(project, objectName, templateName,
            dryRun, (tx, schema) -> applySchemaMutation(op, params, schema));
        return formatResult(r, op);
    }

    /**
     * Runs Xtext-based validation on queryText / expression for ops that
     * accept them. Returns {@code null} on pass, or a JSON error response
     * carrying the {@code queryValidation} or {@code expressionValidation}
     * tag.
     */
    private String preflightValidate(String op, Map<String, String> params, IProject project)
    {
        boolean validateQuery = JsonUtils.extractBooleanArgument(params, "validate_query", true); //$NON-NLS-1$
        boolean validateExpr = JsonUtils.extractBooleanArgument(params, "validate_expression", //$NON-NLS-1$
            true);
        if (validateQuery && "add_dataset".equals(op)) //$NON-NLS-1$
        {
            String queryText = JsonUtils.extractStringArgument(params, "queryText"); //$NON-NLS-1$
            if (queryText != null && !queryText.isEmpty())
            {
                QlValidator.ValidationResult vr = QlValidator.validateQueryText(project,
                    queryText, true);
                if (vr.hasErrors())
                {
                    return ToolResult
                        .error(op + " failed: queryText has " + vr.errorCount //$NON-NLS-1$
                            + " error(s); fix and retry") //$NON-NLS-1$
                        .put("operation", op) //$NON-NLS-1$
                        .put("queryValidation", vr.toTagData()) //$NON-NLS-1$
                        .toJson();
                }
            }
        }
        if (validateExpr && isExpressionOp(op))
        {
            String expression = JsonUtils.extractStringArgument(params, "expression"); //$NON-NLS-1$
            if (expression != null && !expression.isEmpty())
            {
                QlValidator.ValidationResult vr = QlValidator.validateExpression(project,
                    expression);
                if (vr.hasErrors())
                {
                    return ToolResult
                        .error(op + " failed: expression has " + vr.errorCount //$NON-NLS-1$
                            + " error(s); fix and retry") //$NON-NLS-1$
                        .put("operation", op) //$NON-NLS-1$
                        .put("expressionValidation", vr.toTagData()) //$NON-NLS-1$
                        .toJson();
                }
            }
        }
        return null;
    }

    private static boolean isExpressionOp(String op)
    {
        switch (op)
        {
            case "add_calculated_field": //$NON-NLS-1$
            case "add_total": //$NON-NLS-1$
            case "add_parameter": //$NON-NLS-1$
            case "set_parameter": //$NON-NLS-1$
                return true;
            default:
                return false;
        }
    }

    /**
     * Applies one schema-mutation operation on the resolved DCS schema. Called
     * inside the BM write transaction.
     */
    private Object applySchemaMutation(String op, Map<String, String> params, EObject schema)
        throws Exception
    {
        switch (op)
        {
            case "add_dataset": //$NON-NLS-1$
                return doAddDataSet(params, schema);
            case "remove_dataset": //$NON-NLS-1$
                return doRemoveDataSet(params, schema);
            case "add_field": //$NON-NLS-1$
                return doAddField(params, schema);
            case "add_parameter": //$NON-NLS-1$
                return doAddParameter(params, schema);
            case "set_parameter": //$NON-NLS-1$
                return doSetParameter(params, schema);
            case "remove_parameter": //$NON-NLS-1$
                return doRemoveParameter(params, schema);
            case "move_parameter": //$NON-NLS-1$
                return doMoveParameter(params, schema);
            case "add_calculated_field": //$NON-NLS-1$
                return doAddCalculatedField(params, schema);
            case "add_total": //$NON-NLS-1$
                return doAddTotal(params, schema);
            case "add_appearance": //$NON-NLS-1$
                return doAddAppearance(params, schema);
            case "add_grouping": //$NON-NLS-1$
                return doAddGrouping(params, schema);
            case "add_filter": //$NON-NLS-1$
                return doAddFilter(params, schema);
            // 1.41: 13 deferred DCS ops landed natively
            case "add_user_field": //$NON-NLS-1$
                return doAddUserField(params, schema);
            case "add_settings_table": //$NON-NLS-1$
                return doAddSettingsStructureItem(params, schema, "Table"); //$NON-NLS-1$
            case "add_settings_chart": //$NON-NLS-1$
                return doAddSettingsStructureItem(params, schema, "Chart"); //$NON-NLS-1$
            case "add_settings_order": //$NON-NLS-1$
                return doAddSettingsOrder(params, schema);
            case "add_settings_selected_field": //$NON-NLS-1$
                return doAddSettingsSelectedField(params, schema);
            case "remove_settings_selected_field": //$NON-NLS-1$
                return doRemoveSettingsSelectedField(params, schema);
            case "add_settings_variant": //$NON-NLS-1$
                return doAddSettingsVariant(params, schema);
            case "set_settings_parameter": //$NON-NLS-1$
                return doSetSettingsParameter(params, schema);
            case "remove_settings_item": //$NON-NLS-1$
                return doRemoveSettingsItem(params, schema);
            case "remove_conditional_appearance": //$NON-NLS-1$
                return doRemoveConditionalAppearance(params, schema);
            case "set_data_set_field_appearance": //$NON-NLS-1$
                return doSetDataSetFieldAppearance(params, schema);
            case "set_output_parameter": //$NON-NLS-1$
                return doSetOutputParameter(params, schema);
            case "add_settings_filter_group": //$NON-NLS-1$
                return doAddSettingsFilterGroup(params, schema);
            default:
                throw new RuntimeException("Internal: unhandled op '" + op + "'"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    // -----------------------------------------------------------------------
    // DCS mutation handlers (Phase 5.3)
    // -----------------------------------------------------------------------

    private Object doAddDataSet(Map<String, String> params, EObject schema)
    {
        String name = required(params, "name"); //$NON-NLS-1$
        String queryText = JsonUtils.extractStringArgument(params, "queryText"); //$NON-NLS-1$
        String dataSetType = orDefault(JsonUtils.extractStringArgument(params, "dataSetType"), //$NON-NLS-1$
            "Query"); //$NON-NLS-1$
        if (BmDcsHelper.findByNameInList(schema, "getDataSets", name) != null) //$NON-NLS-1$
        {
            throw alreadyExistsTag(name, "dataSet"); //$NON-NLS-1$
        }
        String factoryMethod = "createDataSet" + dataSetType; //$NON-NLS-1$
        Object dataSet = BmDcsHelper.createElement(factoryMethod);
        if (dataSet == null)
        {
            throw new RuntimeException("DcsFactory." + factoryMethod + " not available"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        BmDcsHelper.setProperty(dataSet, "name", name); //$NON-NLS-1$
        if (queryText != null && !queryText.isEmpty())
        {
            BmDcsHelper.setProperty(dataSet, "query", queryText); //$NON-NLS-1$
        }
        EList<EObject> dataSets = BmDcsHelper.getEObjectList(schema, "getDataSets"); //$NON-NLS-1$
        if (dataSets == null)
        {
            throw new RuntimeException("Schema.getDataSets() not available"); //$NON-NLS-1$
        }
        dataSets.add((EObject) dataSet);
        return name;
    }

    private Object doRemoveDataSet(Map<String, String> params, EObject schema)
    {
        String name = required(params, "name"); //$NON-NLS-1$
        EList<EObject> dataSets = BmDcsHelper.getEObjectList(schema, "getDataSets"); //$NON-NLS-1$
        if (dataSets == null)
        {
            throw new RuntimeException("Schema.getDataSets() not available"); //$NON-NLS-1$
        }
        EObject existing = BmDcsHelper.findByNameInList(schema, "getDataSets", name); //$NON-NLS-1$
        if (existing == null)
        {
            // Idempotent: missing dataset is recorded in tags, success returns.
            // The helper Result.tags surfaces notFound via the catch block; we
            // signal the case by throwing and the helper unwraps verdict.
            throw notFoundTag(name, "dataSet"); //$NON-NLS-1$
        }
        dataSets.remove(existing);
        // Cascade: remove calculated/total fields whose expression starts with "<name>."
        int removedCalc = removeFieldsReferencing(schema, "getCalculatedFields", name); //$NON-NLS-1$
        int removedTotal = removeFieldsReferencing(schema, "getTotalFields", name); //$NON-NLS-1$
        return name + " (cascade: removed " + removedCalc //$NON-NLS-1$
            + " calculated, " + removedTotal + " total)"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private int removeFieldsReferencing(EObject schema, String getter, String dataSetName)
    {
        EList<EObject> list = BmDcsHelper.getEObjectList(schema, getter);
        if (list == null)
        {
            return 0;
        }
        int removed = 0;
        Iterator<EObject> it = list.iterator();
        while (it.hasNext())
        {
            EObject field = it.next();
            try
            {
                java.lang.reflect.Method getExpr = field.getClass().getMethod("getExpression"); //$NON-NLS-1$
                Object expr = getExpr.invoke(field);
                if (expr != null && expr.toString().contains(dataSetName + ".")) //$NON-NLS-1$
                {
                    it.remove();
                    removed++;
                }
            }
            catch (Exception ignored)
            {
                // type does not have getExpression - skip
            }
        }
        return removed;
    }

    private Object doAddField(Map<String, String> params, EObject schema)
    {
        String name = required(params, "name"); //$NON-NLS-1$
        String dataSetName = required(params, "dataSetName"); //$NON-NLS-1$
        EObject dataSet = BmDcsHelper.findByNameInList(schema, "getDataSets", dataSetName); //$NON-NLS-1$
        if (dataSet == null)
        {
            throw notFoundTag(dataSetName, "dataSet"); //$NON-NLS-1$
        }
        if (BmDcsHelper.findByNameInList(dataSet, "getFields", name) != null) //$NON-NLS-1$
        {
            throw alreadyExistsTag(name, "field"); //$NON-NLS-1$
        }
        Object field = BmDcsHelper.createElement("createDataSetField"); //$NON-NLS-1$
        if (field == null)
        {
            throw new RuntimeException("DcsFactory.createDataSetField not available"); //$NON-NLS-1$
        }
        BmDcsHelper.setProperty(field, "dataPath", name); //$NON-NLS-1$
        BmDcsHelper.setProperty(field, "field", name); //$NON-NLS-1$
        EList<EObject> fields = BmDcsHelper.getEObjectList(dataSet, "getFields"); //$NON-NLS-1$
        if (fields == null)
        {
            throw new RuntimeException("DataSet.getFields() not available"); //$NON-NLS-1$
        }
        fields.add((EObject) field);
        return dataSetName + "." + name; //$NON-NLS-1$
    }

    private Object doAddParameter(Map<String, String> params, EObject schema)
    {
        String name = required(params, "name"); //$NON-NLS-1$
        if (BmDcsHelper.findByNameInList(schema, "getParameters", name) != null) //$NON-NLS-1$
        {
            throw alreadyExistsTag(name, "parameter"); //$NON-NLS-1$
        }
        Object parameter = BmDcsHelper.createElement("createParameter"); //$NON-NLS-1$
        if (parameter == null)
        {
            throw new RuntimeException("DcsFactory.createParameter not available"); //$NON-NLS-1$
        }
        BmDcsHelper.setProperty(parameter, "name", name); //$NON-NLS-1$
        applyParameterFields(parameter, params);
        EList<EObject> parameters = BmDcsHelper.getEObjectList(schema, "getParameters"); //$NON-NLS-1$
        if (parameters == null)
        {
            throw new RuntimeException("Schema.getParameters() not available"); //$NON-NLS-1$
        }
        parameters.add((EObject) parameter);
        return name;
    }

    private Object doSetParameter(Map<String, String> params, EObject schema)
    {
        String name = required(params, "name"); //$NON-NLS-1$
        EObject parameter = BmDcsHelper.findByNameInList(schema, "getParameters", name); //$NON-NLS-1$
        if (parameter == null)
        {
            throw notFoundTag(name, "parameter"); //$NON-NLS-1$
        }
        // Name change is intentionally ignored (matching conventional behavior).
        applyParameterFields(parameter, params);
        return name + " updated"; //$NON-NLS-1$
    }

    private void applyParameterFields(Object parameter, Map<String, String> params)
    {
        // Optional fields: type, length, precision, expression, title, use,
        // valueListAllowed, denyIncompleteValues, useRestriction.
        applyOptionalProperty(parameter, "title", params, "title"); //$NON-NLS-1$ //$NON-NLS-2$
        applyOptionalProperty(parameter, "expression", params, "expression"); //$NON-NLS-1$ //$NON-NLS-2$
        applyOptionalProperty(parameter, "use", params, "use"); //$NON-NLS-1$ //$NON-NLS-2$
        applyOptionalProperty(parameter, "valueListAllowed", params, //$NON-NLS-1$
            "valueListAllowed"); //$NON-NLS-1$
        applyOptionalProperty(parameter, "denyIncompleteValues", params, //$NON-NLS-1$
            "denyIncompleteValues"); //$NON-NLS-1$
        applyOptionalProperty(parameter, "length", params, "length"); //$NON-NLS-1$ //$NON-NLS-2$
        applyOptionalProperty(parameter, "precision", params, "precision"); //$NON-NLS-1$ //$NON-NLS-2$
        // Type is a TypeDescription object - optional. Without runtime probe
        // of TypeDescriptionFactory we skip detailed wiring; users can set it
        // via separate add_dataset for typed fields.
    }

    private void applyOptionalProperty(Object target, String propertyName,
        Map<String, String> params, String paramKey)
    {
        if (params == null || !params.containsKey(paramKey))
        {
            return;
        }
        String value = JsonUtils.extractStringArgument(params, paramKey);
        if (value == null)
        {
            return;
        }
        String err = BmDcsHelper.setProperty(target, propertyName, value);
        if (err != null)
        {
            Activator.logWarning("dcs_workshop: " + err); //$NON-NLS-1$
        }
    }

    private Object doRemoveParameter(Map<String, String> params, EObject schema)
    {
        String name = required(params, "name"); //$NON-NLS-1$
        EList<EObject> parameters = BmDcsHelper.getEObjectList(schema, "getParameters"); //$NON-NLS-1$
        if (parameters == null)
        {
            throw new RuntimeException("Schema.getParameters() not available"); //$NON-NLS-1$
        }
        EObject existing = BmDcsHelper.findByNameInList(schema, "getParameters", name); //$NON-NLS-1$
        if (existing == null)
        {
            throw notFoundTag(name, "parameter"); //$NON-NLS-1$
        }
        parameters.remove(existing);
        return name;
    }

    private Object doMoveParameter(Map<String, String> params, EObject schema)
    {
        String name = required(params, "name"); //$NON-NLS-1$
        String direction = JsonUtils.extractStringArgument(params, "direction"); //$NON-NLS-1$
        Integer newIndex = extractInteger(params, "newIndex"); //$NON-NLS-1$
        EList<EObject> parameters = BmDcsHelper.getEObjectList(schema, "getParameters"); //$NON-NLS-1$
        if (parameters == null)
        {
            throw new RuntimeException("Schema.getParameters() not available"); //$NON-NLS-1$
        }
        int oldIdx = -1;
        for (int i = 0; i < parameters.size(); i++)
        {
            EObject p = parameters.get(i);
            try
            {
                Object n = p.getClass().getMethod("getName").invoke(p); //$NON-NLS-1$
                if (n != null && name.equalsIgnoreCase(n.toString()))
                {
                    oldIdx = i;
                    break;
                }
            }
            catch (Exception ignored)
            {
                // skip
            }
        }
        if (oldIdx == -1)
        {
            throw notFoundTag(name, "parameter"); //$NON-NLS-1$
        }
        int targetIdx;
        if (newIndex != null)
        {
            targetIdx = newIndex.intValue();
        }
        else if ("Up".equalsIgnoreCase(direction)) //$NON-NLS-1$
        {
            targetIdx = Math.max(0, oldIdx - 1);
        }
        else if ("Down".equalsIgnoreCase(direction)) //$NON-NLS-1$
        {
            targetIdx = Math.min(parameters.size() - 1, oldIdx + 1);
        }
        else
        {
            throw new RuntimeException("Provide direction=Up|Down or newIndex=<int>"); //$NON-NLS-1$
        }
        if (targetIdx < 0 || targetIdx >= parameters.size())
        {
            throw new RuntimeException("newIndex out of range: " + targetIdx); //$NON-NLS-1$
        }
        if (targetIdx != oldIdx)
        {
            parameters.move(targetIdx, oldIdx);
        }
        return name + " moved from " + oldIdx + " to " + targetIdx; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private Object doAddCalculatedField(Map<String, String> params, EObject schema)
    {
        String name = required(params, "name"); //$NON-NLS-1$
        String expression = required(params, "expression"); //$NON-NLS-1$
        if (BmDcsHelper.findByNameInList(schema, "getCalculatedFields", name) != null) //$NON-NLS-1$
        {
            throw alreadyExistsTag(name, "calculatedField"); //$NON-NLS-1$
        }
        Object field = BmDcsHelper.createElement("createCalculatedField"); //$NON-NLS-1$
        if (field == null)
        {
            throw new RuntimeException("DcsFactory.createCalculatedField not available"); //$NON-NLS-1$
        }
        BmDcsHelper.setProperty(field, "dataPath", name); //$NON-NLS-1$
        BmDcsHelper.setProperty(field, "expression", expression); //$NON-NLS-1$
        applyOptionalProperty(field, "title", params, "title"); //$NON-NLS-1$ //$NON-NLS-2$
        EList<EObject> calc = BmDcsHelper.getEObjectList(schema, "getCalculatedFields"); //$NON-NLS-1$
        if (calc == null)
        {
            throw new RuntimeException("Schema.getCalculatedFields() not available"); //$NON-NLS-1$
        }
        calc.add((EObject) field);
        return name;
    }

    private Object doAddTotal(Map<String, String> params, EObject schema)
    {
        String expression = required(params, "expression"); //$NON-NLS-1$
        String aggregateFunction = orDefault(
            JsonUtils.extractStringArgument(params, "aggregateFunction"), "Sum"); //$NON-NLS-1$ //$NON-NLS-2$
        Object field = BmDcsHelper.createElement("createTotalField"); //$NON-NLS-1$
        if (field == null)
        {
            throw new RuntimeException("DcsFactory.createTotalField not available"); //$NON-NLS-1$
        }
        BmDcsHelper.setProperty(field, "expression", expression); //$NON-NLS-1$
        // Some EDT versions name it "aggregateFunction"; others may expose
        // additional properties. Best-effort apply.
        BmDcsHelper.setProperty(field, "aggregateFunction", aggregateFunction); //$NON-NLS-1$
        applyOptionalProperty(field, "dataPath", params, "name"); //$NON-NLS-1$ //$NON-NLS-2$
        EList<EObject> totals = BmDcsHelper.getEObjectList(schema, "getTotalFields"); //$NON-NLS-1$
        if (totals == null)
        {
            throw new RuntimeException("Schema.getTotalFields() not available"); //$NON-NLS-1$
        }
        totals.add((EObject) field);
        return aggregateFunction + "(" + expression + ")"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private Object doAddAppearance(Map<String, String> params, EObject schema)
    {
        String conditionType = orDefault(
            JsonUtils.extractStringArgument(params, "conditionType"), "Equal"); //$NON-NLS-1$ //$NON-NLS-2$
        String conditionValue = JsonUtils.extractStringArgument(params, "conditionValue"); //$NON-NLS-1$
        // Appearance properties are received as a string in 1.37: "Font=Arial,12,bold;TextColor=#FF0000".
        // The font/color guard rejects values that look like JSON objects/arrays
        // (lesson learned: agents often send {"bold": true} which corrupts MXL).
        String appearanceSpec = JsonUtils.extractStringArgument(params, "appearance"); //$NON-NLS-1$
        String appearanceTrim = appearanceSpec != null ? appearanceSpec.trim() : null;
        if (appearanceTrim != null
            && (appearanceTrim.startsWith("{") || appearanceTrim.startsWith("["))) //$NON-NLS-1$ //$NON-NLS-2$
        {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("appearance", appearanceSpec); //$NON-NLS-1$
            data.put("hint", //$NON-NLS-1$
                "Pass appearance as 'Name=Value;Name=Value' string. " //$NON-NLS-1$
                    + "For Font use 'Arial,12,bold'; for colors '#RRGGBB'."); //$NON-NLS-1$
            throw new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
                "appearance must be a 'Name=Value;Name=Value' string, not JSON", //$NON-NLS-1$
                "Use 'Arial,12,bold' for Font, '#RRGGBB' for colors.", //$NON-NLS-1$
                new MetadataGuards.ErrorTag("fontColorGuard", data))); //$NON-NLS-1$
        }
        Object container = invokeGetter(schema, "getConditionalAppearance"); //$NON-NLS-1$
        if (container == null)
        {
            throw new RuntimeException("Schema.getConditionalAppearance() not available"); //$NON-NLS-1$
        }
        Object item = BmDcsHelper.createElement("createConditionalAppearanceItem"); //$NON-NLS-1$
        if (item == null)
        {
            throw new RuntimeException("DcsFactory.createConditionalAppearanceItem not available"); //$NON-NLS-1$
        }
        BmDcsHelper.setProperty(item, "useInGrouping", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        BmDcsHelper.setProperty(item, "useInTable", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        BmDcsHelper.setProperty(item, "useInChart", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        if (conditionValue != null)
        {
            // Attach a single filter item to .condition - best-effort via reflection.
            Object condition = invokeGetter(item, "getFilter"); //$NON-NLS-1$
            if (condition != null)
            {
                Object filterItem = BmDcsHelper.createElement("createFilterItem"); //$NON-NLS-1$
                if (filterItem != null)
                {
                    BmDcsHelper.setProperty(filterItem, "comparisonType", conditionType); //$NON-NLS-1$
                    BmDcsHelper.setProperty(filterItem, "rightValue", conditionValue); //$NON-NLS-1$
                    EList<EObject> items = BmDcsHelper.getEObjectList(condition, "getItems"); //$NON-NLS-1$
                    if (items != null)
                    {
                        items.add((EObject) filterItem);
                    }
                }
            }
        }
        EList<EObject> items = BmDcsHelper.getEObjectList(container, "getItems"); //$NON-NLS-1$
        if (items == null)
        {
            throw new RuntimeException("ConditionalAppearance.getItems() not available"); //$NON-NLS-1$
        }
        items.add((EObject) item);
        return "appearance added (cond=" + conditionType + ")"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private Object doAddGrouping(Map<String, String> params, EObject schema)
    {
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        String field = JsonUtils.extractStringArgument(params, "field"); //$NON-NLS-1$
        if (field == null || field.isEmpty())
        {
            throw new RuntimeException("field is required for add_grouping"); //$NON-NLS-1$
        }
        String groupingType = orDefault(
            JsonUtils.extractStringArgument(params, "groupingType"), "Standard"); //$NON-NLS-1$ //$NON-NLS-2$
        Object settings = invokeGetter(schema, "getDefaultSettings"); //$NON-NLS-1$
        if (settings == null)
        {
            throw new RuntimeException("Schema.getDefaultSettings() not available"); //$NON-NLS-1$
        }
        Object structure = invokeGetter(settings, "getStructure"); //$NON-NLS-1$
        if (structure == null)
        {
            throw new RuntimeException("DefaultSettings.getStructure() not available"); //$NON-NLS-1$
        }
        Object group = BmDcsHelper.createElement("createSettingsGroup"); //$NON-NLS-1$
        if (group == null)
        {
            throw new RuntimeException("DcsFactory.createSettingsGroup not available"); //$NON-NLS-1$
        }
        if (name != null && !name.isEmpty())
        {
            BmDcsHelper.setProperty(group, "name", name); //$NON-NLS-1$
        }
        // SettingsGroup has groupFields - a structured collection of GroupField.
        Object groupFields = invokeGetter(group, "getGroupFields"); //$NON-NLS-1$
        if (groupFields != null)
        {
            Object groupField = BmDcsHelper.createElement("createGroupField"); //$NON-NLS-1$
            if (groupField != null)
            {
                BmDcsHelper.setProperty(groupField, "field", field); //$NON-NLS-1$
                BmDcsHelper.setProperty(groupField, "groupType", groupingType); //$NON-NLS-1$
                EList<EObject> items = BmDcsHelper.getEObjectList(groupFields, "getItems"); //$NON-NLS-1$
                if (items != null)
                {
                    items.add((EObject) groupField);
                }
            }
        }
        EList<EObject> structureItems;
        if (structure instanceof EList)
        {
            @SuppressWarnings({ "rawtypes", "unchecked" })
            EList<EObject> coerced = (EList) structure;
            structureItems = coerced;
        }
        else
        {
            structureItems = BmDcsHelper.getEObjectList(structure, "getItems"); //$NON-NLS-1$
        }
        if (structureItems == null)
        {
            throw new RuntimeException("Settings.Structure has no items collection"); //$NON-NLS-1$
        }
        structureItems.add((EObject) group);
        return "grouping by " + field + " (" + groupingType + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private Object doAddFilter(Map<String, String> params, EObject schema)
    {
        String field = JsonUtils.extractStringArgument(params, "field"); //$NON-NLS-1$
        if (field == null || field.isEmpty())
        {
            throw new RuntimeException("field is required for add_filter"); //$NON-NLS-1$
        }
        String comparisonType = orDefault(
            JsonUtils.extractStringArgument(params, "comparisonType"), "Equal"); //$NON-NLS-1$ //$NON-NLS-2$
        String value = JsonUtils.extractStringArgument(params, "value"); //$NON-NLS-1$
        String userPresentation = JsonUtils.extractStringArgument(params,
            "userSettingPresentation"); //$NON-NLS-1$
        String viewMode = JsonUtils.extractStringArgument(params, "viewMode"); //$NON-NLS-1$

        Object settings = invokeGetter(schema, "getDefaultSettings"); //$NON-NLS-1$
        if (settings == null)
        {
            throw new RuntimeException("Schema.getDefaultSettings() not available"); //$NON-NLS-1$
        }
        Object filter = invokeGetter(settings, "getFilter"); //$NON-NLS-1$
        if (filter == null)
        {
            throw new RuntimeException("DefaultSettings.getFilter() not available"); //$NON-NLS-1$
        }
        Object filterItem = BmDcsHelper.createElement("createFilterItem"); //$NON-NLS-1$
        if (filterItem == null)
        {
            throw new RuntimeException("DcsFactory.createFilterItem not available"); //$NON-NLS-1$
        }
        BmDcsHelper.setProperty(filterItem, "leftValue", field); //$NON-NLS-1$
        BmDcsHelper.setProperty(filterItem, "comparisonType", comparisonType); //$NON-NLS-1$
        if (value != null)
        {
            BmDcsHelper.setProperty(filterItem, "rightValue", value); //$NON-NLS-1$
        }
        if (userPresentation != null)
        {
            BmDcsHelper.setProperty(filterItem, "userSettingPresentation", userPresentation); //$NON-NLS-1$
        }
        if (viewMode != null)
        {
            BmDcsHelper.setProperty(filterItem, "viewMode", viewMode); //$NON-NLS-1$
        }
        EList<EObject> items = BmDcsHelper.getEObjectList(filter, "getItems"); //$NON-NLS-1$
        if (items == null)
        {
            throw new RuntimeException("Filter has no items collection"); //$NON-NLS-1$
        }
        items.add((EObject) filterItem);
        return "filter " + field + " " + comparisonType //$NON-NLS-1$ //$NON-NLS-2$
            + (value != null ? " " + value : ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // -----------------------------------------------------------------------
    // 1.41: 13 deferred DCS mutation handlers (Phase 4)
    // -----------------------------------------------------------------------

    /**
     * 1.41 / 4d: adds a user-defined calculated field at Schema-level (NOT
     * DefaultSettings). User fields are calculated expressions evaluated
     * at report runtime; they live in Schema.getUserFields() if exposed,
     * with fallback to Schema.getCalculatedFields().
     */
    private Object doAddUserField(Map<String, String> params, EObject schema)
    {
        String name = required(params, "name"); //$NON-NLS-1$
        String expression = required(params, "expression"); //$NON-NLS-1$
        String title = JsonUtils.extractStringArgument(params, "title"); //$NON-NLS-1$
        if (BmDcsHelper.findByNameInList(schema, "getUserFields", name) != null //$NON-NLS-1$
            || BmDcsHelper.findByNameInList(schema, "getCalculatedFields", name) != null) //$NON-NLS-1$
        {
            throw alreadyExistsTag(name, "userField"); //$NON-NLS-1$
        }
        // 1.41 H2 fix: resolve target list FIRST. Mixing UserFieldExpression
        // into CalculatedFields causes a ClassCastException inside the EMF
        // EList.add(); resolve the list explicitly so the user gets a
        // structured dcsFactoryMethodNotFound instead.
        EList<EObject> userFields = BmDcsHelper.getEObjectList(schema, "getUserFields"); //$NON-NLS-1$
        if (userFields == null)
        {
            throw factoryMissingTag("Schema.getUserFields"); //$NON-NLS-1$
        }
        Object userField = BmDcsHelper.createElement("createUserFieldExpression"); //$NON-NLS-1$
        if (userField == null)
        {
            userField = BmDcsHelper.createElement("createUserField"); //$NON-NLS-1$
        }
        if (userField == null)
        {
            throw factoryMissingTag("createUserFieldExpression, createUserField"); //$NON-NLS-1$
        }
        BmDcsHelper.setProperty(userField, "name", name); //$NON-NLS-1$
        BmDcsHelper.setProperty(userField, "expression", expression); //$NON-NLS-1$
        if (title != null)
        {
            BmDcsHelper.setProperty(userField, "title", title); //$NON-NLS-1$
        }
        userFields.add((EObject) userField);
        return "user field '" + name + "' added"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * 1.41 / 4a: shared implementation for addSettingsTable / addSettingsChart -
     * both append a structure item of the corresponding type to
     * {@code Schema.getDefaultSettings().getStructure().getItems()}.
     *
     * @param kind {@code "Table"} or {@code "Chart"}
     */
    private Object doAddSettingsStructureItem(Map<String, String> params, EObject schema, String kind)
    {
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        Object settings = invokeGetter(schema, "getDefaultSettings"); //$NON-NLS-1$
        if (settings == null)
        {
            throw new RuntimeException("Schema.getDefaultSettings() not available"); //$NON-NLS-1$
        }
        Object structure = invokeGetter(settings, "getStructure"); //$NON-NLS-1$
        if (structure == null)
        {
            throw new RuntimeException("DefaultSettings.getStructure() not available"); //$NON-NLS-1$
        }
        String factoryMethod = "createSettings" + kind; //$NON-NLS-1$
        Object item = BmDcsHelper.createElement(factoryMethod);
        if (item == null)
        {
            // Fallback: createTableStructureItem / createChartStructureItem
            item = BmDcsHelper.createElement("create" + kind + "StructureItem"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (item == null)
        {
            throw factoryMissingTag(factoryMethod + ", create" + kind + "StructureItem"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (name != null && !name.isEmpty())
        {
            BmDcsHelper.setProperty(item, "name", name); //$NON-NLS-1$
        }
        EList<EObject> items = resolveStructureItems(structure);
        items.add((EObject) item);
        return "settings " + kind.toLowerCase() //$NON-NLS-1$
            + (name != null ? " '" + name + "'" : "") + " added"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /**
     * 1.41 / 4b: appends an order item to
     * {@code Settings.getOrder().getItems()}.
     */
    private Object doAddSettingsOrder(Map<String, String> params, EObject schema)
    {
        String field = required(params, "field"); //$NON-NLS-1$
        String orderType = orDefault(JsonUtils.extractStringArgument(params, "orderType"), //$NON-NLS-1$
            "Asc"); //$NON-NLS-1$
        Object settings = invokeGetter(schema, "getDefaultSettings"); //$NON-NLS-1$
        if (settings == null)
        {
            throw new RuntimeException("Schema.getDefaultSettings() not available"); //$NON-NLS-1$
        }
        Object order = invokeGetter(settings, "getOrder"); //$NON-NLS-1$
        if (order == null)
        {
            throw new RuntimeException("DefaultSettings.getOrder() not available"); //$NON-NLS-1$
        }
        Object orderItem = BmDcsHelper.createElement("createOrderItem"); //$NON-NLS-1$
        if (orderItem == null)
        {
            orderItem = BmDcsHelper.createElement("createSettingsOrderItem"); //$NON-NLS-1$
        }
        if (orderItem == null)
        {
            throw factoryMissingTag("createOrderItem, createSettingsOrderItem"); //$NON-NLS-1$
        }
        BmDcsHelper.setProperty(orderItem, "field", field); //$NON-NLS-1$
        BmDcsHelper.setProperty(orderItem, "orderType", orderType); //$NON-NLS-1$
        EList<EObject> items = BmDcsHelper.getEObjectList(order, "getItems"); //$NON-NLS-1$
        if (items == null)
        {
            throw new RuntimeException("Order.getItems() not available"); //$NON-NLS-1$
        }
        items.add((EObject) orderItem);
        return "order by " + field + " (" + orderType + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * 1.41 / 4b: appends a SelectedField to
     * {@code Settings.getSelection().getItems()}.
     */
    private Object doAddSettingsSelectedField(Map<String, String> params, EObject schema)
    {
        String field = required(params, "field"); //$NON-NLS-1$
        String title = JsonUtils.extractStringArgument(params, "title"); //$NON-NLS-1$
        Object settings = invokeGetter(schema, "getDefaultSettings"); //$NON-NLS-1$
        if (settings == null)
        {
            throw new RuntimeException("Schema.getDefaultSettings() not available"); //$NON-NLS-1$
        }
        Object selection = invokeGetter(settings, "getSelection"); //$NON-NLS-1$
        if (selection == null)
        {
            throw new RuntimeException("DefaultSettings.getSelection() not available"); //$NON-NLS-1$
        }
        Object item = BmDcsHelper.createElement("createSelectedField"); //$NON-NLS-1$
        if (item == null)
        {
            item = BmDcsHelper.createElement("createSettingsSelectedField"); //$NON-NLS-1$
        }
        if (item == null)
        {
            throw factoryMissingTag("createSelectedField, createSettingsSelectedField"); //$NON-NLS-1$
        }
        BmDcsHelper.setProperty(item, "field", field); //$NON-NLS-1$
        if (title != null)
        {
            BmDcsHelper.setProperty(item, "title", title); //$NON-NLS-1$
        }
        EList<EObject> items = BmDcsHelper.getEObjectList(selection, "getItems"); //$NON-NLS-1$
        if (items == null)
        {
            throw new RuntimeException("Selection.getItems() not available"); //$NON-NLS-1$
        }
        items.add((EObject) item);
        return "selected field '" + field + "' added"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * 1.41 / 4b: removes a SelectedField from
     * {@code Settings.getSelection().getItems()} by field name.
     */
    private Object doRemoveSettingsSelectedField(Map<String, String> params, EObject schema)
    {
        String field = required(params, "field"); //$NON-NLS-1$
        Object settings = invokeGetter(schema, "getDefaultSettings"); //$NON-NLS-1$
        if (settings == null)
        {
            throw new RuntimeException("Schema.getDefaultSettings() not available"); //$NON-NLS-1$
        }
        Object selection = invokeGetter(settings, "getSelection"); //$NON-NLS-1$
        if (selection == null)
        {
            throw new RuntimeException("DefaultSettings.getSelection() not available"); //$NON-NLS-1$
        }
        EList<EObject> items = BmDcsHelper.getEObjectList(selection, "getItems"); //$NON-NLS-1$
        if (items == null)
        {
            throw new RuntimeException("Selection.getItems() not available"); //$NON-NLS-1$
        }
        EObject toRemove = null;
        for (EObject it : items)
        {
            Object f = invokeGetter(it, "getField"); //$NON-NLS-1$
            if (f != null && field.equalsIgnoreCase(f.toString()))
            {
                toRemove = it;
                break;
            }
        }
        if (toRemove == null)
        {
            throw notFoundTag(field, "selectedField"); //$NON-NLS-1$
        }
        items.remove(toRemove);
        return "selected field '" + field + "' removed"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * 1.41 / 4a: appends a SettingsVariant to
     * {@code Schema.getVariants().getItems()} (Schema-level, not Settings).
     */
    private Object doAddSettingsVariant(Map<String, String> params, EObject schema)
    {
        String name = required(params, "name"); //$NON-NLS-1$
        String presentation = JsonUtils.extractStringArgument(params, "presentation"); //$NON-NLS-1$
        Object variants = invokeGetter(schema, "getVariants"); //$NON-NLS-1$
        if (variants == null)
        {
            throw new RuntimeException("Schema.getVariants() not available"); //$NON-NLS-1$
        }
        EList<EObject> items = BmDcsHelper.getEObjectList(variants, "getItems"); //$NON-NLS-1$
        if (items == null && variants instanceof EList)
        {
            @SuppressWarnings({ "unchecked", "rawtypes" })
            EList<EObject> coerced = (EList) variants;
            items = coerced;
        }
        if (items == null)
        {
            throw new RuntimeException("Schema.Variants has no items collection"); //$NON-NLS-1$
        }
        // 1.41 idempotency: silent duplicate variants corrupt the report
        // (UI shows only the first, the second becomes invisible junk that
        // cannot be removed through the editor).
        for (EObject existing : items)
        {
            Object existingName = invokeGetter(existing, "getName"); //$NON-NLS-1$
            if (existingName != null && name.equalsIgnoreCase(existingName.toString()))
            {
                throw alreadyExistsTag(name, "settingsVariant"); //$NON-NLS-1$
            }
        }
        Object variant = BmDcsHelper.createElement("createSchemaVariant"); //$NON-NLS-1$
        if (variant == null)
        {
            variant = BmDcsHelper.createElement("createSettingsVariant"); //$NON-NLS-1$
        }
        if (variant == null)
        {
            throw factoryMissingTag("createSchemaVariant, createSettingsVariant"); //$NON-NLS-1$
        }
        BmDcsHelper.setProperty(variant, "name", name); //$NON-NLS-1$
        if (presentation != null)
        {
            BmDcsHelper.setProperty(variant, "presentation", presentation); //$NON-NLS-1$
        }
        items.add((EObject) variant);
        return "settings variant '" + name + "' added"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * 1.41 / 4c: locates an existing parameter in
     * {@code Settings.getDataParameters().getItems()} by name and overwrites
     * its value.
     */
    private Object doSetSettingsParameter(Map<String, String> params, EObject schema)
    {
        String name = required(params, "name"); //$NON-NLS-1$
        String value = JsonUtils.extractStringArgument(params, "value"); //$NON-NLS-1$
        Object settings = invokeGetter(schema, "getDefaultSettings"); //$NON-NLS-1$
        if (settings == null)
        {
            throw new RuntimeException("Schema.getDefaultSettings() not available"); //$NON-NLS-1$
        }
        Object dataParameters = invokeGetter(settings, "getDataParameters"); //$NON-NLS-1$
        if (dataParameters == null)
        {
            throw new RuntimeException("DefaultSettings.getDataParameters() not available"); //$NON-NLS-1$
        }
        EList<EObject> items = BmDcsHelper.getEObjectList(dataParameters, "getItems"); //$NON-NLS-1$
        if (items == null)
        {
            throw new RuntimeException("DataParameters.getItems() not available"); //$NON-NLS-1$
        }
        EObject found = null;
        for (EObject it : items)
        {
            Object n = invokeGetter(it, "getParameter"); //$NON-NLS-1$
            if (n == null)
            {
                n = invokeGetter(it, "getName"); //$NON-NLS-1$
            }
            if (n != null && name.equalsIgnoreCase(n.toString()))
            {
                found = it;
                break;
            }
        }
        if (found == null)
        {
            throw notFoundTag(name, "settingsParameter"); //$NON-NLS-1$
        }
        BmDcsHelper.setProperty(found, "value", value); //$NON-NLS-1$
        return "settings parameter '" + name + "' set"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * 1.41 / 4c: universal cascade-remove operation. {@code itemPath} is a
     * dot-separated path with bracketed indices. Supported roots:
     * {@code Structure[N]}, {@code Filter[N]}, {@code Order[N]},
     * {@code Selection[N]}, {@code ConditionalAppearance[N]},
     * {@code DataParameters[N]}.
     */
    private Object doRemoveSettingsItem(Map<String, String> params, EObject schema)
    {
        String itemPath = required(params, "itemPath"); //$NON-NLS-1$
        Object settings = invokeGetter(schema, "getDefaultSettings"); //$NON-NLS-1$
        if (settings == null)
        {
            throw new RuntimeException("Schema.getDefaultSettings() not available"); //$NON-NLS-1$
        }
        // Parse the path into root.getter[index] tokens
        String[] parts = itemPath.split("\\."); //$NON-NLS-1$
        if (parts.length == 0)
        {
            throw new RuntimeException("itemPath cannot be empty"); //$NON-NLS-1$
        }
        Object current = settings;
        EList<EObject> parentList = null;
        int parentIndex = -1;
        for (String token : parts)
        {
            int lb = token.indexOf('[');
            int rb = token.indexOf(']');
            String collectionName = lb > 0 ? token.substring(0, lb) : token;
            int index = (lb > 0 && rb > lb)
                ? Integer.parseInt(token.substring(lb + 1, rb)) : -1;
            String getterName = "get" + Character.toUpperCase(collectionName.charAt(0)) //$NON-NLS-1$
                + collectionName.substring(1);
            Object collection = invokeGetter(current, getterName);
            if (collection == null)
            {
                throw new RuntimeException("Path segment '" + token //$NON-NLS-1$
                    + "' not resolvable on " + current.getClass().getSimpleName()); //$NON-NLS-1$
            }
            EList<EObject> items = BmDcsHelper.getEObjectList(collection, "getItems"); //$NON-NLS-1$
            if (items == null && collection instanceof EList)
            {
                @SuppressWarnings({ "unchecked", "rawtypes" })
                EList<EObject> coerced = (EList) collection;
                items = coerced;
            }
            if (items == null)
            {
                throw new RuntimeException(getterName + " has no items collection"); //$NON-NLS-1$
            }
            if (index < 0)
            {
                // Walked into a collection root without a specific index -
                // not a removable target by itself
                throw new RuntimeException("Path '" + token //$NON-NLS-1$
                    + "' requires [index] to remove a specific entry"); //$NON-NLS-1$
            }
            if (index >= items.size())
            {
                throw new RuntimeException(getterName + "[" + index //$NON-NLS-1$
                    + "] out of bounds (size=" + items.size() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            parentList = items;
            parentIndex = index;
            current = items.get(index);
        }
        if (parentList == null || parentIndex < 0)
        {
            throw new RuntimeException("itemPath did not resolve to a removable item"); //$NON-NLS-1$
        }
        // Cascade-remove: removing the EObject from its parent EList; EMF
        // automatically detaches the contained subtree.
        parentList.remove(parentIndex);
        return "settings item at '" + itemPath + "' removed (cascade)"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * 1.41 / 4d: removes a ConditionalAppearance item by index from
     * {@code Schema.getConditionalAppearance().getItems()} (or
     * {@code Settings.getConditionalAppearance()} when {@code target=settings}).
     */
    private Object doRemoveConditionalAppearance(Map<String, String> params, EObject schema)
    {
        String indexStr = required(params, "index"); //$NON-NLS-1$
        String target = orDefault(JsonUtils.extractStringArgument(params, "target"), "schema"); //$NON-NLS-1$ //$NON-NLS-2$
        int index;
        try
        {
            index = Integer.parseInt(indexStr);
        }
        catch (NumberFormatException e)
        {
            throw new RuntimeException("index must be an integer"); //$NON-NLS-1$
        }
        Object root = "settings".equalsIgnoreCase(target) //$NON-NLS-1$
            ? invokeGetter(schema, "getDefaultSettings") //$NON-NLS-1$
            : schema;
        Object ca = invokeGetter(root, "getConditionalAppearance"); //$NON-NLS-1$
        if (ca == null)
        {
            throw new RuntimeException(target + ".ConditionalAppearance not available"); //$NON-NLS-1$
        }
        EList<EObject> items = BmDcsHelper.getEObjectList(ca, "getItems"); //$NON-NLS-1$
        if (items == null)
        {
            throw new RuntimeException("ConditionalAppearance.getItems() not available"); //$NON-NLS-1$
        }
        if (index < 0 || index >= items.size())
        {
            throw new RuntimeException("index out of bounds (size=" + items.size() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        items.remove(index);
        return "conditional appearance [" + index + "] removed from " + target; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * 1.41 / 4d: sets appearance properties (font / color / horizontalAlignment /
     * verticalAlignment / textColor) on a single DataSet field.
     */
    private Object doSetDataSetFieldAppearance(Map<String, String> params, EObject schema)
    {
        String dataSetName = required(params, "dataSet"); //$NON-NLS-1$
        String fieldName = required(params, "field"); //$NON-NLS-1$
        EObject dataSet = BmDcsHelper.findByNameInList(schema, "getDataSets", dataSetName); //$NON-NLS-1$
        if (dataSet == null)
        {
            throw notFoundTag(dataSetName, "dataSet"); //$NON-NLS-1$
        }
        EObject field = BmDcsHelper.findByNameInList(dataSet, "getFields", fieldName); //$NON-NLS-1$
        if (field == null)
        {
            throw notFoundTag(fieldName, "dataSetField"); //$NON-NLS-1$
        }
        Object appearance = invokeGetter(field, "getAppearance"); //$NON-NLS-1$
        if (appearance == null)
        {
            throw new RuntimeException("DataSetField.getAppearance() not available"); //$NON-NLS-1$
        }
        int touched = 0;
        for (String prop : new String[] { "font", "textColor", "backColor", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "horizontalAlignment", "verticalAlignment", "border" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            String v = JsonUtils.extractStringArgument(params, prop);
            if (v != null && !v.isEmpty())
            {
                BmDcsHelper.setProperty(appearance, prop, v);
                touched++;
            }
        }
        return "dataset field '" + dataSetName + "." + fieldName //$NON-NLS-1$ //$NON-NLS-2$
            + "' appearance updated (" + touched + " properties)"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * 1.41 / 4c: sets a value on an existing OutputParameter by name in
     * {@code Schema.getOutputParameters()} (Schema-level).
     */
    private Object doSetOutputParameter(Map<String, String> params, EObject schema)
    {
        String name = required(params, "name"); //$NON-NLS-1$
        String value = JsonUtils.extractStringArgument(params, "value"); //$NON-NLS-1$
        EList<EObject> outputParams = BmDcsHelper.getEObjectList(schema, "getOutputParameters"); //$NON-NLS-1$
        if (outputParams == null)
        {
            throw new RuntimeException("Schema.getOutputParameters() not available"); //$NON-NLS-1$
        }
        EObject found = null;
        for (EObject p : outputParams)
        {
            Object n = invokeGetter(p, "getName"); //$NON-NLS-1$
            if (n != null && name.equalsIgnoreCase(n.toString()))
            {
                found = p;
                break;
            }
        }
        if (found == null)
        {
            throw notFoundTag(name, "outputParameter"); //$NON-NLS-1$
        }
        BmDcsHelper.setProperty(found, "value", value); //$NON-NLS-1$
        return "output parameter '" + name + "' set"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * 1.41 / 4a: appends a FilterItemGroup container to
     * {@code Settings.getFilter().getItems()}, allowing nested AND/OR groups.
     */
    private Object doAddSettingsFilterGroup(Map<String, String> params, EObject schema)
    {
        String groupType = orDefault(JsonUtils.extractStringArgument(params, "groupType"), //$NON-NLS-1$
            "AndGroup"); //$NON-NLS-1$
        Object settings = invokeGetter(schema, "getDefaultSettings"); //$NON-NLS-1$
        if (settings == null)
        {
            throw new RuntimeException("Schema.getDefaultSettings() not available"); //$NON-NLS-1$
        }
        Object filter = invokeGetter(settings, "getFilter"); //$NON-NLS-1$
        if (filter == null)
        {
            throw new RuntimeException("DefaultSettings.getFilter() not available"); //$NON-NLS-1$
        }
        Object group = BmDcsHelper.createElement("createFilterItemGroup"); //$NON-NLS-1$
        if (group == null)
        {
            group = BmDcsHelper.createElement("createSettingsFilterGroup"); //$NON-NLS-1$
        }
        if (group == null)
        {
            throw factoryMissingTag("createFilterItemGroup, createSettingsFilterGroup"); //$NON-NLS-1$
        }
        BmDcsHelper.setProperty(group, "groupType", groupType); //$NON-NLS-1$
        EList<EObject> items = BmDcsHelper.getEObjectList(filter, "getItems"); //$NON-NLS-1$
        if (items == null)
        {
            throw new RuntimeException("Filter.getItems() not available"); //$NON-NLS-1$
        }
        items.add((EObject) group);
        return "filter group (" + groupType + ") added"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * 1.41: structured "factory not exposed" error for the AI to surface
     * a {@code dcsFactoryMethodNotFound} tag with the tried method names.
     */
    private MetadataGuards.BlockedGuardException factoryMissingTag(String triedMethods)
    {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("triedMethods", triedMethods); //$NON-NLS-1$
        return new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
            "dcsFactoryMethodNotFound: " + triedMethods, //$NON-NLS-1$
            "EDT factory does not expose this method - GUI fallback required.", //$NON-NLS-1$
            new MetadataGuards.ErrorTag("dcsFactoryMethodNotFound", data))); //$NON-NLS-1$
    }

    /**
     * 1.41: walks {@code structure}, returning the items collection regardless
     * of whether it's an EList directly or a wrapper with {@code getItems()}.
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private EList<EObject> resolveStructureItems(Object structure)
    {
        if (structure instanceof EList)
        {
            return (EList) structure;
        }
        EList<EObject> items = BmDcsHelper.getEObjectList(structure, "getItems"); //$NON-NLS-1$
        if (items == null)
        {
            throw new RuntimeException("Structure has no items collection"); //$NON-NLS-1$
        }
        return items;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private Object invokeGetter(Object target, String methodName)
    {
        try
        {
            java.lang.reflect.Method m = target.getClass().getMethod(methodName);
            return m.invoke(target);
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    private MetadataGuards.BlockedGuardException alreadyExistsTag(String name, String kind)
    {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name); //$NON-NLS-1$
        data.put("kind", kind); //$NON-NLS-1$
        return new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
            kind + " already exists: " + name, //$NON-NLS-1$
            "Use a different name or remove the existing element first.", //$NON-NLS-1$
            new MetadataGuards.ErrorTag("alreadyExists", data))); //$NON-NLS-1$
    }

    private MetadataGuards.BlockedGuardException notFoundTag(String name, String kind)
    {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name); //$NON-NLS-1$
        data.put("kind", kind); //$NON-NLS-1$
        return new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
            kind + " not found: " + name, //$NON-NLS-1$
            "Verify the name and try again.", //$NON-NLS-1$
            new MetadataGuards.ErrorTag("notFound", data))); //$NON-NLS-1$
    }

    private static String required(Map<String, String> params, String key)
    {
        String value = JsonUtils.extractStringArgument(params, key);
        if (value == null || value.isEmpty())
        {
            throw new RuntimeException(key + " is required"); //$NON-NLS-1$
        }
        return value;
    }

    private static String orDefault(String value, String fallback)
    {
        return value != null && !value.isEmpty() ? value : fallback;
    }

    private static Integer extractInteger(Map<String, String> params, String key)
    {
        String s = JsonUtils.extractStringArgument(params, key);
        if (s == null || s.isEmpty())
        {
            return null;
        }
        try
        {
            return Integer.valueOf(s.trim());
        }
        catch (NumberFormatException nfe)
        {
            return null;
        }
    }

    private String handleHelp(Map<String, String> params)
    {
        String topic = JsonUtils.extractStringArgument(params, "topic"); //$NON-NLS-1$
        if (topic == null || topic.isEmpty())
        {
            StringBuilder sb = new StringBuilder("# dcs_workshop\n\n"); //$NON-NLS-1$
            sb.append("DCS schema constructor. 27 operations across 4 groups (1.35 + 1.37).\n\n"); //$NON-NLS-1$
            sb.append("**Implemented (1.37):**\n"); //$NON-NLS-1$
            sb.append("- create_schema, repair_schema\n"); //$NON-NLS-1$
            sb.append("- add_dataset (auto query validation), remove_dataset (cascades calc fields)\n"); //$NON-NLS-1$
            sb.append("- add_field\n"); //$NON-NLS-1$
            sb.append("- add_parameter / set_parameter / remove_parameter / move_parameter\n"); //$NON-NLS-1$
            sb.append("- add_calculated_field / add_total (auto expression validation)\n"); //$NON-NLS-1$
            sb.append("- add_appearance (font/color guard), add_grouping, add_filter\n\n"); //$NON-NLS-1$
            sb.append("**Deferred (followups):**\n"); //$NON-NLS-1$
            sb.append("- add_user_field, add_settings_table, add_chart\n"); //$NON-NLS-1$
            sb.append("- add_filter_group, add_order, select_field / deselect_field\n"); //$NON-NLS-1$
            sb.append("- add_variant, set_param_value, remove_settings_item\n"); //$NON-NLS-1$
            sb.append("- remove_appearance, set_field_appearance, set_output_param\n\n"); //$NON-NLS-1$
            sb.append("**Topics:** workflow, dcsWorkflow, propertyValues, examples, errorTags\n"); //$NON-NLS-1$
            return ToolResult.success().put("help", sb.toString()).toJson(); //$NON-NLS-1$
        }
        switch (topic.toLowerCase())
        {
            case "workflow": //$NON-NLS-1$
            case "dcsworkflow": //$NON-NLS-1$
                return ToolResult.success().put("topic", topic) //$NON-NLS-1$
                    .put("text", buildDcsWorkflowHelp()).toJson(); //$NON-NLS-1$
            case "propertyvalues": //$NON-NLS-1$
                return ToolResult.success().put("topic", topic) //$NON-NLS-1$
                    .put("text", buildPropertyValuesHelp()).toJson(); //$NON-NLS-1$
            case "examples": //$NON-NLS-1$
                return ToolResult.success().put("topic", topic) //$NON-NLS-1$
                    .put("text", buildExamplesHelp()).toJson(); //$NON-NLS-1$
            case "errortags": //$NON-NLS-1$
                return ToolResult.success().put("topic", topic) //$NON-NLS-1$
                    .put("text", buildErrorTagsHelp()).toJson(); //$NON-NLS-1$
            default:
                return ToolResult.error("Unknown topic: " + topic //$NON-NLS-1$
                    + ". Available: dcsWorkflow, propertyValues, examples, errorTags.") //$NON-NLS-1$
                    .toJson();
        }
    }

    private String buildDcsWorkflowHelp()
    {
        return "Step-by-step DCS schema construction.\n\n" //$NON-NLS-1$
            + "1. Create the parent object (Report / DataProcessor):\n" //$NON-NLS-1$
            + "   edit_metadata operation=createObject objectType=Report name=Sales\n\n" //$NON-NLS-1$
            + "2. Create the schema (creates the Template + DCS root):\n" //$NON-NLS-1$
            + "   dcs_workshop operation=create_schema objectName=Report.Sales\n\n" //$NON-NLS-1$
            + "3. Add a dataset with a real query (auto-validated before write):\n" //$NON-NLS-1$
            + "   dcs_workshop operation=add_dataset objectName=Report.Sales name=Main \\\n" //$NON-NLS-1$
            + "       queryText=\"VYBRAT T.* IZ Spravochnik.Tovary KAK T " //$NON-NLS-1$
            + "GDE T.Artikul PODOBNO &Artikul\"\n\n" //$NON-NLS-1$
            + "4. Add parameters and calculated fields:\n" //$NON-NLS-1$
            + "   dcs_workshop operation=add_parameter name=Period type=Date\n" //$NON-NLS-1$
            + "   dcs_workshop operation=add_calculated_field name=Total \\\n" //$NON-NLS-1$
            + "       expression=\"Summa(Tsena * Kolichestvo)\"\n\n" //$NON-NLS-1$
            + "5. Build settings (default groupings / filters / appearance):\n" //$NON-NLS-1$
            + "   dcs_workshop operation=add_grouping field=Tovar groupingType=Standard\n" //$NON-NLS-1$
            + "   dcs_workshop operation=add_filter field=Period comparisonType=Between\n" //$NON-NLS-1$
            + "   dcs_workshop operation=add_appearance conditionType=Greater \\\n" //$NON-NLS-1$
            + "       conditionValue=1000 appearance=\"BackColor=#FFFF00;Font=Arial,11,bold\"\n\n" //$NON-NLS-1$
            + "6. Wire the composer on a form (use edit_metadata):\n" //$NON-NLS-1$
            + "   edit_metadata operation=setupSettingsComposerOnForm \\\n" //$NON-NLS-1$
            + "       formFqn=Report.Sales.Forms.Form\n\n" //$NON-NLS-1$
            + "Direct save to .dcs disk file is automatic for extension projects.\n"; //$NON-NLS-1$
    }

    private String buildPropertyValuesHelp()
    {
        return "Allowed values for DCS operation parameters.\n\n" //$NON-NLS-1$
            + "**type** (Parameter.type, Field.valueType):\n" //$NON-NLS-1$
            + "- Date, Number, String, Boolean\n" //$NON-NLS-1$
            + "- Reference types: CatalogRef.X, DocumentRef.X, EnumRef.X,\n" //$NON-NLS-1$
            + "  ChartOfAccountsRef.X, ChartOfCalculationTypesRef.X, ExchangePlanRef.X,\n" //$NON-NLS-1$
            + "  TaskRef.X, BusinessProcessRef.X.\n\n" //$NON-NLS-1$
            + "**aggregateFunction** (add_total):\n" //$NON-NLS-1$
            + "- Sum, Count, CountDistinct, Min, Max, Avg, BeginDate, EndDate, Array.\n\n" //$NON-NLS-1$
            + "**comparisonType** (add_filter, add_appearance condition):\n" //$NON-NLS-1$
            + "- Equal, NotEqual, Greater, GreaterOrEqual, Less, LessOrEqual\n" //$NON-NLS-1$
            + "- InList, NotInList, InHierarchyList, NotInHierarchyList\n" //$NON-NLS-1$
            + "- InHierarchy, NotInHierarchy\n" //$NON-NLS-1$
            + "- Like, NotLike\n" //$NON-NLS-1$
            + "- BeginsWith, NotBeginsWith, Contains, NotContains\n" //$NON-NLS-1$
            + "- Filled, NotFilled\n" //$NON-NLS-1$
            + "- Between, NotBetween.\n\n" //$NON-NLS-1$
            + "**viewMode** (filter, parameter, selected field):\n" //$NON-NLS-1$
            + "- Auto, Normal, QuickAccess, Inaccessible.\n\n" //$NON-NLS-1$
            + "**groupingType** (add_grouping):\n" //$NON-NLS-1$
            + "- Standard (regular grouping)\n" //$NON-NLS-1$
            + "- DetailRecords (no aggregation, raw rows)\n" //$NON-NLS-1$
            + "- Items (hierarchical inside the same field).\n\n" //$NON-NLS-1$
            + "**appearance names** (add_appearance):\n" //$NON-NLS-1$
            + "- Font, TextColor, BackColor, Border, Format, MinimumWidth,\n" //$NON-NLS-1$
            + "  HorizontalAlign, VerticalAlign.\n" //$NON-NLS-1$
            + "Pass values as strings: 'Arial,12,bold' for Font, '#RRGGBB' for colors.\n"; //$NON-NLS-1$
    }

    private String buildExamplesHelp()
    {
        return "Common dcs_workshop call snippets (JSON-style for quick copy).\n\n" //$NON-NLS-1$
            + "Add a dataset with auto-validated query:\n" //$NON-NLS-1$
            + "{\n" //$NON-NLS-1$
            + "  \"operation\": \"add_dataset\",\n" //$NON-NLS-1$
            + "  \"projectName\": \"MyConfig\",\n" //$NON-NLS-1$
            + "  \"objectName\": \"Report.Sales\",\n" //$NON-NLS-1$
            + "  \"name\": \"Main\",\n" //$NON-NLS-1$
            + "  \"queryText\": \"VYBRAT * IZ Document.Realizatsiya\"\n" //$NON-NLS-1$
            + "}\n\n" //$NON-NLS-1$
            + "Update an existing parameter (only listed fields applied):\n" //$NON-NLS-1$
            + "{\n" //$NON-NLS-1$
            + "  \"operation\": \"set_parameter\",\n" //$NON-NLS-1$
            + "  \"objectName\": \"Report.Sales\",\n" //$NON-NLS-1$
            + "  \"name\": \"Period\",\n" //$NON-NLS-1$
            + "  \"title\": \"Period\",\n" //$NON-NLS-1$
            + "  \"use\": \"Always\"\n" //$NON-NLS-1$
            + "}\n\n" //$NON-NLS-1$
            + "Conditional appearance with font/color guard:\n" //$NON-NLS-1$
            + "{\n" //$NON-NLS-1$
            + "  \"operation\": \"add_appearance\",\n" //$NON-NLS-1$
            + "  \"objectName\": \"Report.Sales\",\n" //$NON-NLS-1$
            + "  \"conditionType\": \"Greater\",\n" //$NON-NLS-1$
            + "  \"conditionValue\": \"100000\",\n" //$NON-NLS-1$
            + "  \"appearance\": \"BackColor=#FFFF00;Font=Arial,11,bold\"\n" //$NON-NLS-1$
            + "}\n\n" //$NON-NLS-1$
            + "Default settings filter Between dates:\n" //$NON-NLS-1$
            + "{\n" //$NON-NLS-1$
            + "  \"operation\": \"add_filter\",\n" //$NON-NLS-1$
            + "  \"objectName\": \"Report.Sales\",\n" //$NON-NLS-1$
            + "  \"field\": \"Period\",\n" //$NON-NLS-1$
            + "  \"comparisonType\": \"Between\",\n" //$NON-NLS-1$
            + "  \"value\": \"BeginOfYear|EndOfYear\"\n" //$NON-NLS-1$
            + "}\n"; //$NON-NLS-1$
    }

    private String buildErrorTagsHelp()
    {
        return "Structured error tags surfaced into the JSON response next to `error`.\n\n" //$NON-NLS-1$
            + "- `notFound` { name, kind } - target child not found.\n" //$NON-NLS-1$
            + "- `alreadyExists` { name, kind } - target child already exists.\n" //$NON-NLS-1$
            + "- `queryValidation` { issues, statistics } - queryText has parse errors\n" //$NON-NLS-1$
            + "    (returned BEFORE the BM transaction opens).\n" //$NON-NLS-1$
            + "- `expressionValidation` { issues, statistics } - DCS expression has errors.\n" //$NON-NLS-1$
            + "- `fontColorGuard` { appearance, hint } - appearance was passed as JSON\n" //$NON-NLS-1$
            + "    instead of 'Name=Value;...' string. Hint shows the expected format.\n" //$NON-NLS-1$
            + "- `supportLock` - schema parent is on vendor support; use an extension.\n\n" //$NON-NLS-1$
            + "Pass `validate_query=false` or `validate_expression=false` to bypass\n" //$NON-NLS-1$
            + "pre-flight validation (use only for trusted templating).\n"; //$NON-NLS-1$
    }

    private String formatResult(BmDcsHelper.Result r, String op)
    {
        if (r.ok)
        {
            ToolResult result = ToolResult.success()
                .put("operation", op) //$NON-NLS-1$
                .put("schemaFqn", r.schemaFqn) //$NON-NLS-1$
                .put("message", r.message != null ? r.message : "ok"); //$NON-NLS-1$ //$NON-NLS-2$
            if (r.directSave != null && r.directSave.ok)
            {
                result.put("directSavePath", r.directSave.filePath) //$NON-NLS-1$
                    .put("directSaveBytes", r.directSave.bytesWritten) //$NON-NLS-1$
                    .put("directSaveMs", r.directSave.totalMs); //$NON-NLS-1$
            }
            applyTags(result, r.tags);
            return result.toJson();
        }
        ToolResult err = ToolResult
            .error(op + " failed: " + (r.error != null ? r.error : "unknown error")) //$NON-NLS-1$ //$NON-NLS-2$
            .put("operation", op) //$NON-NLS-1$
            .put("schemaFqn", r.schemaFqn); //$NON-NLS-1$
        applyTags(err, r.tags);
        return err.toJson();
    }

    private static void applyTags(ToolResult result, Map<String, Object> tags)
    {
        if (result == null || tags == null || tags.isEmpty())
        {
            return;
        }
        for (Map.Entry<String, Object> entry : tags.entrySet())
        {
            result.put(entry.getKey(), entry.getValue());
        }
    }

    private String suggest(String op)
    {
        String lower = op.toLowerCase();
        for (String known : OPS.keySet())
        {
            if (known.toLowerCase().contains(lower) || lower.contains(known.toLowerCase()))
            {
                return known;
            }
        }
        List<String> all = new java.util.ArrayList<>(OPS.keySet());
        Collections.sort(all);
        return all.isEmpty() ? "(none)" : all.get(0); //$NON-NLS-1$
    }

    private static Map<String, String> buildOpsCatalog()
    {
        Map<String, String> m = new LinkedHashMap<>();
        for (String op : Arrays.asList(
            // 1.35
            "create_schema", "add_dataset", "add_field", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "add_parameter", "set_parameter", "remove_parameter", "move_parameter", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "add_calculated_field", "add_total", "repair_schema", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            // 1.36
            "add_user_field", "remove_dataset", //$NON-NLS-1$ //$NON-NLS-2$
            "add_grouping", "add_settings_table", "add_chart", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "add_filter", "add_filter_group", "add_order", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "select_field", "deselect_field", //$NON-NLS-1$ //$NON-NLS-2$
            "add_variant", "set_param_value", "remove_settings_item", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "add_appearance", "remove_appearance", "set_field_appearance", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "set_output_param")) //$NON-NLS-1$
        {
            m.put(op, op);
        }
        return Collections.unmodifiableMap(m);
    }
}
