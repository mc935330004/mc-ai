package org.example.ai.agent.workflow.runtime;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.workflow.entity.WorkflowDefinition;
import org.example.ai.agent.workflow.entity.WorkflowVersion;
import org.example.ai.agent.workflow.mapper.WorkflowDefinitionMapper;
import org.example.ai.agent.workflow.mapper.WorkflowVersionMapper;
import org.example.ai.agent.workflow.snapshot.WorkflowGraphMaterial;
import org.example.ai.agent.workflow.snapshot.WorkflowGraphSnapshotFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

/**
 * 正式运行工作流快照解析器。
 *
 * Agent不能读取主表中的graphSpecJson草稿。
 */
@Component
@RequiredArgsConstructor
public class WorkflowRuntimeSnapshotResolver {

    private final WorkflowDefinitionMapper workflowMapper;
    private final WorkflowVersionMapper versionMapper;
    private final WorkflowGraphSnapshotFactory snapshotFactory;

    public PublishedWorkflow resolveByCode(
            String workflowCode) {

        if (!StringUtils.hasText(workflowCode)) {
            throw new BusinessException(
                    400,
                    "workflowCode不能为空"
            );
        }

        WorkflowDefinition definition =
                workflowMapper.selectOne(
                        Wrappers.<WorkflowDefinition>lambdaQuery()
                                .eq(WorkflowDefinition::getWorkflowCode,workflowCode.trim())
                                .eq(WorkflowDefinition::getEnabled,1)
                                .eq(WorkflowDefinition::getPublishStatus,  "PUBLISHED")
                                .isNotNull(WorkflowDefinition::getActiveVersionId)
                );

        if (definition == null) {
            throw new BusinessException(
                    404,
                    "没有可运行的已发布工作流：" +
                            workflowCode
            );
        }

        WorkflowVersion version =
                versionMapper.selectById(
                        definition.getActiveVersionId()
                );

        validateVersion(definition, version);

        /*
         * 检查数据库中的发布快照是否被非法修改。
         */
        String actualChecksum =
                snapshotFactory.checksumRaw(
                        version.getSnapshotJson()
                );

        if (!Objects.equals(
                actualChecksum,
                version.getConfigChecksum()
        )) {
            throw new IllegalStateException(
                    "工作流版本快照校验和不一致，versionId=" +
                            version.getId()
            );
        }

        if (!Objects.equals( definition.getConfigChecksum(), version.getConfigChecksum())) {
            throw new IllegalStateException(
                    "工作流定义与活动版本校验和不一致"
            );
        }

        /*
         * 每次运行前重新编译发布快照。
         *
         * 如果工作流引用的能力已经被停用，
         * GraphSpecCompiler会使运行失败，达到失败关闭效果。
         */
        WorkflowGraphMaterial material =
                snapshotFactory.analyzePublished(
                        definition.getWorkflowCode(),
                        version.getSnapshotJson()
                );

        if (!material.valid()) {
            throw new BusinessException(
                    409,
                    "已发布工作流当前不可执行：" +
                            material
                                    .compilationResult()
                                    .errors()
            );
        }

        return new PublishedWorkflow(
                definition,
                version,
                material
                        .compilationResult()
                        .compiledGraph(),
                snapshotFactory.readInputSchema(version.getSnapshotJson())
        );
    }

    private void validateVersion(
            WorkflowDefinition definition,
            WorkflowVersion version) {

        if (version == null) {
            throw new IllegalStateException(
                    "工作流活动版本不存在：" +
                            definition.getActiveVersionId()
            );
        }

        if (!Objects.equals(
                definition.getId(),
                version.getWorkflowId()
        )) {
            throw new IllegalStateException(
                    "工作流版本归属错误：" +
                            version.getId()
            );
        }

        if (!"ACTIVE".equals(
                version.getStatus()
        )) {
            throw new IllegalStateException(
                    "活动版本状态不是ACTIVE：" +
                            version.getId()
            );
        }

        if (!Objects.equals(
                definition.getWorkflowCode(),
                version.getWorkflowCode()
        )) {
            throw new IllegalStateException(
                    "工作流版本编码不一致：" +
                            version.getId()
            );
        }
    }

    /**
     * 失败重试时加载指定历史版本。
     *
     * 工作流本身仍必须启用，
     * 但版本可以是ACTIVE或RETIRED。
     */
    public PublishedWorkflow resolveExactVersion(
            String workflowCode,
            Long versionId) {

        if (!StringUtils.hasText(workflowCode)
                || versionId == null) {
            throw new BusinessException(
                    400,
                    "workflowCode和versionId不能为空"
            );
        }

        WorkflowDefinition definition =
                workflowMapper.selectOne(
                        Wrappers
                                .<WorkflowDefinition>lambdaQuery()
                                .eq(
                                        WorkflowDefinition::getWorkflowCode,
                                        workflowCode.trim()
                                )
                                .eq(
                                        WorkflowDefinition::getEnabled,
                                        1
                                )
                                .eq(
                                        WorkflowDefinition::getPublishStatus,
                                        "PUBLISHED"
                                )
                );

        if (definition == null) {
            throw new BusinessException(
                    404,
                    "工作流不存在、未发布或已停用"
            );
        }

        WorkflowVersion version =
                versionMapper.selectById(
                        versionId
                );

        if (version == null
                || !Objects.equals(
                version.getWorkflowId(),
                definition.getId()
        )) {
            throw new BusinessException(
                    404,
                    "指定工作流版本不存在"
            );
        }

        if (!List.of("ACTIVE", "RETIRED")
                .contains(version.getStatus())) {
            throw new BusinessException(
                    409,
                    "工作流版本状态不允许重试"
            );
        }

        String checksum =
                snapshotFactory.checksumRaw(
                        version.getSnapshotJson()
                );

        if (!Objects.equals(
                checksum,
                version.getConfigChecksum()
        )) {
            throw new IllegalStateException(
                    "工作流历史版本校验和不一致"
            );
        }

        WorkflowGraphMaterial material =
                snapshotFactory.analyzePublished(
                        definition.getWorkflowCode(),
                        version.getSnapshotJson()
                );

        if (!material.valid()) {
            throw new BusinessException(
                    409,
                    "历史工作流版本当前不可执行"
            );
        }

        return new PublishedWorkflow(
                definition,
                version,
                material
                        .compilationResult()
                        .compiledGraph(),
                snapshotFactory.readInputSchema(
                        version.getSnapshotJson()
                )
        );
    }
}