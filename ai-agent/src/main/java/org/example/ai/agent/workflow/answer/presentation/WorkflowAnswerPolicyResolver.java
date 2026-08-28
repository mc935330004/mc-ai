package org.example.ai.agent.workflow.answer.presentation;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.graph.GraphSpecParser;
import org.example.ai.agent.graph.model.GraphSpec;
import org.example.ai.agent.workflow.runtime.PublishedWorkflow;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionOutcome;
import org.example.ai.agent.workflow.runtime.WorkflowRuntimeSnapshotResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 读取本次工作流实际发布版本的报告配置状态。
 *
 * 这里只判断是否存在报告模板，
 * 不读取工作流默认展示方式。
 */
@Component
@RequiredArgsConstructor
public class WorkflowAnswerPolicyResolver {

    private final WorkflowRuntimeSnapshotResolver snapshotResolver;
    private final GraphSpecParser graphSpecParser;

    /**
     * 判断当前发布版本是否配置了报告模板。
     */
    public WorkflowAnswerPolicy resolve(
            WorkflowExecutionOutcome outcome) {

        if (outcome == null
                || outcome.versionId() == null
                || !StringUtils.hasText(outcome.workflowCode())) {

            return new WorkflowAnswerPolicy(false);
        }

        PublishedWorkflow workflow =
                snapshotResolver.resolveExactVersion(
                        outcome.workflowCode(),
                        outcome.versionId()
                );

        GraphSpec graph = graphSpecParser.parse(
                workflow.version().getSnapshotJson()
        );

        return new WorkflowAnswerPolicy(
                graph.getReportDefinition() != null
        );
    }
}