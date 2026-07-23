package org.example.ai.agent.workflow.answer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.workflow.runtime.WorkflowBatchSummary;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionOutcome;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 创建发送给回答模型的安全载荷。
 */
@Component
@RequiredArgsConstructor
public class WorkflowAnswerPayloadFactory {

    private final ObjectMapper objectMapper;

    public WorkflowAnswerModelPayload create(
            WorkflowExecutionOutcome outcome,
            Set<String> hiddenFieldNames) {

        if (outcome == null) {
            throw new IllegalArgumentException(
                    "工作流执行结果不能为空"
            );
        }

        Object safeResult =
                sanitizeResult(
                        outcome.result(),
                        hiddenFieldNames
                );

        List<WorkflowAnswerModelPayload.Batch> batches =
                outcome.batches()
                        .stream()
                        .map(this::toSafeBatch)
                        .toList();

        return new WorkflowAnswerModelPayload(
                outcome.success(),
                outcome.partialSuccess(),
                safeResult,
                batches
        );
    }

    private WorkflowAnswerModelPayload.Batch toSafeBatch(
            WorkflowBatchSummary batch) {

        return new WorkflowAnswerModelPayload.Batch(
                batch.totalCount(),
                batch.successCount(),
                batch.partialCount(),
                batch.failureCount(),
                batch.skippedCount(),
                batch.partialSuccess(),
                batch.descendants()
        );
    }

    private Object sanitizeResult(
            Object result,
            Set<String> hiddenFieldNames) {

        if (result == null) {
            return null;
        }

        JsonNode copy =
                objectMapper.valueToTree(result);

        removeHiddenFields(
                copy,
                hiddenFieldNames == null
                        ? Set.of()
                        : hiddenFieldNames
        );

        return objectMapper.convertValue(
                copy,
                Object.class
        );
    }

    /**
     * 递归删除禁止发送给模型的字段和值。
     *
     * 使用字段名称精确匹配：
     * 隐藏id不会误删projectId。
     */
    private void removeHiddenFields(
            JsonNode node,
            Set<String> hiddenFieldNames) {

        if (node == null
                || node.isNull()
                || node.isMissingNode()) {
            return;
        }

        if (node.isObject()) {
            ObjectNode objectNode =
                    (ObjectNode) node;

            List<String> fieldNames =
                    new ArrayList<>();

            objectNode.fieldNames()
                    .forEachRemaining(
                            fieldNames::add
                    );

            for (String fieldName :
                    fieldNames) {

                if (hiddenFieldNames.contains(
                        fieldName)) {

                    objectNode.remove(fieldName);
                    continue;
                }

                removeHiddenFields(
                        objectNode.get(fieldName),
                        hiddenFieldNames
                );
            }

            return;
        }

        if (node.isArray()) {
            for (JsonNode child : node) {
                removeHiddenFields(
                        child,
                        hiddenFieldNames
                );
            }
        }
    }
}