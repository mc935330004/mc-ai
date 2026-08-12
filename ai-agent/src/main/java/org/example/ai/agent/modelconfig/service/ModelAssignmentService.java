package org.example.ai.agent.modelconfig.service;

import org.example.ai.agent.modelconfig.dto.ModelAssignmentSaveDTO;
import org.example.ai.agent.modelconfig.entity.ModelAssignment;
import org.example.ai.agent.modelconfig.vo.ModelAssignmentVO;

import java.util.List;

/**
 * 系统和人员模型授权服务。
 */
public interface ModelAssignmentService {

    ModelAssignmentVO getSystemAssignment();

    ModelAssignmentVO saveSystemAssignment(
            ModelAssignmentSaveDTO dto,
            String operator
    );

    ModelAssignmentVO getUserAssignment(String userId);

    ModelAssignmentVO saveUserAssignment(
            String userId,
            ModelAssignmentSaveDTO dto,
            String operator
    );

    /**
     * 删除用户专属配置后，用户重新继承系统配置。
     *
     * @param userId 用户编号
     * @param operator 操作人编号
     */
    void deleteUserAssignment(String userId, String operator);

    /**
     * 提供给模型解析服务读取原始授权顺序。
     */
    List<ModelAssignment> listRows(
            String subjectType,
            String subjectId
    );
}