package org.example.ai.agent.modelconfig.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.result.Result;
import org.example.ai.agent.modelconfig.dto.ModelAssignmentSaveDTO;
import org.example.ai.agent.modelconfig.service.ModelAssignmentService;
import org.example.ai.agent.modelconfig.vo.ModelAssignmentVO;
import org.example.ai.agent.security.CurrentUserProvider;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统和人员模型授权管理接口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/agent/admin/model-assignments")
public class ModelAssignmentController {

    private final ModelAssignmentService assignmentService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping("/system")
    public Result<ModelAssignmentVO> getSystem() {
        return Result.success(
                assignmentService.getSystemAssignment()
        );
    }

    @PutMapping("/system")
    public Result<ModelAssignmentVO> saveSystem( @Valid @RequestBody ModelAssignmentSaveDTO dto) {
        String operator =currentUserProvider.getRequiredUserId();
        return Result.success(assignmentService.saveSystemAssignment(dto,operator));
    }

    @GetMapping("/users/{userId}")
    public Result<ModelAssignmentVO> getUser( @PathVariable String userId) {
        return Result.success(assignmentService.getUserAssignment(userId));
    }

    @PutMapping("/users/{userId}")
    public Result<ModelAssignmentVO> saveUser(@PathVariable String userId,
                                              @Valid @RequestBody ModelAssignmentSaveDTO dto) {
        String operator =currentUserProvider.getRequiredUserId();
        return Result.success(assignmentService.saveUserAssignment(userId,dto,operator));
    }

    /**
     * 删除专属配置后恢复继承系统模型配置。
     */
    @DeleteMapping("/users/{userId}")
    public Result<Void> deleteUser(@PathVariable String userId) {
        String operator = currentUserProvider.getRequiredUserId();
        assignmentService.deleteUserAssignment(userId, operator);
        return Result.success();
    }
}