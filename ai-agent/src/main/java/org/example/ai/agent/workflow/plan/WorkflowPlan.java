package org.example.ai.agent.workflow.plan;

import lombok.Builder;
import lombok.Data;
import org.example.ai.agent.common.enums.WorkflowPlanStatus;

import java.util.LinkedHashMap;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
/**
 * 工作流规划结果。
 */
@Data
@Builder
public class WorkflowPlan {

    private WorkflowPlanStatus status;

    private String workflowCode;

    private String workflowName;

    /**
     * 规划时选中的发布版本。
     */
    private Long versionId;

    @Builder.Default
    private Map<String, Object> input =
            new LinkedHashMap<>();

    private double confidence;

    private String reason;

    private String clarifyQuestion;

    /**
     * READ 或 WRITE。
     */
    private String sideEffect;

    /**
     * WRITE 工作流中的能力信息。
     */
    private String actionCapabilityCode;

    private String actionCapabilityName;

    /**
     * 已根据工作流输入映射生成的固定写入参数。
     */
    @Builder.Default
    private Map<String, Object> actionInput =new LinkedHashMap<>();
    /**
     * WRITE能力发布版本ID。
     *
     * 表单提交时必须重新校验，防止用户打开表单后能力版本发生变化。
     */
    private Long actionCapabilityVersionId;

    /**
     * WRITE能力发布快照中的输入Schema。
     *
     * 用于前端渲染通用动态表单。
     */
    private JsonNode actionInputSchema;

    /**
     * 操作预览展示参数。
     *
     * 下拉字段保存中文名称，不能提交给业务接口。
     */
    @Builder.Default
    private Map<String, Object> actionDisplayInput = new LinkedHashMap<>();
    /**
     * 判断当前计划是否为可确认的写工作流。
     */
    public boolean isWriteAction() {
        return "WRITE".equalsIgnoreCase(sideEffect) && actionCapabilityCode != null && !actionCapabilityCode.isBlank();
    }
    public boolean isReady() {
        return status == WorkflowPlanStatus.READY;
    }
}