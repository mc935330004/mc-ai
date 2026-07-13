package org.example.ai.agent.tool;

import lombok.Builder;
import lombok.Data;
import org.example.ai.agent.answer.model.AnswerFact;
import org.example.ai.agent.plan.PlanStep;

import java.util.List;

/**
 * 工具统一返回结果。
 *
 * 不管底层业务接口返回什么格式，这里都统一包装，
 * 方便 AnswerComposer 和前端统一处理。
 */
@Data
@Builder
public class ToolResult {

    /**
     * 是否执行成功。
     */
    private boolean success;

    /**
     * 当前调用的能力编码。
     */
    private String capabilityCode;

    /**
     * 当前步骤输出变量名。
     */
    private String outputKey;

    /**
     * 业务数据结果。
     */
    private Object data;

    /**
     * 字段语义说明。
     */
    private List<FieldMeta> fields;

    /**
     * 简短摘要，方便日志、SSE、Trace 展示。
     */
    private String summary;

    /**
     * 错误码。
     */
    private String errorCode;

    /**
     * 错误信息。
     */
    private String errorMessage;

    /**
     * 原始业务接口返回。
     *
     * 第一版可以保留，方便排查接口字段问题。
     */
    private Object raw;

    /**
     * 当前工具实际入参。
     *
     * 注意：
     * 这里保存的是 step.input + inputRef 解析后的最终参数。
     * RunStepRecorder 会把它写入 ai_run_step.input_json。
     */
    private Object input;

    /**
     * 根据字段字典从真实接口响应中提取出的标准事实。
     *
     * AnswerComposer 优先使用 facts，
     * 不再直接依赖 raw 原始响应。
     */
    private List<AnswerFact> facts;
}