package org.example.ai.agent.modelconfig.service;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.chat.vo.ChatModelVO;
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
 * 不负责创建模型客户端或执行故障转移。
 */
@Service
@RequiredArgsConstructor
public class ChatModelPolicyService {

    private static final String SYSTEM_SUBJECT_ID = "SYSTEM";

    private final ModelAssignmentService assignmentService;
    private final ModelConfigService modelConfigService;

    /**
     * 查询当前用户实际允许选择的数据库模型。
     */
    public List<ChatModelVO> listSelectableModels(String userId) {
        List<ModelOption> enabledModels = requireEnabledModels();
        List<ModelAssignment> assignments =
                findEffectiveAssignments(userId);

        /*
         * 没有人员或系统授权配置时，
         * 默认允许使用数据库中全部已启用模型。
         */
        if (assignments.isEmpty()) {
            String defaultModelCode =
                    resolveDatabaseDefault(enabledModels);

            return enabledModels.stream()
                    .map(option -> toChatModelVO(
                            option,
                            defaultModelCode
                    ))
                    .toList();
        }

        Map<String, ModelOption> enabledModelMap =
                toEnabledModelMap(enabledModels);

        List<ModelAssignment> availableAssignments =
                filterAvailableAssignments(
                        assignments,
                        enabledModelMap
                );

        requireAvailableAssignments(availableAssignments);

        String defaultModelCode =
                resolveConfiguredDefault(
                        availableAssignments
                );

        return availableAssignments.stream()
                .map(assignment -> toChatModelVO(
                        enabledModelMap.get(
                                assignment.getModelCode()
                        ),
                        defaultModelCode
                ))
                .toList();
    }

    /**
     * 解析当前会话最终使用的模型编码。
     *
     * explicitModelCode表示用户本次明确选择；
     * sessionModelCode表示历史会话保存的模型。
     */
    public String resolveModelCode(
            String userId,
            String explicitModelCode,
            String sessionModelCode) {

        List<ModelOption> enabledModels =
                requireEnabledModels();

        List<ModelAssignment> assignments =
                findEffectiveAssignments(userId);

        /*
         * 没有授权配置时使用数据库启用模型，
         * 不再回退application.yml中的聊天模型。
         */
        if (assignments.isEmpty()) {
            return resolveUnassignedModel(
                    enabledModels,
                    explicitModelCode,
                    sessionModelCode
            );
        }

        Map<String, ModelOption> enabledModelMap =
                toEnabledModelMap(enabledModels);

        List<ModelAssignment> availableAssignments =
                filterAvailableAssignments(
                        assignments,
                        enabledModelMap
                );

        requireAvailableAssignments(availableAssignments);

        if (StringUtils.hasText(explicitModelCode)) {
            if (containsAssignment(
                    availableAssignments,
                    explicitModelCode)) {
                return explicitModelCode;
            }

            throw new BusinessException(
                    ErrorCode.FORBIDDEN,
                    "当前用户无权使用模型："
                            + explicitModelCode
            );
        }

        /*
         * 历史会话模型被停用或取消授权时，
         * 回落到当前授权范围内的默认模型。
         */
        if (StringUtils.hasText(sessionModelCode)
                && containsAssignment(
                availableAssignments,
                sessionModelCode)) {
            return sessionModelCode;
        }

        return resolveConfiguredDefault(
                availableAssignments
        );
    }

    /**
     * 优先使用人员授权，没有人员授权时继承系统授权。
     */
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

    /**
     * 查询数据库启用模型。
     *
     * 数据库未配置模型时不阻止应用启动，
     * 只在实际查询或使用聊天模型时返回业务错误。
     */
    private List<ModelOption> requireEnabledModels() {
        List<ModelOption> enabledModels =
                modelConfigService.listEnabledOptions();

        if (enabledModels.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.AI_SERVICE_UNAVAILABLE,
                    "当前没有已启用的聊天模型，请联系管理员配置"
            );
        }

        return enabledModels;
    }

    private Map<String, ModelOption> toEnabledModelMap(
            List<ModelOption> enabledModels) {

        Map<String, ModelOption> result = new HashMap<>();

        for (ModelOption option : enabledModels) {
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

    private void requireAvailableAssignments(
            List<ModelAssignment> availableAssignments) {

        if (availableAssignments.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.AI_SERVICE_UNAVAILABLE,
                    "当前授权的模型均不存在或已经停用，请联系管理员"
            );
        }
    }

    /**
     * 解析没有人员和系统授权时使用的数据库模型。
     */
    private String resolveUnassignedModel(
            List<ModelOption> enabledModels,
            String explicitModelCode,
            String sessionModelCode) {

        if (StringUtils.hasText(explicitModelCode)) {
            if (containsModel(
                    enabledModels,
                    explicitModelCode)) {
                return explicitModelCode;
            }

            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "模型不存在或已经停用："
                            + explicitModelCode
            );
        }

        /*
         * 历史会话模型被停用时，
         * 回落到数据库当前默认模型。
         */
        if (StringUtils.hasText(sessionModelCode)
                && containsModel(
                enabledModels,
                sessionModelCode)) {
            return sessionModelCode;
        }

        return resolveDatabaseDefault(enabledModels);
    }

    /**
     * 数据库默认模型不存在时，
     * 使用排序后的第一个启用模型。
     */
    private String resolveDatabaseDefault(
            List<ModelOption> enabledModels) {

        return enabledModels.stream()
                .filter(ModelOption::defaultModel)
                .map(ModelOption::modelCode)
                .findFirst()
                .orElse(enabledModels.get(0).modelCode());
    }

    private String resolveConfiguredDefault(
            List<ModelAssignment> assignments) {

        return assignments.stream()
                .filter(assignment ->
                        assignment.getDefaultModel() != null
                                && assignment.getDefaultModel() == 1)
                .map(ModelAssignment::getModelCode)
                .findFirst()
                .orElse(assignments.get(0).getModelCode());
    }

    private boolean containsAssignment(
            List<ModelAssignment> assignments,
            String modelCode) {

        return assignments.stream()
                .anyMatch(assignment ->
                        assignment.getModelCode().equals(
                                modelCode
                        ));
    }

    private boolean containsModel(
            List<ModelOption> enabledModels,
            String modelCode) {

        return enabledModels.stream()
                .anyMatch(option ->
                        option.modelCode().equals(modelCode));
    }

    private ChatModelVO toChatModelVO(
            ModelOption option,
            String defaultModelCode) {

        return ChatModelVO.builder()
                .code(option.modelCode())
                .name(option.displayName())
                .provider(option.providerCode())
                .defaultModel(
                        option.modelCode().equals(
                                defaultModelCode
                        )
                )
                .build();
    }
}