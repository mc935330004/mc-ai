package org.example.ai.agent.chat.memory.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 报告完成后等待用户回答的通用业务追问状态。
 *
 * 这里只保存定位信息和已经确定的目标参数，
 * 不保存完整报告，也不保存全部候选业务数据。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PendingReportFollowUp {

    /**
     * 来源报告类型。
     */
    private String sourceReportType;

    /**
     * 来源工作流编码。
     */
    private String sourceWorkflowCode;

    /**
     * 来源工作流发布版本ID。
     */
    private Long sourceWorkflowVersionId;

    /**
     * 来源安全结果快照ID。
     */
    private String sourceArtifactId;

    /**
     * 目标类型：
     * CAPABILITY 表示直接执行单个只读能力；
     * WORKFLOW 表示执行多节点工作流。
     */
    private String targetType;

    /**
     * 目标能力编码或目标工作流编码。
     */
    private String targetCode;

    /**
     * 展示给用户的追问提示。
     */
    private String prompt;

    /**
     * 已经从来源报告解析出的目标参数。
     *
     * 用户选择相关参数将在唯一匹配后补充。
     */
    private Map<String, Object> inheritedInput = new LinkedHashMap<>();
}