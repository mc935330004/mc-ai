package org.example.ai.agent.modelconfig.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.enums.ModelAssignmentSubjectType;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.exception.ErrorCode;
import org.example.ai.agent.modelconfig.audit.ModelConfigAuditRecorder;
import org.example.ai.agent.modelconfig.dto.ModelAssignmentItemDTO;
import org.example.ai.agent.modelconfig.dto.ModelAssignmentSaveDTO;
import org.example.ai.agent.modelconfig.entity.ModelAssignment;
import org.example.ai.agent.modelconfig.mapper.ModelAssignmentMapper;
import org.example.ai.agent.modelconfig.model.ModelOption;
import org.example.ai.agent.modelconfig.service.ModelAssignmentService;
import org.example.ai.agent.modelconfig.service.ModelConfigService;
import org.example.ai.agent.modelconfig.vo.ModelAssignmentItemVO;
import org.example.ai.agent.modelconfig.vo.ModelAssignmentVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 系统和人员模型授权服务实现。
 */
@Service
@RequiredArgsConstructor
public class ModelAssignmentServiceImpl
        implements ModelAssignmentService {

    private static final String SYSTEM_SUBJECT_ID = "SYSTEM";

    private final ModelAssignmentMapper assignmentMapper;
    private final ModelConfigService modelConfigService;
    private final ModelConfigAuditRecorder auditRecorder;
    @Override
    public ModelAssignmentVO getSystemAssignment() {
        return buildVO(
                ModelAssignmentSubjectType.SYSTEM.name(),
                SYSTEM_SUBJECT_ID
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ModelAssignmentVO saveSystemAssignment(ModelAssignmentSaveDTO dto,String operator) {
        replaceAssignments(
                ModelAssignmentSubjectType.SYSTEM.name(),
                SYSTEM_SUBJECT_ID,
                dto,
                operator
        );
        // 授权保存成功后，在同一事务中记录审计日志。
        auditRecorder.record(
                operator,
                ModelConfigAuditRecorder.SYSTEM_ASSIGNMENT_SAVE,
                ModelConfigAuditRecorder.SYSTEM_ASSIGNMENT,
                SYSTEM_SUBJECT_ID,
                "保存系统模型授权"
        );
        return getSystemAssignment();
    }

    @Override
    public ModelAssignmentVO getUserAssignment( String userId) {
        validateUserId(userId);
        return buildVO(ModelAssignmentSubjectType.USER.name(),userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ModelAssignmentVO saveUserAssignment(
            String userId,
            ModelAssignmentSaveDTO dto,
            String operator) {
        validateUserId(userId);
        replaceAssignments(
                ModelAssignmentSubjectType.USER.name(),
                userId,
                dto,
                operator
        );
        // 只记录服务端生成的固定摘要，不保存请求DTO和模型密钥。
        auditRecorder.record(
                operator,
                ModelConfigAuditRecorder.USER_ASSIGNMENT_SAVE,
                ModelConfigAuditRecorder.USER_ASSIGNMENT,
                userId,
                "保存人员模型授权"
        );
        return getUserAssignment(userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteUserAssignment(
            String userId,
            String operator) {

        validateUserId(userId);

        int deletedRows = assignmentMapper.delete(
                new LambdaQueryWrapper<ModelAssignment>()
                        .eq(ModelAssignment::getSubjectType, ModelAssignmentSubjectType.USER.name())
                        .eq(ModelAssignment::getSubjectId,userId)
        );

        // 没有删除任何授权时不记录审计，避免产生虚假的状态变更记录。
        if (deletedRows > 0) {
            auditRecorder.record(
                    operator,
                    ModelConfigAuditRecorder.USER_ASSIGNMENT_DELETE,
                    ModelConfigAuditRecorder.USER_ASSIGNMENT,
                    userId,
                    "删除人员模型授权"
            );
        }
    }

    @Override
    public List<ModelAssignment> listRows(String subjectType,String subjectId) {
        ModelAssignmentSubjectType.from(subjectType);
        if (!StringUtils.hasText(subjectId)) {
            return List.of();
        }
        return assignmentMapper.selectList(
                new LambdaQueryWrapper<ModelAssignment>()
                        .eq(ModelAssignment::getSubjectType,subjectType)
                        .eq(ModelAssignment::getSubjectId,subjectId)
                        .orderByAsc(ModelAssignment::getFallbackPriority)
                        .orderByAsc(ModelAssignment::getId)
        );
    }

    private void replaceAssignments(String subjectType,String subjectId,ModelAssignmentSaveDTO dto,String operator) {
        validateAssignment(dto);
        assignmentMapper.delete(
                new LambdaQueryWrapper<ModelAssignment>()
                        .eq(ModelAssignment::getSubjectType,subjectType)
                        .eq(ModelAssignment::getSubjectId,subjectId)
        );

        for (ModelAssignmentItemDTO item : dto.getModels()) {
            ModelAssignment assignment =new ModelAssignment();

            assignment.setSubjectType(subjectType);
            assignment.setSubjectId(subjectId);
            assignment.setModelCode(item.getModelCode().trim());
            assignment.setDefaultModel(
                    Boolean.TRUE.equals(item.getDefaultModel())
                            ? 1 : 0
            );
            assignment.setFallbackPriority(
                    item.getFallbackPriority()
            );
            assignment.setCreatedBy(operator);
            assignment.setUpdatedBy(operator);
            assignment.setCreatedAt(LocalDateTime.now());
            assignmentMapper.insert(assignment);
        }
    }

    private void validateAssignment(
            ModelAssignmentSaveDTO dto) {

        if (dto == null
                || dto.getModels() == null
                || dto.getModels().isEmpty()) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "至少需要配置一个模型"
            );
        }

        Map<String, ModelOption> enabledModels =
                new HashMap<>();

        for (ModelOption option
                : modelConfigService.listEnabledOptions()) {
            enabledModels.put(
                    option.modelCode(),
                    option
            );
        }

        Set<String> modelCodes = new HashSet<>();
        Set<Integer> priorities = new HashSet<>();
        int defaultCount = 0;

        for (ModelAssignmentItemDTO item
                : dto.getModels()) {

            validateAssignmentItem(
                    item,
                    enabledModels,
                    modelCodes,
                    priorities
            );

            if (Boolean.TRUE.equals(
                    item.getDefaultModel())) {
                defaultCount++;
            }
        }

        if (defaultCount != 1) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "模型配置必须且只能包含一个默认模型"
            );
        }
    }

    private void validateAssignmentItem(
            ModelAssignmentItemDTO item,
            Map<String, ModelOption> enabledModels,
            Set<String> modelCodes,
            Set<Integer> priorities) {

        if (item == null
                || !StringUtils.hasText(
                item.getModelCode())) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "模型编码不能为空"
            );
        }

        String modelCode = item.getModelCode().trim();

        if (!enabledModels.containsKey(modelCode)) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "模型不存在或未启用：" + modelCode
            );
        }

        if (!modelCodes.add(modelCode)) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "模型不能重复配置：" + modelCode
            );
        }

        if (item.getFallbackPriority() == null
                || item.getFallbackPriority() < 1) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "模型优先级必须大于0"
            );
        }

        if (!priorities.add(
                item.getFallbackPriority())) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "模型优先级不能重复"
            );
        }
    }

    private ModelAssignmentVO buildVO(String subjectType,String subjectId) {
        List<ModelAssignment> rows =listRows(subjectType, subjectId);
        Map<String, ModelOption> enabledModels =new HashMap<>();
        for (ModelOption option : modelConfigService.listEnabledOptions()) {
            enabledModels.put(option.modelCode(),option );
        }

        List<ModelAssignmentItemVO> items = rows.stream()
                .map(row -> toItemVO(
                        row,
                        enabledModels.get(
                                row.getModelCode())
                ))
                .toList();

        return ModelAssignmentVO.builder()
                .subjectType(subjectType)
                .subjectId(subjectId)
                .models(items)
                .build();
    }

    private ModelAssignmentItemVO toItemVO(
            ModelAssignment row,
            ModelOption option) {

        return ModelAssignmentItemVO.builder()
                .modelCode(row.getModelCode())
                .displayName(
                        option == null
                                ? row.getModelCode()
                                : option.displayName()
                )
                .providerCode(
                        option == null
                                ? null
                                : option.providerCode()
                )
                .enabled(option != null)
                .defaultModel(
                        row.getDefaultModel() != null
                                && row.getDefaultModel() == 1
                )
                .fallbackPriority(
                        row.getFallbackPriority()
                )
                .build();
    }

    private void validateUserId(String userId) {
        if (!StringUtils.hasText(userId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,"用户编码不能为空");
        }
        if (userId.length() > 64) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,"用户编码不能超过64个字符");
        }
    }
}