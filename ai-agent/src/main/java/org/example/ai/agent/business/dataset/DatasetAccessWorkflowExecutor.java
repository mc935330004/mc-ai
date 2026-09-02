package org.example.ai.agent.business.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.business.dataset.entity.ReportDataset;
import org.example.ai.agent.workflow.runtime.PublishedWorkflow;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionCommand;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionFacade;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionOutcome;
import org.example.ai.agent.workflow.runtime.WorkflowRuntimeSnapshotResolver;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 只执行数据集权限工作流的共享安全边界。
 *
 * 快照复权和首次数据集执行共用这一实现，避免复权时误触发查询工作流。
 */
@Service
public class DatasetAccessWorkflowExecutor {

    private final WorkflowRuntimeSnapshotResolver snapshotResolver;
    private final WorkflowExecutionFacade executionFacade;
    private final CanonicalInputMapper canonicalInputMapper;
    private final ObjectMapper objectMapper;

    public DatasetAccessWorkflowExecutor(
            WorkflowRuntimeSnapshotResolver snapshotResolver,
            WorkflowExecutionFacade executionFacade,
            CanonicalInputMapper canonicalInputMapper,
            ObjectMapper objectMapper) {
        this.snapshotResolver = Objects.requireNonNull(snapshotResolver, "snapshotResolver不能为空");
        this.executionFacade = Objects.requireNonNull(executionFacade, "executionFacade不能为空");
        this.canonicalInputMapper = Objects.requireNonNull(canonicalInputMapper, "canonicalInputMapper不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper不能为空");
    }

    public AccessDecision authorize(AccessRequest request, ReportDataset dataset) {
        if (request == null || dataset == null
                || !StringUtils.hasText(request.agentRunId())
                || !StringUtils.hasText(request.userId())
                || !StringUtils.hasText(request.authorization())
                || !StringUtils.hasText(dataset.getAccessWorkflowCode())) {
            return AccessDecision.FAILED;
        }
        try {
            Map<String, String> mapping = readAccessMapping(dataset.getInputMappingJson());
            Map<String, Object> selected = new LinkedHashMap<>();
            for (String canonicalName : mapping.keySet()) {
                if (request.canonicalInput().containsKey(canonicalName)) {
                    selected.put(canonicalName, request.canonicalInput().get(canonicalName));
                }
            }
            PublishedWorkflow workflow = snapshotResolver.resolveByCode(
                    dataset.getAccessWorkflowCode()
            );
            Map<String, Object> input = canonicalInputMapper.map(
                    selected, mapping, workflow.inputSchema()
            );
            WorkflowExecutionOutcome outcome = executionFacade.execute(
                    WorkflowExecutionCommand.builder()
                            .runId(UUID.randomUUID().toString())
                            .agentRunId(request.agentRunId())
                            .userId(request.userId())
                            .workflowCode(dataset.getAccessWorkflowCode())
                            .expectedVersionId(workflow.version().getId())
                            .input(input)
                            .authorization(request.authorization())
                            .secureContext(request.secureContext())
                            .build()
            );
            return parseDecision(outcome);
        } catch (RuntimeException exception) {
            return AccessDecision.FAILED;
        }
    }

    private Map<String, String> readAccessMapping(String mappingJson) {
        if (!StringUtils.hasText(mappingJson)) {
            throw new IllegalArgumentException("inputMappingJson不能为空");
        }
        try {
            JsonNode section = objectMapper.readTree(mappingJson).get("access");
            if (section == null || !section.isObject()) {
                throw new IllegalArgumentException("inputMappingJson缺少access对象");
            }
            Map<String, String> mapping = new LinkedHashMap<>();
            section.fields().forEachRemaining(entry -> {
                if (!StringUtils.hasText(entry.getKey())
                        || !entry.getValue().isTextual()
                        || !StringUtils.hasText(entry.getValue().textValue())) {
                    throw new IllegalArgumentException("access映射必须是非空字符串键值");
                }
                mapping.put(entry.getKey(), entry.getValue().textValue());
            });
            return Collections.unmodifiableMap(mapping);
        } catch (Exception exception) {
            throw new IllegalArgumentException("inputMappingJson不是合法权限映射", exception);
        }
    }

    private AccessDecision parseDecision(WorkflowExecutionOutcome outcome) {
        if (outcome == null || !outcome.success()) {
            return AccessDecision.FAILED;
        }
        if (outcome.result() instanceof Boolean allowed) {
            return allowed ? AccessDecision.ALLOWED : AccessDecision.DENIED;
        }
        JsonNode root = objectMapper.valueToTree(outcome.result());
        JsonNode allowed = root == null || !root.isObject() ? null : root.get("allowed");
        if (allowed == null || !allowed.isBoolean()) {
            return AccessDecision.FAILED;
        }
        return allowed.booleanValue() ? AccessDecision.ALLOWED : AccessDecision.DENIED;
    }

    public enum AccessDecision {
        ALLOWED,
        DENIED,
        FAILED
    }

    public record AccessRequest(
            String agentRunId,
            String userId,
            String authorization,
            Map<String, Object> secureContext,
            Map<String, Object> canonicalInput) {

        @SuppressWarnings("unchecked")
        public AccessRequest {
            secureContext = (Map<String, Object>) ReportDatasetValidator.freezeSafeValue(
                    secureContext == null ? Map.of() : secureContext
            );
            canonicalInput = (Map<String, Object>) ReportDatasetValidator.freezeSafeValue(
                    canonicalInput == null ? Map.of() : canonicalInput
            );
        }

        /** 认证和安全上下文只允许驻留本次调用内存，不进入日志。 */
        @Override
        public String toString() {
            return "AccessRequest[agentRunId=" + agentRunId
                    + ", userId=" + userId
                    + ", authorizationPresent=" + StringUtils.hasText(authorization)
                    + ", secureContextSize=" + secureContext.size()
                    + ", canonicalInputSize=" + canonicalInput.size() + ']';
        }
    }
}
