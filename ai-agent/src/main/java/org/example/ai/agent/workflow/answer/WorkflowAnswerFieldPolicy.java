package org.example.ai.agent.workflow.answer;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 工作流字段权限策略。
 *
 * internalFields：
 * 所有已发布并且在结果中出现的字段。
 *
 * modelFields：
 * 允许发送给大模型的字段。
 *
 * userFields：
 * 允许展示给用户的字段。
 */
public record WorkflowAnswerFieldPolicy(
        List<WorkflowAnswerFieldContext> internalFields) {

    public WorkflowAnswerFieldPolicy {
        internalFields = internalFields == null
                ? List.of()
                : List.copyOf(internalFields);
    }

    /**
     * 对外分析只使用用户可见、模型可见且未隐藏的字段。
     */
    public List<WorkflowAnswerFieldContext> modelFields() {
        return internalFields.stream()
                .filter(field -> field.modelVisible()
                        && field.userVisible()
                        && !"HIDDEN".equalsIgnoreCase(field.displayComponent()))
                .toList();
    }

    /**
     * 获取允许向用户展示的字段。
     */
    public List<WorkflowAnswerFieldContext> userFields() {
        return internalFields.stream()
                .filter(field -> field.userVisible()
                        && !"HIDDEN".equalsIgnoreCase(field.displayComponent()))
                .toList();
    }

    /**
     * 获取工作流内部字段机器名称。
     */
    public Set<String> internalFieldNames() {
        return fieldNames(internalFields);
    }

    /**
     * 获取允许发送给模型的机器字段名称。
     */
    public Set<String> modelFieldNames() {
        return fieldNames(modelFields());
    }

    /**
     * 获取允许展示给用户的机器字段名称。
     */
    public Set<String> userFieldNames() {
        return fieldNames(userFields());
    }

    private Set<String> fieldNames(
            List<WorkflowAnswerFieldContext> fields) {

        Set<String> names =
                new LinkedHashSet<>();

        for (WorkflowAnswerFieldContext field : fields) {

            if (field == null
                    || field.fieldName() == null
                    || field.fieldName().isBlank()) {

                continue;
            }

            names.add(
                    field.fieldName().trim()
            );
        }

        return Set.copyOf(names);
    }

    public static WorkflowAnswerFieldPolicy empty() {
        return new WorkflowAnswerFieldPolicy(
                List.of()
        );
    }
}