package org.example.ai.agent.trace.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 运行步骤记录实体。
 *
 * 对应 ai_run_step 表。
 * RoutePlan 中的每一个 PlanStep，执行后都写一条 RunStep。
 */
@Data
@TableName("ai_run_step")
public class RunStep {

    /**
     * 主键 ID。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 本次 Agent 运行 ID。
     */
    private String runId;

    /**
     * 步骤编号。
     */
    private Integer stepNo;

    /**
     * 步骤类型。
     *
     * 示例：
     * BUSINESS_TOOL、RAG、LLM_SUMMARY。
     */
    private String stepType;

    /**
     * 步骤名称。
     */
    private String stepName;

    /**
     * 能力编码。
     *
     * BUSINESS_TOOL 步骤会有值。
     */
    private String capabilityCode;

    /**
     * 步骤入参 JSON。
     */
    private String inputJson;

    /**
     * 步骤出参 JSON。
     */
    private String outputJson;

    /**
     * 步骤状态。
     *
     * 示例：
     * SUCCESS、FAILED、SKIPPED。
     */
    private String status;

    /**
     * 错误信息。
     */
    private String errorMessage;

    /**
     * 步骤耗时，单位毫秒。
     */
    private Long durationMs;

    /**
     * 创建时间。
     */
    @TableField(fill = FieldFill.INSERT)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createdAt;
}