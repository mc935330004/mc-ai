package org.example.ai.agent.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 聊天写操作预览对象。
 *
 * 只返回前端确认操作需要的数据，
 * 不直接暴露 Agent 内部的 DynamicCapabilityPlan。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActionPreviewVO {

    /**
     * 本次 Agent 运行编号，也是待确认操作编号。
     */
    private String runId;

    /**
     * 即将执行的能力编码。
     */
    private String capabilityCode;

    /**
     * 即将执行的能力名称。
     */
    private String capabilityName;

    /**
     * 大模型生成的操作说明。
     */
    private String actionSummary;

    /**
     * 即将提交给业务系统的参数。
     *
     * 前端只能展示这些参数，确认时不能重新提交或修改参数。
     */
    private Map<String, Object> input;

    /**
     * 当前操作状态，例如 PENDING。
     */
    private String status;

    /**
     * 操作过期时间。
     */
    private LocalDateTime expireAt;

    /**
     * 是否需要用户确认。
     */
    private Boolean requireConfirm;

    /**
     * 中文展示参数。
     *
     * 只用于预览，不能提交给业务接口。
     */
    private Map<String, Object> displayInput;
}