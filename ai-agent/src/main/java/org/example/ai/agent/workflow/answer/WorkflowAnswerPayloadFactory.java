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
            Set<String> allowedFieldNames) {

        if (outcome == null) {
            throw new IllegalArgumentException(
                    "工作流执行结果不能为空"
            );
        }

        Object safeResult = sanitizeResult(outcome.result(), allowedFieldNames);

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
            Set<String> allowedFieldNames) {

        if (result == null) {
            return null;
        }

        JsonNode copy =
                objectMapper.valueToTree(result);

        retainAllowedFields(
                copy,
                allowedFieldNames == null
                        ? Set.of()
                        : allowedFieldNames
        );

        return objectMapper.convertValue(
                copy,
                Object.class
        );
    }

    /**
     * 递归删除未授权的叶子字段。
     *
     * 对象和数组只作为结构容器保留，
     * 业务叶子字段必须在允许集合中。
     */
    private void retainAllowedFields(JsonNode node, Set<String> allowedFieldNames) {

        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }

        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            List<String> fieldNames = new ArrayList<>();
            objectNode.fieldNames().forEachRemaining(fieldNames::add);

            for (String fieldName : fieldNames) {
                JsonNode child = objectNode.get(fieldName);
                if (child == null) {
                    continue;
                }
                if (child.isContainerNode()) {
                    retainAllowedFields(child, allowedFieldNames);
                    continue;
                }
                if (!allowedFieldNames.contains(fieldName)) {
                    objectNode.remove(fieldName);
                }
            }
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                retainAllowedFields(child, allowedFieldNames);
            }
        }
    }

}