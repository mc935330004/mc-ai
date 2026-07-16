package org.example.ai.agent.workflow.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.result.Result;
import org.example.ai.agent.security.CurrentUserProvider;
import org.example.ai.agent.workflow.dto.WorkflowDebugRequestDTO;
import org.example.ai.agent.workflow.dto.WorkflowSaveDTO;
import org.example.ai.agent.workflow.entity.WorkflowDefinition;
import org.example.ai.agent.workflow.entity.WorkflowVersion;
import org.example.ai.agent.workflow.run.service.WorkflowDebugService;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionOutcome;
import org.example.ai.agent.workflow.service.WorkflowDefinitionService;
import org.example.ai.agent.workflow.vo.WorkflowDetailVO;
import org.example.ai.agent.workflow.vo.WorkflowPublishResultVO;
import org.example.ai.agent.workflow.vo.WorkflowValidationVO;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 工作流定义管理接口。
 *
 * 当前阶段仅提供后端接口，不编写前端工作流页面。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/agent/workflows")
public class WorkflowDefinitionController {

    private final WorkflowDefinitionService workflowService;
    private final CurrentUserProvider currentUserProvider;
    private final WorkflowDebugService workflowDebugService;

    /**
     * 分页查询工作流。
     */
    @GetMapping("/pageList")
    public Result<Page<WorkflowDefinition>> pageList(
            Page<WorkflowDefinition> page,
            @RequestParam( value = "keyword", required = false)String keyword,
            @RequestParam( value = "publishStatus", required = false)
            String publishStatus,
            @RequestParam(value = "enabled",required = false)Integer enabled) {
        return Result.success(
                workflowService.pageWorkflows(
                        page,
                        keyword,
                        publishStatus,
                        enabled
                )
        );
    }

    /**
     * 查询工作流详情及活动版本。
     */
    @GetMapping("/detail/{id}")
    public Result<WorkflowDetailVO> detail( @PathVariable Long id) {
        return Result.success(workflowService.detail(id) );
    }

    /**
     * 保存工作流草稿。
     */
    @PostMapping("/save")
    public Result<WorkflowDefinition> save(@RequestBody WorkflowSaveDTO dto) {

        return Result.success( workflowService.saveDraft( dto,currentUserProvider.getRequiredUserId() ) );
    }

    /**
     * 校验草稿，不执行工作流。
     */
    @PostMapping("/{id}/validate")
    public Result<WorkflowValidationVO> validate(@PathVariable Long id) {
        return Result.success( workflowService.validateDraft(id));
    }

    /**
     * 发布工作流。
     */
    @PostMapping("/{id}/publish")
    public Result<WorkflowPublishResultVO> publish(
            @PathVariable Long id) {

        return Result.success(
                workflowService.publish(
                        id,
                        currentUserProvider
                                .getRequiredUserId()
                )
        );
    }

    /**
     * 启用已发布工作流。
     */
    @PostMapping("/{id}/enable")
    public Result<Boolean> enable(
            @PathVariable Long id) {

        return Result.success(
                workflowService.updateEnabled(
                        id,
                        1,
                        currentUserProvider
                                .getRequiredUserId()
                )
        );
    }

    /**
     * 停用工作流。
     */
    @PostMapping("/{id}/disable")
    public Result<Boolean> disable(
            @PathVariable Long id) {

        return Result.success(
                workflowService.updateEnabled(
                        id,
                        0,
                        currentUserProvider
                                .getRequiredUserId()
                )
        );
    }

    /**
     * 查询全部历史版本。
     */
    @GetMapping("/{id}/versions")
    public Result<List<WorkflowVersion>> versions(
            @PathVariable Long id) {

        return Result.success(
                workflowService.listVersions(id)
        );
    }

    /**
     * 查询指定版本。
     */
    @GetMapping("/{id}/versions/{versionNo}")
    public Result<WorkflowVersion> versionDetail(
            @PathVariable Long id,
            @PathVariable Integer versionNo) {

        return Result.success(
                workflowService.getVersion(
                        id,
                        versionNo
                )
        );
    }
    @PostMapping("/{id}/debug")
    public Result<WorkflowExecutionOutcome> debug(@PathVariable Long id,
                                                  @RequestBody(required = false) WorkflowDebugRequestDTO request) {
        return Result.success(
                workflowDebugService.debug(
                        id,
                        request,
                        currentUserProvider
                                .getRequiredUserId(),
                        currentUserProvider
                                .getRequiredAuthorization()
                )
        );
    }
}