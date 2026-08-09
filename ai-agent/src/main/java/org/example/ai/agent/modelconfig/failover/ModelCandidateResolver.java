package org.example.ai.agent.modelconfig.failover;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.enums.ModelAssignmentSubjectType;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.exception.ErrorCode;
import org.example.ai.agent.common.modelusage.ModelCallContext;
import org.example.ai.agent.modelconfig.client.ModelClientRegistry;
import org.example.ai.agent.modelconfig.entity.ModelAssignment;
import org.example.ai.agent.modelconfig.model.ModelOption;
import org.example.ai.agent.modelconfig.service.ModelAssignmentService;
import org.example.ai.agent.modelconfig.service.ModelConfigService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单次模型调用的有序候选链解析器。
 *
 * 顺序为当前模型、人员备用模型、系统备用模型，
 * 自动去重并最多保留三个候选。
 */
@Service
@RequiredArgsConstructor
public class ModelCandidateResolver {

    private static final String SYSTEM_SUBJECT_ID = "SYSTEM";
    private static final int MAX_CANDIDATES = 3;

    private final ModelAssignmentService assignmentService;
    private final ModelConfigService modelConfigService;
    private final ModelClientRegistry modelClientRegistry;

    public List<String> resolveCandidates(
            ModelCallContext context,
            boolean streamingRequired) {

        boolean userVisibleCall =
                context != null
                        && context.getCallType() != null
                        && context.getCallType()
                        .usesUserSelectedModel();

        List<ModelAssignment> userAssignments =
                loadUserAssignments(
                        context,
                        userVisibleCall
                );

        List<ModelAssignment> systemAssignments =
                loadSystemAssignments();

        /*
         * 没有数据库授权配置时继续沿用Phase 3的YAML回退。
         */
        if (userAssignments.isEmpty()
                && systemAssignments.isEmpty()) {
            return List.of(
                    modelClientRegistry
                            .resolve(context)
                            .modelCode()
            );
        }

        Set<String> orderedCodes =
                new LinkedHashSet<>();

        if (userVisibleCall
                && context != null
                && StringUtils.hasText(
                context.getModelCode())) {
            orderedCodes.add(
                    context.getModelCode().trim()
            );
        }

        if (!userAssignments.isEmpty()) {
            addAssignments(
                    orderedCodes,
                    userAssignments
            );
            addAssignments(
                    orderedCodes,
                    systemAssignments
            );
        } else {
            addAssignments(
                    orderedCodes,
                    systemAssignments
            );
        }

        Map<String, ModelOption> enabledModels =
                enabledModelMap();

        List<String> candidates = orderedCodes.stream()
                .filter(enabledModels::containsKey)
                .filter(modelCode -> supportsCall(
                        enabledModels.get(modelCode),
                        context,
                        streamingRequired
                ))
                .limit(MAX_CANDIDATES)
                .toList();

        if (candidates.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.AI_SERVICE_UNAVAILABLE,
                    "没有满足当前调用能力要求的可用模型"
            );
        }

        return candidates;
    }

    private List<ModelAssignment> loadUserAssignments(
            ModelCallContext context,
            boolean userVisibleCall) {

        if (!userVisibleCall
                || context == null
                || !StringUtils.hasText(
                context.getUserId())) {
            return List.of();
        }

        return assignmentService.listRows(
                ModelAssignmentSubjectType.USER.name(),
                context.getUserId()
        );
    }

    private List<ModelAssignment> loadSystemAssignments() {
        return assignmentService.listRows(
                ModelAssignmentSubjectType.SYSTEM.name(),
                SYSTEM_SUBJECT_ID
        );
    }

    private void addAssignments(
            Set<String> target,
            List<ModelAssignment> assignments) {

        orderedAssignments(assignments)
                .stream()
                .map(ModelAssignment::getModelCode)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .forEach(target::add);
    }

    private List<ModelAssignment> orderedAssignments(
            List<ModelAssignment> assignments) {

        return assignments.stream()
                .sorted(
                        Comparator
                                .comparingInt(
                                        this::defaultOrder
                                )
                                .thenComparingInt(
                                        this::priorityOrder
                                )
                )
                .toList();
    }

    private int defaultOrder(
            ModelAssignment assignment) {

        return assignment.getDefaultModel() != null
                && assignment.getDefaultModel() == 1
                ? 0
                : 1;
    }

    private int priorityOrder(
            ModelAssignment assignment) {

        return assignment.getFallbackPriority() == null
                ? Integer.MAX_VALUE
                : assignment.getFallbackPriority();
    }

    private Map<String, ModelOption> enabledModelMap() {
        Map<String, ModelOption> result =
                new HashMap<>();

        for (ModelOption option
                : modelConfigService.listEnabledOptions()) {
            result.put(option.modelCode(), option);
        }

        return result;
    }

    private boolean supportsCall(
            ModelOption option,
            ModelCallContext context,
            boolean streamingRequired) {

        if (streamingRequired
                && !option.streamingSupported()) {
            return false;
        }

        return context == null
                || context.getCallType() == null
                || !context.getCallType()
                .requiresStructuredOutput()
                || option.structuredOutputSupported();
    }
}