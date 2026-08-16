package org.example.ai.agent.workflow.answer.report.template;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.ai.agent.chat.vo.ReportSchemaVO;
import org.example.ai.agent.common.enums.ReportType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 项目结算固定报告模板。
 *
 * 后端只输出结构化业务字段，页面布局由前端固定组件负责。
 */
@Component
public class ProjectSettlementReportTemplate implements ReportTemplate {

    private static final String WORKFLOW_CODE = "project_settlement_list";
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm");

    @Override
    public boolean supports(String workflowCode) {
        return WORKFLOW_CODE.equals(workflowCode);
    }

    @Override
    public ReportType reportType() {
        return ReportType.PROJECT_SETTLEMENT;
    }

    @Override
    public List<ReportSchemaVO.Section> buildSections(JsonNode safeResult) {
        SettlementData data = extractData(safeResult);
        return List.of(
                new ReportSchemaVO.Section(
                        "SETTLEMENT_DETAILS",
                        "项目结算报告",
                        List.of(),
                        List.of(),
                        data.detailRows()
                ),
                new ReportSchemaVO.Section(
                        "SETTLEMENT_ATTACHMENTS",
                        "结算附件",
                        List.of(),
                        List.of(),
                        data.attachmentRows()
                )
        );
    }

    /**
     * 从工作流结果中提取项目明细。
     *
     * 兼容两种结果结构：
     * 1. result.items
     * 2. result.workflowData.items
     */
    private SettlementData extractData(JsonNode safeResult) {
        List<Map<String, Object>> detailRows = new ArrayList<>();
        List<Map<String, Object>> attachmentRows = new ArrayList<>();
        JsonNode outerItems = resolveOuterItems(safeResult);
        if (outerItems == null || !outerItems.isArray()) {
            return new SettlementData(detailRows, attachmentRows);
        }
        for (int outerIndex = 0; outerIndex < outerItems.size(); outerIndex++) {
            JsonNode outerItem = outerItems.get(outerIndex);
            if (!outerItem.path("success").asBoolean(false)) {
                continue;
            }
            collectProjectRows(
                    outerItem.path("data").path("items"),
                    outerIndex,
                    detailRows,
                    attachmentRows
            );
        }

        return new SettlementData(detailRows, attachmentRows);
    }

    /**
     * 兼容当前工作流结果和历史工作流结果的数据路径。
     */
    private JsonNode resolveOuterItems(JsonNode safeResult) {
        if (safeResult == null || safeResult.isNull()) {
            return null;
        }
        // 当前工作流实际返回：{ "items": [] }
        JsonNode rootItems = safeResult.path("items");
        if (rootItems.isArray()) {
            return rootItems;
        }
        // 兼容历史结构：{ "workflowData": { "items": [] } }
        JsonNode workflowItems = safeResult
                .path("workflowData")
                .path("items");

        return workflowItems.isArray() ? workflowItems : null;
    }

    /**
     * 提取项目列表和项目结算详情。
     */
    private void collectProjectRows(JsonNode projectItems,int outerIndex,List<Map<String, Object>> detailRows,
                                    List<Map<String, Object>> attachmentRows) {

        if (projectItems == null || !projectItems.isArray()) {
            return;
        }
        for (int projectIndex = 0; projectIndex < projectItems.size();projectIndex++) {
            JsonNode projectResult = projectItems.get(projectIndex);
            JsonNode project = projectResult.path("item");

            /*
             * 明细查询失败或被跳过时，
             * 仍然保留项目基础信息，避免整个项目从报告中消失。
             */
            if (!projectResult.path("success").asBoolean(false)) {
                if (project.isObject()) {
                    ProjectContext context = new ProjectContext(
                            resolveProjectKey(project, outerIndex, projectIndex),
                            LocalTime.now().format(TIME_FORMAT),
                            readDecimal(project, "contractAmount"),
                            readDecimal(project, "settlementAmount"),
                            readDecimal(project, "outputAmount"),
                            0,
                            0
                    );
                    Map<String, Object> row =buildProjectFields(project, context);
                    row.put("unitPresent", false);
                    row.put("recordPresent", false);
                    String errorMessage =readText(projectResult, "errorMessage");
                    row.put("recordAttention",errorMessage == null? "结算明细未查询" : errorMessage);detailRows.add(row);
                }
                continue;
            }
            collectSettlementRows(project, projectResult.path("data").path("settlementInfos"),
                    outerIndex, projectIndex, detailRows, attachmentRows);
        }
    }

    /**
     * 构建一个项目的固定展示行。
     */
    private void collectSettlementRows(
            JsonNode project,
            JsonNode settlementInfos,
            int outerIndex,
            int projectIndex,
            List<Map<String, Object>> detailRows,
            List<Map<String, Object>> attachmentRows) {

        String projectKey = resolveProjectKey(
                project,
                outerIndex,
                projectIndex
        );
        int unitCount = settlementInfos.isArray()
                ? settlementInfos.size()
                : 0;
        int recordCount = countRecords(settlementInfos);
        BigDecimal projectContractAmount = resolveProjectContractAmount(
                project,
                settlementInfos
        );
        BigDecimal projectSettlementAmount = readDecimal(
                project,
                "settlementAmount"
        );
        BigDecimal outputAmount = readDecimal(project, "outputAmount");
        ProjectContext context = new ProjectContext(
                projectKey,
                LocalTime.now().format(TIME_FORMAT),
                projectContractAmount,
                projectSettlementAmount,
                outputAmount,
                unitCount,
                recordCount
        );
        if (!settlementInfos.isArray() || settlementInfos.isEmpty()) {
            detailRows.add(buildEmptyProjectRow(project, context));
            return;
        }

        for (int unitIndex = 0; unitIndex < settlementInfos.size(); unitIndex++) {

            JsonNode settlementInfo = settlementInfos.get(unitIndex);
            collectUnitRows(
                    project,
                    settlementInfo,
                    context,
                    unitIndex,
                    detailRows,
                    attachmentRows
            );
        }
    }

    /**
     * 构建一个结算单位下的明细行。
     */
    private void collectUnitRows(
            JsonNode project,
            JsonNode settlementInfo,
            ProjectContext context,
            int unitIndex,
            List<Map<String, Object>> detailRows,
            List<Map<String, Object>> attachmentRows) {

        String unitKey = context.projectKey() + "-unit-" + unitIndex;
        JsonNode settlements = settlementInfo.path("settlements");
        BigDecimal unitSettlementAmount = sumSettlements(settlements);

        if (!settlements.isArray() || settlements.isEmpty()) {
            detailRows.add(buildDetailRow(
                    project,
                    settlementInfo,
                    null,
                    context,
                    unitKey,
                    unitSettlementAmount,
                    null,
                    false
            ));
            return;
        }

        for (int rowIndex = 0; rowIndex < settlements.size();rowIndex++) {
            JsonNode settlement = settlements.get(rowIndex);
            String rowKey = unitKey + "-row-" + rowIndex;
            detailRows.add(buildDetailRow(
                    project,
                    settlementInfo,
                    settlement,
                    context,
                    unitKey,
                    unitSettlementAmount,
                    rowKey,
                    true
            ));
            collectAttachments(
                    settlement,
                    rowKey,
                    attachmentRows
            );
        }
    }

    /**
     * 项目没有结算单位时仍返回项目抬头和汇总信息。
     */
    private Map<String, Object> buildEmptyProjectRow(
            JsonNode project,
            ProjectContext context) {

        Map<String, Object> row = buildProjectFields(project, context);
        row.put("unitPresent", false);
        row.put("recordPresent", false);
        return row;
    }

    /**
     * 构建只包含标量值的结算明细行。
     */
    private Map<String, Object> buildDetailRow(JsonNode project,JsonNode settlementInfo,JsonNode settlement,
            ProjectContext context,String unitKey, BigDecimal unitSettlementAmount,String rowKey,boolean recordPresent) {
        Map<String, Object> row = buildProjectFields(project, context);
        row.put("unitPresent", true);
        row.put("unitKey", unitKey);
        row.put("unitName", readScalar(
                settlementInfo,
                "unitName"
        ));
        row.put("unitContractAmountText", formatWanAmount(
                readDecimal(settlementInfo, "contract")
        ));
        row.put("unitSettlementAmountText", formatYuanAmount(
                unitSettlementAmount
        ));
        row.put("rowKey", rowKey);
        row.put("recordPresent", recordPresent);
        row.put("settlementDate", readScalar(
                settlement,
                "settlementDate"
        ));
        row.put("amountText", formatYuanAmount(
                readDecimal(settlement, "amount")
        ));
        row.put("taxAmountText", formatYuanAmount(
                readDecimal(settlement, "taxAmount")
        ));
        row.put("warrantyAmountText", formatYuanAmount(
                readDecimal(settlement, "warrantyAmount")
        ));
        row.put("warrantyDate", readScalar( settlement,"warrantyDate"));
        return row;
    }

    /**
     * 构建项目级公共字段，避免在每个分支重复拼装。
     */
    private Map<String, Object> buildProjectFields(
            JsonNode project,
            ProjectContext context) {

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("projectKey", context.projectKey());
        row.put("projectCode", readScalar(project, "projectCode"));
        row.put("deptName", readScalar(project, "deptName"));
        row.put("generatedAt", context.generatedAt());
        // 中文注释：当前接口使用settlementStatusName，兼容旧接口的auditStatusName字段。
        String auditStatusName = readText(project,"auditStatusName");
        if (auditStatusName == null) {
            auditStatusName = readText(project,"settlementStatusName");
        }
        row.put("auditStatusName",auditStatusName == null
                        ? "审批中"
                        : auditStatusName
        );
        row.put("projectContractAmountText", formatWanAmount(
                context.projectContractAmount()
        ));
        row.put("projectSettlementAmountText", formatWanFromYuan(
                context.projectSettlementAmount()
        ));
        row.put("outputAmountText", formatWanFromYuan(
                context.outputAmount()
        ));
        row.put("reportSummary", buildSummary(project, context));
        row.put("measurementAttention", buildMeasurementAttention(
                context.outputAmount()
        ));
        row.put("recordAttention","共存在" + context.recordCount() + "条结算记录");
        return row;
    }

    /**
     * 普通数据查询使用确定性摘要，不调用大模型。
     */
    private String buildSummary(JsonNode project,ProjectContext context) {

        String auditStatusName = readText(project, "auditStatusName");
        String approvalText = auditStatusName == null
                ? "审批中"
                : auditStatusName;

        return "本项目当前" + approvalText
                + "，共涉及" + context.unitCount()
                + "个结算单位、" + context.recordCount()
                + "条结算记录。当前累计结算金额"
                + formatWanFromYuan(context.projectSettlementAmount())
                + "，计量金额"
                + formatWanFromYuan(context.outputAmount())
                + "。";
    }

    private String buildMeasurementAttention(BigDecimal outputAmount) {
        if (outputAmount == null || outputAmount.compareTo(BigDecimal.ZERO) == 0) {
            return "当前计量金额为0";
        }
        return "当前计量金额为" + formatWanFromYuan(outputAmount);
    }

    /**
     * 附件单独输出为标量行，文件名称点击后直接请求已有文件地址。
     */
    private void collectAttachments(
            JsonNode settlement,
            String rowKey,
            List<Map<String, Object>> attachmentRows) {

        appendFiles(settlement.path("fileList"),rowKey,"已盖章", attachmentRows);
        appendFiles(
                settlement.path("notFileList"),
                rowKey,
                "未盖章",
                attachmentRows
        );
    }

    private void appendFiles(
            JsonNode files,
            String rowKey,
            String fileStatus,
            List<Map<String, Object>> attachmentRows) {

        if (!files.isArray()) {
            return;
        }

        for (int fileIndex = 0;fileIndex < files.size();fileIndex++) {

            JsonNode file = files.get(fileIndex);
            String fileName = readText(file, "original");
            String fileUrl = readText(file, "url");

            if (fileName == null && fileUrl == null) {
                continue;
            }

            Map<String, Object> attachment = new LinkedHashMap<>();
            attachment.put("rowKey", rowKey);
            attachment.put(
                    "fileKey",
                    rowKey + "-file-" + fileStatus + "-" + fileIndex
            );
            attachment.put("fileName", fileName);
            attachment.put("fileUrl", fileUrl);
            attachment.put("fileStatus", fileStatus);
            attachmentRows.add(attachment);
        }
    }

    private int countRecords(JsonNode settlementInfos) {
        if (!settlementInfos.isArray()) {
            return 0;
        }
        int count = 0;
        for (JsonNode settlementInfo : settlementInfos) {
            JsonNode settlements = settlementInfo.path("settlements");
            if (settlements.isArray()) {
                count += settlements.size();
            }
        }
        return count;
    }

    private BigDecimal resolveProjectContractAmount(
            JsonNode project,
            JsonNode settlementInfos) {

        BigDecimal projectContractAmount = readDecimal(
                project,
                "contractAmount"
        );
        if (projectContractAmount != null) {
            return projectContractAmount;
        }

        BigDecimal total = BigDecimal.ZERO;
        if (!settlementInfos.isArray()) {
            return total;
        }

        for (JsonNode settlementInfo : settlementInfos) {
            BigDecimal contract = readDecimal(
                    settlementInfo,
                    "contract"
            );
            if (contract != null) {
                total = total.add(contract);
            }
        }
        return total;
    }

    private BigDecimal sumSettlements(JsonNode settlements) {
        BigDecimal total = BigDecimal.ZERO;
        if (!settlements.isArray()) {
            return total;
        }

        for (JsonNode settlement : settlements) {
            BigDecimal amount = readDecimal(settlement, "amount");
            if (amount != null) {
                total = total.add(amount);
            }
        }
        return total;
    }

    private String resolveProjectKey(
            JsonNode project,
            int outerIndex,
            int projectIndex) {

        String projectCode = readText(project, "projectCode");
        if (projectCode != null) {
            return projectCode;
        }
        return "project-" + outerIndex + "-" + projectIndex;
    }

    /**
     * 读取字符串、数字或布尔值，拒绝对象和数组进入展示行。
     */
    private Object readScalar(JsonNode parent, String fieldName) {
        if (parent == null || parent.isNull()) {
            return null;
        }
        JsonNode value = parent.get(fieldName);
        if (value == null || value.isNull()
                || value.isMissingNode()
                || value.isContainerNode()) {
            return null;
        }

        if (value.isNumber()) {
            return value.numberValue();
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        return value.asText();
    }

    private String readText(JsonNode parent, String fieldName) {
        Object value = readScalar(parent, fieldName);
        if (value == null) {
            return null;
        }

        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private BigDecimal readDecimal(JsonNode parent, String fieldName) {
        Object value = readScalar(parent, fieldName);
        if (value == null) {
            return null;
        }

        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String formatWanAmount(BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        return amount.setScale(2, RoundingMode.HALF_UP)
                .toPlainString() + " 万元";
    }

    private String formatWanFromYuan(BigDecimal amount) {
        if (amount == null) {
            return "0.00 万元";
        }
        return amount.divide(BigDecimal.valueOf(10_000),2,
                        RoundingMode.HALF_UP)
                .toPlainString() + " 万元";
    }

    private String formatYuanAmount(BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        return amount.setScale(2, RoundingMode.HALF_UP)
                .toPlainString() + " 元";
    }

    private record SettlementData(
            List<Map<String, Object>> detailRows,
            List<Map<String, Object>> attachmentRows) {
    }

    private record ProjectContext(
            String projectKey,
            String generatedAt,
            BigDecimal projectContractAmount,
            BigDecimal projectSettlementAmount,
            BigDecimal outputAmount,
            int unitCount,
            int recordCount) {
    }
}
