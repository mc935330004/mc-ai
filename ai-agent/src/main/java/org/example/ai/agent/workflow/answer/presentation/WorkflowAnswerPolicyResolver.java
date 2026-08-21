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
 * 从本次实际执行的发布版本中读取回答策略。
 *
 * 禁止读取 WorkflowDefinition.graphSpecJson 草稿，
 * 避免运行配置与发布版本不一致。
 */
@Component
@RequiredArgsConstructor
public class WorkflowAnswerPolicyResolver {

    private final WorkflowRuntimeSnapshotResolver snapshotResolver;
    private final GraphSpecParser graphSpecParser;

    /**
     * 解析本次工作流实际执行版本的展示配置。
     */
    public WorkflowAnswerPolicy resolve(WorkflowExecutionOutcome outcome) {

        if (outcome == null || outcome.versionId() == null || !StringUtils.hasText(outcome.workflowCode())) {

            /*
             * 无法确认发布版本时保持旧报表行为，
             * 不允许误切换到新的文字回答链路。
             */
            return new WorkflowAnswerPolicy(null);
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
                graph.getPresentationMode()
        );
    }
}