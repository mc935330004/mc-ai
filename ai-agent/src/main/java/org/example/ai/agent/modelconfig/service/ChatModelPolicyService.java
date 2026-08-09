package org.example.ai.agent.modelconfig.service;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.chat.vo.ChatModelVO;
import org.example.ai.agent.common.config.AgentModelProperties;
import org.example.ai.agent.common.enums.ModelAssignmentSubjectType;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.exception.ErrorCode;
import org.example.ai.agent.modelconfig.entity.ModelAssignment;
import org.example.ai.agent.modelconfig.model.ModelOption;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 当前用户聊天模型解析服务。
 *
 * 只负责授权、默认模型和会话选模，
 * 不执行自动故障转移。
 */
@Service
@RequiredArgsConstructor
public class ChatModelPolicyService {

    private static final String SYSTEM_SUBJECT_ID = "SYSTEM";

    private final ModelAssignmentService assignmentService;
    private final ModelConfigService modelConfigService;
    private final AgentModelProperties yamlModelProperties;

    /**
     * 查询当前用户实际允许选择的模型。
     */
    public List<ChatModelVO> listSelectableModels(
            String userId) {

        List<ModelAssignment> assignments =
                findEffectiveAssignments(userId);

        if (assignments.isEmpty()) {
            return listYamlModels();
        }

        Map<String, ModelOption> enabledModels =
                enabledModelMap();

        List<ModelAssignment> availableAssignments =
                filterAvailableAssignments(
                        assignments,
                        enabledModels
                );

        requireAvailableModels(
                assignments,
                availableAssignments
        );

        String defaultModelCode =
                resolveConfiguredDefault(
                        availableAssignments
                );

        return availableAssignments.stream()
                .map(assignment -> {
                    ModelOption option = enabledModels.get(
                            assignment.getModelCode()
                    );

                    return ChatModelVO.builder()
                            .code(option.modelCode())
                            .name(option.displayName())
                            .provider(
                                    option.providerCode()
                            )
                            .defaultModel(
                                    option.modelCode().equals(
                                            defaultModelCode
                                    )
                            )
                            .build();
                })
                .toList();
    }

    /**
     * 解析当前会话最终使用的模型编码。
     *
     * explicitModelCode表示用户本次明确选择；
     * sessionModelCode表示历史会话保存值。
     */
    public String resolveModelCode(
            String userId,
            String explicitModelCode,
            String sessionModelCode) {

        List<ModelAssignment> assignments =
                findEffectiveAssignments(userId);

        if (assignments.isEmpty()) {
            return resolveYamlModel(
                    explicitModelCode,
                    sessionModelCode
            );
        }

        Map<String, ModelOption> enabledModels =
                enabledModelMap();

        List<ModelAssignment> availableAssignments =
                filterAvailableAssignments(
                        assignments,
                        enabledModels
                );

        requireAvailableModels(
                assignments,
                availableAssignments
        );

        if (StringUtils.hasText(explicitModelCode)) {
            if (containsModel(
                    availableAssignments,
                    explicitModelCode)) {
                return explicitModelCode;
            }

            throw new BusinessException(
                    ErrorCode.FORBIDDEN,
                    "当前用户无权使用模型：" + explicitModelCode
            );
        }

        /*
         * 历史会话模型被停用或取消授权时，
         * 回落到当前有效默认模型，而不是让整个会话失效。
         */
        if (StringUtils.hasText(sessionModelCode)
                && containsModel(
                availableAssignments,
                sessionModelCode)) {
            return sessionModelCode;
        }

        return resolveConfiguredDefault(
                availableAssignments
        );
    }

    private List<ModelAssignment> findEffectiveAssignments(
            String userId) {

        List<ModelAssignment> userAssignments =
                assignmentService.listRows(
                        ModelAssignmentSubjectType.USER.name(),
                        userId
                );

        if (!userAssignments.isEmpty()) {
            return userAssignments;
        }

        return assignmentService.listRows(
                ModelAssignmentSubjectType.SYSTEM.name(),
                SYSTEM_SUBJECT_ID
        );
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

    private List<ModelAssignment> filterAvailableAssignments(
            List<ModelAssignment> assignments,
            Map<String, ModelOption> enabledModels) {

        return assignments.stream()
                .filter(assignment ->
                        enabledModels.containsKey(
                                assignment.getModelCode()
                        ))
                .toList();
    }

    private void requireAvailableModels(
            List<ModelAssignment> configuredAssignments,
            List<ModelAssignment> availableAssignments) {

        if (!configuredAssignments.isEmpty()
                && availableAssignments.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.AI_SERVICE_UNAVAILABLE,
                    "当前配置的模型均已停用，请联系管理员"
            );
        }
    }

    private String resolveConfiguredDefault(
            List<ModelAssignment> assignments) {

        return assignments.stream()
                .filter(assignment ->
                        assignment.getDefaultModel() != null
                                && assignment.getDefaultModel() == 1)
                .map(ModelAssignment::getModelCode)
                .findFirst()
                /*
                 * 默认模型被临时停用时，
                 * 使用当前优先级最高的可用模型。
                 */
                .orElseGet(() ->
                        assignments.get(0).getModelCode()
                );
    }

    private boolean containsModel(
            List<ModelAssignment> assignments,
            String modelCode) {

        return assignments.stream()
                .anyMatch(assignment ->
                        assignment.getModelCode().equals(
                                modelCode
                        ));
    }

    private List<ChatModelVO> listYamlModels() {
        return yamlModelProperties.getModels()
                .stream()
                .filter(
                        AgentModelProperties.ModelItem::isEnabled
                )
                .map(item -> ChatModelVO.builder()
                        .code(item.getCode())
                        .name(item.getName())
                        .provider(item.getProvider())
                        .defaultModel(
                                item.getCode().equals(
                                        yamlModelProperties
                                                .getDefaultCode()
                                )
                        )
                        .build())
                .toList();
    }

    private String resolveYamlModel(
            String explicitModelCode,
            String sessionModelCode) {

        if (StringUtils.hasText(explicitModelCode)) {
            return yamlModelProperties
                    .resolve(explicitModelCode)
                    .getCode();
        }

        if (StringUtils.hasText(sessionModelCode)) {
            try {
                return yamlModelProperties
                        .resolve(sessionModelCode)
                        .getCode();
            } catch (IllegalArgumentException exception) {
                /*
                 * YAML模型被移除时，历史会话回落默认模型。
                 */
            }
        }

        return yamlModelProperties
                .defaultModel()
                .getCode();
    }
}