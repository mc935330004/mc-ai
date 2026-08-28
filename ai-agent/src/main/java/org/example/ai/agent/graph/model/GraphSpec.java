package org.example.ai.agent.graph.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.databind.JsonNode;
import org.example.ai.agent.common.enums.WorkflowPresentationMode;
import org.example.ai.agent.graph.model.report.ReportDefinitionSpec;
import org.example.ai.agent.graph.model.risk.WorkflowRiskRuleSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作流图定义。
 *
 * 本对象属于可编辑的草稿模型，
 * 不能直接交给运行器执行。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public class GraphSpec {

    /**
     * GraphSpec协议版本。
     */
    @Builder.Default
    private String version = "1.0";

    /**
     * 工作流稳定编码。
     *
     * 嵌套ForEach子图可以为空。
     */
    private String code;

    /**
     * 工作流名称。
     *
     * 嵌套ForEach子图可以为空。
     */
    private String name;

    /**
     * 随工作流版本发布的风险判定规则。
     *
     * 旧工作流没有该配置时使用空集合，
     * 不影响原有工作流执行。
     */
    @Builder.Default
    private List<WorkflowRiskRuleSpec> riskRules = new ArrayList<>();

    @Builder.Default
    private List<GraphNodeSpec> nodes =new ArrayList<>();

    @Builder.Default
    private List<GraphEdgeSpec> edges = new ArrayList<>();
    // 其余代码不变

    /**
     * 工作流对外输入JSON Schema。
     *
     * Planner提取出的输入和执行接口传入的输入，
     * 都必须经过该Schema校验。
     */
    private JsonNode inputSchema;

    /**
     * 工作流默认展示方式。
     *
     * 未配置时统一使用智能选择，
     * 不再保留旧报表兼容模式。
     */
    @Builder.Default
    private WorkflowPresentationMode presentationMode = WorkflowPresentationMode.AUTO;

    /**
     * 当前工作流发布版本使用的固定报告定义。
     *
     * 只有需要生成完整报表的工作流才需要配置。
     * 报表内容全部根据该配置生成，不再调用业务专用模板。
     */
    private ReportDefinitionSpec reportDefinition;
}