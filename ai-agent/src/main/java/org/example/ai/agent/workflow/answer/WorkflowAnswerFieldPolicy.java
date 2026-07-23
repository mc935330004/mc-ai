package org.example.ai.agent.workflow.answer;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 工作流回答字段策略。
 *
 * @param visibleFields    允许发送给模型的字段中文语义
 * @param hiddenFieldNames 禁止发送给模型的机器字段名称
 */
public record WorkflowAnswerFieldPolicy(
        List<WorkflowAnswerFieldContext> visibleFields,
        Set<String> hiddenFieldNames) {

    public WorkflowAnswerFieldPolicy {

        visibleFields = visibleFields == null
                ? List.of()
                : List.copyOf(visibleFields);

        hiddenFieldNames = hiddenFieldNames == null
                ? Set.of()
                : Collections.unmodifiableSet(
                        new LinkedHashSet<>(
                                hiddenFieldNames
                        )
                );
    }

    public static WorkflowAnswerFieldPolicy empty() {
        return new WorkflowAnswerFieldPolicy(
                List.of(),
                Set.of()
        );
    }
}