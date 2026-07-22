package org.example.ai.agent.graph.runtime;

import org.example.ai.agent.common.enums.GraphNodeStatus;
import org.example.ai.agent.common.enums.GraphNodeType;
import org.example.ai.agent.graph.compiler.CompiledGraphNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GraphSpec节点统一执行结果。
 *
 * 安全要求：
 * 1. 不允许保存Authorization；
 * 2. 不允许保存Cookie；
 * 3. 不允许保存Token；
 * 4. 不允许保存未经字段字典投影的完整业务响应。
 */
public record GraphNodeResult(
        String nodeId,
        GraphNodeType nodeType,
        GraphNodeStatus status,

        /*
         * 兼容旧工作流的数据入口。
         */
        Object data,

        /*
         * 使用稳定英文字段的工作流机器数据。
         */
        Object workflowData,

        /*
         * 使用中文名称的前端和大模型展示数据。
         */
        Object displayData,

        boolean emptyData,
        String businessCode,
        String businessMessage,
        String errorCode,
        String errorMessage,
        String summary,
        Map<String, Object> metadata,
        long durationMs) {

    /**
     * 统一处理metadata不可变性。
     */
    public GraphNodeResult {
        metadata = metadata == null
                ? Map.of()
                : Collections.unmodifiableMap(
                new LinkedHashMap<>(metadata)
        );
    }

    /**
     * 判断节点是否执行成功。
     */
    public boolean isSuccess() {
        return status == GraphNodeStatus.SUCCESS;
    }

    /**
     * 创建普通节点成功结果。
     *
     * START、MERGE、FOREACH等旧节点仍然调用该方法。
     * 为保持兼容，三个数据入口暂时使用同一份数据。
     */
    public static GraphNodeResult success(
            CompiledGraphNode node,
            Object data,
            boolean emptyData) {

        return success(
                node,
                data,
                emptyData,
                null,
                null,
                node.name() + "执行成功",
                Map.of()
        );
    }

    /**
     * 创建带业务状态的普通节点成功结果。
     *
     * 普通节点没有独立机器视图和展示视图时，
     * 三个字段使用同一份数据。
     */
    public static GraphNodeResult success(
            CompiledGraphNode node,
            Object data,
            boolean emptyData,
            String businessCode,
            String businessMessage,
            String summary,
            Map<String, Object> metadata) {

        return new GraphNodeResult(
                node.id(),
                node.type(),
                GraphNodeStatus.SUCCESS,
                data,
                data,
                data,
                emptyData,
                businessCode,
                businessMessage,
                null,
                null,
                summary,
                metadata,
                0
        );
    }

    /**
     * 创建包含机器视图和展示视图的成功结果。
     *
     * @param node            当前编译节点
     * @param data            兼容旧工作流的数据
     * @param workflowData    稳定机器字段数据
     * @param displayData     中文展示数据
     * @param emptyData       是否为空数据
     * @param businessCode    业务状态码
     * @param businessMessage 业务消息
     * @param summary         执行摘要
     * @param metadata        安全元数据
     */
    public static GraphNodeResult successWithViews(
            CompiledGraphNode node,
            Object data,
            Object workflowData,
            Object displayData,
            boolean emptyData,
            String businessCode,
            String businessMessage,
            String summary,
            Map<String, Object> metadata) {

        return new GraphNodeResult(
                node.id(),
                node.type(),
                GraphNodeStatus.SUCCESS,
                data,
                workflowData,
                displayData,
                emptyData,
                businessCode,
                businessMessage,
                null,
                null,
                summary,
                metadata,
                0
        );
    }

    /**
     * 创建节点失败结果。
     */
    public static GraphNodeResult failure(
            CompiledGraphNode node,
            String errorCode,
            String errorMessage) {

        return failure(
                node,
                errorCode,
                errorMessage,
                null,
                null,
                Map.of()
        );
    }

    /**
     * 创建包含业务状态的节点失败结果。
     */
    public static GraphNodeResult failure(
            CompiledGraphNode node,
            String errorCode,
            String errorMessage,
            String businessCode,
            String businessMessage,
            Map<String, Object> metadata) {

        return new GraphNodeResult(
                node.id(),
                node.type(),
                GraphNodeStatus.FAILED,
                null,
                null,
                null,
                true,
                businessCode,
                businessMessage,
                errorCode,
                errorMessage,
                errorMessage,
                metadata,
                0
        );
    }

    /**
     * 创建上游失败导致的跳过结果。
     */
    public static GraphNodeResult skipped(
            CompiledGraphNode node,
            String message) {

        return new GraphNodeResult(
                node.id(),
                node.type(),
                GraphNodeStatus.SKIPPED,
                null,
                null,
                null,
                true,
                null,
                null,
                "GRAPH_NODE_UPSTREAM_FAILED",
                message,
                message,
                Map.of(),
                0
        );
    }

    /**
     * 返回携带执行耗时的新结果。
     *
     * record不可变，因此不能直接修改durationMs。
     */
    public GraphNodeResult withDuration(
            long durationMs) {

        return new GraphNodeResult(
                nodeId,
                nodeType,
                status,
                data,
                workflowData,
                displayData,
                emptyData,
                businessCode,
                businessMessage,
                errorCode,
                errorMessage,
                summary,
                metadata,
                durationMs
        );
    }

    /**
     * 写入$vars的标准不可变信封。
     *
     * 示例：
     * $vars.settlement_list.data
     * $vars.settlement_list.workflowData
     * $vars.settlement_list.displayData
     */
    public Map<String, Object> toVariableEnvelope() {
        Map<String, Object> envelope =
                new LinkedHashMap<>();

        envelope.put(
                "success",
                isSuccess()
        );

        envelope.put(
                "status",
                status.name()
        );

        /*
         * 兼容旧工作流。
         */
        envelope.put(
                "data",
                data
        );

        /*
         * 新工作流下游计算统一读取这里。
         */
        envelope.put(
                "workflowData",
                workflowData
        );

        /*
         * 前端和大模型展示统一读取这里。
         */
        envelope.put(
                "displayData",
                displayData
        );

        envelope.put(
                "emptyData",
                emptyData
        );

        envelope.put(
                "businessCode",
                businessCode
        );

        envelope.put(
                "businessMessage",
                businessMessage
        );

        envelope.put(
                "errorCode",
                errorCode
        );

        envelope.put(
                "errorMessage",
                errorMessage
        );

        envelope.put(
                "summary",
                summary
        );

        return Collections.unmodifiableMap(
                envelope
        );
    }
}