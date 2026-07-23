package org.example.ai.agent.workflow.answer;

import org.example.ai.agent.graph.compiler.CompiledGraphNode;
import org.example.ai.agent.graph.compiler.CompiledGraphSpec;
import org.example.ai.agent.graph.config.CapabilityNodeConfig;
import org.example.ai.agent.graph.config.CompiledForEachNodeConfig;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 从编译后的工作流中递归收集能力编码。
 *
 * 支持：
 * 1. 主流程能力节点；
 * 2. 第一层项目循环；
 * 3. 第二层业务明细循环；
 * 4. 后续继续增加的嵌套循环。
 */
@Component
public class WorkflowCapabilityCodeCollector {

    public List<String> collect(
            CompiledGraphSpec graph) {

        Set<String> capabilityCodes =
                new LinkedHashSet<>();

        collectGraph(graph, capabilityCodes);

        return List.copyOf(capabilityCodes);
    }

    private void collectGraph( CompiledGraphSpec graph,Set<String> capabilityCodes) {

        if (graph == null || graph.nodesById() == null) {
            return;
        }

        for (CompiledGraphNode node :graph.nodesById().values()) {

            if (node == null) {
                continue;
            }

            /*
             * 普通业务能力节点。
             */
            if (node.config()
                    instanceof CapabilityNodeConfig config
                    && StringUtils.hasText(
                    config.capabilityCode())) {

                capabilityCodes.add(
                        config.capabilityCode().trim()
                );
            }

            /*
             * FOREACH节点内部还可能包含子图，
             * 必须递归读取，不能只扫描主流程。
             */
            if (node.config()
                    instanceof CompiledForEachNodeConfig forEach
                    && forEach.body() != null) {

                collectGraph(
                        forEach.body(),
                        capabilityCodes
                );
            }
        }
    }
}