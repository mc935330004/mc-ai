package org.example.ai.agent.tool;

import lombok.Builder;
import lombok.Data;
import org.example.ai.agent.answer.model.AnswerFact;

import java.util.List;

/**
 * 工具统一返回结果。
 *
 * 不管底层业务接口返回什么格式，
 * 这里都进行统一包装，方便：
 * 1. GraphSpec 工作流处理；
 * 2. AnswerComposer 生成回答；
 * 3. 前端展示执行结果；
 * 4. Run、Trace 记录执行摘要。
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
     * 兼容旧工作流的业务数据。
     *
     * P0阶段继续保存 displayData，
     * 避免已有工作流和前端页面立即失效。
     *
     * 新工作流中的下游节点应当读取 workflowData。
     */
    private Object data;

    /**
     * 工作流机器数据。
     *
     * 字段名称使用字段字典中的 fieldName，
     * 例如：
     * id
     * projectCode
     * unitId
     * projectTypeId
     *
     * GraphSpec下游表达式必须优先读取本字段。
     */
    private Object workflowData;

    /**
     * 前端和大模型展示数据。
     *
     * 字段名称可以使用字段字典中的中文名称，
     * 例如：
     * 记录ID
     * 项目编码
     * 单位ID
     * 项目类型ID
     */
    private Object displayData;

    /**
     * 业务系统返回的业务状态码。
     *
     * 例如：
     * 200
     * 0
     * SUCCESS
     */
    private String businessCode;

    /**
     * 业务系统返回的业务消息。
     */
    private String businessMessage;

    /**
     * 是否为空数据。
     *
     * null、空数组、空对象都可以标记为true。
     * 后续GraphSpec可以根据该字段执行条件判断。
     */
    private boolean emptyData;

    /**
     * 字段语义说明。
     *
     * 字段信息来自已发布的字段字典。
     */
    private List<FieldMeta> fields;

    /**
     * 简短执行摘要。
     *
     * 用于日志、SSE、Trace和运行记录展示。
     */
    private String summary;

    /**
     * 系统错误码。
     */
    private String errorCode;

    /**
     * 安全的错误信息。
     *
     * 不能包含Token、Cookie、Authorization
     * 或完整原始业务响应。
     */
    private String errorMessage;

    /**
     * 原始业务接口响应。
     *
     * 普通Agent和工作流运行不得保存完整raw。
     * 当前只允许管理端接口测试场景临时使用。
     */
    private Object raw;

    /**
     * 当前工具实际调用参数。
     *
     * 保存的是输入映射、inputRef解析后的最终参数，
     * RunStepRecorder会将安全参数写入运行步骤记录。
     *
     * 敏感字段必须在写入前完成脱敏。
     */
    private Object input;

    /**
     * 根据字段字典从业务响应中提取的标准事实。
     *
     * AnswerComposer优先使用facts生成最终回答，
     * 不直接依赖完整raw响应。
     */
    private List<AnswerFact> facts;
}