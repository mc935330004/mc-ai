package org.example.ai.agent.graph.runtime.executor;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.enums.GraphNodeType;
import org.example.ai.agent.graph.compiler.CompiledGraphNode;
import org.example.ai.agent.graph.config.CapabilityNodeConfig;
import org.example.ai.agent.graph.runtime.GraphExecutionContext;
import org.example.ai.agent.graph.runtime.GraphNodeExecutor;
import org.example.ai.agent.graph.runtime.GraphNodeResult;
import org.example.ai.agent.graph.runtime.GraphRuntimeExpressionResolver;
import org.example.ai.agent.plan.PlanStep;
import org.example.ai.agent.plan.StepType;
import org.example.ai.agent.tool.BusinessCapabilityExecutor;
import org.example.ai.agent.tool.ToolExecutionContext;
import org.example.ai.agent.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 业务能力GraphSpec节点执行器。
 *
 * 核心职责：
 * 1. 解析节点输入映射；
 * 2. 构造业务能力执行步骤；
 * 3. 传递可信执行上下文；
 * 4. 把ToolResult转换为GraphNodeResult。
 *
 * 本类不直接调用HTTP接口，
 * 所有业务接口调用继续由BusinessCapabilityExecutor负责。
 */
@Component
@RequiredArgsConstructor
public class CapabilityGraphNodeExecutor
        implements GraphNodeExecutor {

    private final BusinessCapabilityExecutor
            businessCapabilityExecutor;

    private final GraphRuntimeExpressionResolver
            expressionResolver;

    /**
     * 当前执行器负责CAPABILITY节点。
     */
    @Override
    public GraphNodeType type() {
        return GraphNodeType.CAPABILITY;
    }

    /**
     * 执行业务能力节点。
     */
    @Override
    public GraphNodeResult execute(
            CompiledGraphNode node,
            GraphExecutionContext context) {

        /*
         * 编译后的节点配置必须是CapabilityNodeConfig。
         *
         * 如果出现其他类型，说明编译或运行快照存在问题，
         * 必须失败关闭，不能猜测配置内容。
         */
        if (!(node.config()
                instanceof CapabilityNodeConfig config)) {

            return GraphNodeResult.failure(
                    node,
                    "GRAPH_CAPABILITY_CONFIG_INVALID",
                    "能力节点配置类型不正确"
            );
        }

        /*
         * 解析GraphSpec输入映射。
         *
         * 例如：
         * queryStr = $input.projectKeys.0
         * id = $item.id
         */
        Map<String, Object> resolvedInput =
                expressionResolver.resolveMap(
                        config.inputMapping(),
                        context
                );

        /*
         * 继续复用现有PlanStep和BusinessCapabilityExecutor，
         * 不在Graph执行器中复制能力调用逻辑。
         */
        PlanStep step =
                PlanStep.builder()
                        .stepType(
                                StepType.BUSINESS_TOOL
                        )
                        .stepName(node.name())
                        .capabilityCode(
                                config.capabilityCode()
                        )
                        .input(resolvedInput)
                        .outputKey(node.outputKey())
                        .nodeId(node.id())
                        .executionPath(
                                context.getExecutionPath()
                        )
                        .build();

        /*
         * 构造安全工具执行上下文。
         *
         * Authorization和secureContext只向能力网关传递，
         * 不能写入普通Graph变量。
         */
        ToolExecutionContext toolContext =
                ToolExecutionContext.builder()
                        .runId(context.getRunId())
                        .userId(context.getUserId())
                        .variables(
                                context.snapshotVariables()
                        )
                        .userContext(
                                context.getUserContext()
                        )
                        .authorization(
                                context.getAuthorization()
                        )
                        .secureContext(
                                context.getSecureContext()
                        )
                        .currentItem(
                                context.getCurrentItem()
                        )
                        .build();

        ToolResult toolResult =
                businessCapabilityExecutor.execute(
                        toolContext,
                        step
                );

        if (toolResult == null) {
            return GraphNodeResult.failure(
                    node,
                    "GRAPH_CAPABILITY_EMPTY_RESULT",
                    "能力执行器返回空结果"
            );
        }

        /*
         * metadata只能保存安全的审计信息。
         */
        Map<String, Object> metadata =
                new LinkedHashMap<>();

        if (toolResult.getInput() != null) {
            metadata.put(
                    "auditInput",
                    toolResult.getInput()
            );
        }

        if (toolResult.getFacts() != null) {
            metadata.put(
                    "facts",
                    toolResult.getFacts()
            );
        }

        if (toolResult.getFields() != null) {
            metadata.put(
                    "fields",
                    toolResult.getFields()
            );
        }

        if (!toolResult.isSuccess()) {
            return GraphNodeResult.failure(
                    node,
                    toolResult.getErrorCode(),
                    toolResult.getErrorMessage(),
                    toolResult.getBusinessCode(),
                    toolResult.getBusinessMessage(),
                    metadata
            );
        }

        /*
         * P0-2与P0-3之间需要保持兼容：
         *
         * 当前BusinessCapabilityExecutorImpl还没有设置
         * workflowData和displayData，因此为空时回退到旧data。
         *
         * P0-3接入CapabilityOutputProjector后，
         * 这里会自动使用真正的双通道数据。
         */
        Object workflowData =
                toolResult.getWorkflowData() != null
                        ? toolResult.getWorkflowData()
                        : toolResult.getData();

        Object displayData =
                toolResult.getDisplayData() != null
                        ? toolResult.getDisplayData()
                        : toolResult.getData();

        return GraphNodeResult.successWithViews(
                node,

                /*
                 * data继续使用旧兼容数据。
                 */
                toolResult.getData(),

                workflowData,
                displayData,
                toolResult.isEmptyData(),
                toolResult.getBusinessCode(),
                toolResult.getBusinessMessage(),
                toolResult.getSummary(),
                metadata
        );
    }
}