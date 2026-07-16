package org.example.ai.agent.workflow.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import org.example.ai.agent.workflow.dto.WorkflowSaveDTO;
import org.example.ai.agent.workflow.entity.WorkflowDefinition;
import org.example.ai.agent.workflow.entity.WorkflowVersion;
import org.example.ai.agent.workflow.vo.WorkflowDetailVO;
import org.example.ai.agent.workflow.vo.WorkflowPublishResultVO;
import org.example.ai.agent.workflow.vo.WorkflowValidationVO;

import java.util.List;

/**
 * 工作流定义服务。
 */
public interface WorkflowDefinitionService  extends IService<WorkflowDefinition> {

    Page<WorkflowDefinition> pageWorkflows(
            Page<WorkflowDefinition> page,
            String keyword,
            String publishStatus,
            Integer enabled
    );

    WorkflowDefinition saveDraft(
            WorkflowSaveDTO dto,
            String operator
    );

    WorkflowDetailVO detail(Long id);

    WorkflowValidationVO validateDraft(Long id);

    WorkflowPublishResultVO publish(
            Long id,
            String publishedBy
    );

    Boolean updateEnabled(
            Long id,
            Integer enabled,
            String operator
    );

    List<WorkflowVersion> listVersions(
            Long workflowId
    );

    WorkflowVersion getVersion(
            Long workflowId,
            Integer versionNo
    );
    /**
     * 查询Agent允许选择的已发布工作流。
     */
    List<WorkflowDefinition>listAgentCallableDefinitions();
}