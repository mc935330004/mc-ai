package org.example.ai.agent.workflow.answer.risk;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.graph.GraphSpecParser;
import org.example.ai.agent.graph.model.GraphSpec;
import org.example.ai.agent.graph.model.risk.WorkflowRiskRuleSpec;
import org.example.ai.agent.workflow.runtime.PublishedWorkflow;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionOutcome;
import org.example.ai.agent.workflow.runtime.WorkflowRuntimeSnapshotResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 从本次实际执行版本读取风险规则。
 *
 * 禁止读取工作流草稿或最新活动版本，
 * 避免运行数据和规则版本不一致。
 */
@Component
@RequiredArgsConstructor
public class WorkflowRiskRuleResolver {

    private final WorkflowRuntimeSnapshotResolver snapshotResolver;
    private final GraphSpecParser graphSpecParser;

    public List<WorkflowRiskRuleSpec> resolve(WorkflowExecutionOutcome outcome) {

        if (outcome == null || outcome.versionId() == null || !StringUtils.hasText(outcome.workflowCode())) {
            return List.of();
        }
        PublishedWorkflow workflow = snapshotResolver.resolveExactVersion(outcome.workflowCode(), outcome.versionId());
        GraphSpec graph = graphSpecParser.parse(workflow.version().getSnapshotJson());
        if (graph.getRiskRules() == null) {
            return List.of();
        }

        return graph.getRiskRules()
                .stream()
                .filter(rule -> rule != null && rule.enabled())
                .toList();
    }
}