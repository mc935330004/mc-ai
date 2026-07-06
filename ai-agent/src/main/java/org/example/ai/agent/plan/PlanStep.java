package org.example.ai.agent.plan;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 运行计划中的单个步骤。
 *
 * 一个用户问题可能会被拆成多个步骤执行。
 *
 * 示例：
 * 用户问：“查询 A 项目回款情况，并分析风险”
 *
 * 可以拆成：
 * 1. 查询项目
 * 2. 查询合同
 * 3. 查询回款
 * 4. 检索风险制度
 * 5. 汇总生成回答
 */
@Data
@Builder
public class PlanStep {

    /**
     * 步骤编号。
     *
     * 从 1 开始，表示执行顺序。
     */
    private Integer stepNo;

    /**
     * 步骤类型。
     *
     * 决定后续由哪个执行器处理。
     */
    private StepType stepType;

    /**
     * 步骤名称。
     *
     * 给开发人员、前端、执行轨迹页面查看。
     */
    private String stepName;

    /**
     * 能力编码。
     *
     * 当 stepType = BUSINESS_TOOL 时使用。
     *
     * 示例：
     * - pm.project.getByName
     * - pm.contract.listByProjectId
     * - pm.payment.summaryByProjectId
     */
    private String capabilityCode;

    /**
     * RAG 检索问题。
     *
     * 当 stepType = RAG 时使用。
     */
    private String ragQuery;

    /**
     * 当前步骤的直接输入参数。
     *
     * 示例：
     * {
     *   "projectName": "A项目"
     * }
     */
    private Map<String, Object> input;

    /**
     * 当前步骤从上一步结果中引用的参数。
     *
     * 示例：
     * {
     *   "projectId": "$.project.id"
     * }
     *
     * 第一版可以先只存结构，不急着解析。
     */
    private Map<String, String> inputRef;

    /**
     * 当前步骤输出结果保存到变量池中的 key。
     *
     * 示例：
     * - project
     * - contracts
     * - paymentSummary
     * - riskDocs
     */
    private String outputKey;

    /**
     * 当前步骤需要读取哪些上游结果。
     *
     * 当 stepType = LLM_SUMMARY 时常用。
     */
    private List<String> inputKeys;
}