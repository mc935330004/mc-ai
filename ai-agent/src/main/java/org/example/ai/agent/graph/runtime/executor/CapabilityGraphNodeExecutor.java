package org.example.ai.agent.graph.runtime.executor;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.enums.GraphNodeType;
import org.example.ai.agent.graph.compiler.CompiledGraphNode;
import org.example.ai.agent.graph.config.CapabilityNodeConfig;
import org.example.ai.agent.graph.runtime.*;
import org.example.ai.agent.plan.PlanStep;
import org.example.ai.agent.plan.StepType;
import org.example.ai.agent.tool.BusinessCapabilityExecutor;
import org.example.ai.agent.tool.ToolExecutionContext;
import org.example.ai.agent.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class CapabilityGraphNodeExecutor implements GraphNodeExecutor {

    private final BusinessCapabilityExecutor businessCapabilityExecutor;

    private final GraphRuntimeExpressionResolver expressionResolver;

    @Override
    public GraphNodeType type() {
        return GraphNodeType.CAPABILITY;
    }

    @Override
    public GraphNodeResult execute(
            CompiledGraphNode node,
            GraphExecutionContext context) {

        if (!(node.config()
                instanceof CapabilityNodeConfig config)) {

            return GraphNodeResult.failure(
                    node,
                    "GRAPH_CAPABILITY_CONFIG_INVALID",
                    "能力节点配置类型不正确"
            );
        }

        Map<String, Object> resolvedInput =
                expressionResolver.resolveMap(
                        config.inputMapping(),
                        context
                );

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
                        .build();

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

        return GraphNodeResult.success(
                node,
                toolResult.getData(),
                toolResult.isEmptyData(),
                toolResult.getBusinessCode(),
                toolResult.getBusinessMessage(),
                toolResult.getSummary(),
                metadata
        );
    }
}