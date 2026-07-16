package org.example.ai.agent.workflow.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.workflow.dto.WorkflowSaveDTO;
import org.example.ai.agent.workflow.entity.WorkflowDefinition;
import org.example.ai.agent.workflow.entity.WorkflowVersion;
import org.example.ai.agent.workflow.mapper.WorkflowDefinitionMapper;
import org.example.ai.agent.workflow.mapper.WorkflowVersionMapper;
import org.example.ai.agent.workflow.service.WorkflowDefinitionService;
import org.example.ai.agent.workflow.snapshot.WorkflowGraphMaterial;
import org.example.ai.agent.workflow.snapshot.WorkflowGraphSnapshotFactory;
import org.example.ai.agent.workflow.vo.WorkflowDetailVO;
import org.example.ai.agent.workflow.vo.WorkflowPublishResultVO;
import org.example.ai.agent.workflow.vo.WorkflowValidationVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 工作流定义服务实现。
 */
@Service
@RequiredArgsConstructor
public class WorkflowDefinitionServiceImpl extends ServiceImpl< WorkflowDefinitionMapper, WorkflowDefinition>
        implements WorkflowDefinitionService {

    private static final int MAX_GRAPH_JSON_LENGTH =
            1_000_000;

    private static final Pattern WORKFLOW_CODE_PATTERN =
            Pattern.compile(
                    "^[a-z][a-z0-9._-]{0,127}$"
            );

    private final WorkflowDefinitionMapper workflowMapper;
    private final WorkflowVersionMapper versionMapper;
    private final WorkflowGraphSnapshotFactory snapshotFactory;

    @Override
    public Page<WorkflowDefinition> pageWorkflows(
            Page<WorkflowDefinition> page,
            String keyword,
            String publishStatus,
            Integer enabled) {

        return workflowMapper.pageWorkflows(
                page,
                keyword,
                publishStatus,
                enabled
        );
    }

    /**
     * 保存草稿。
     *
     * 注意：
     * 1. JSON必须可以解析；
     * 2. DAG拓扑可以暂时不完整；
     * 3. 已发布工作流保存草稿时，不修改activeVersionId；
     * 4. 正式运行仍继续使用旧版本。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkflowDefinition saveDraft(
            WorkflowSaveDTO dto,
            String operator) {

        validateSaveRequest(dto, operator);

        String workflowCode =
                dto.getWorkflowCode().trim();

        String workflowName =
                dto.getWorkflowName().trim();

        WorkflowGraphMaterial material =
                snapshotFactory.analyzeDraft(
                        workflowCode,
                        workflowName,
                        dto.getGraphSpecJson()
                );

        WorkflowDefinition definition;

        if (dto.getId() == null) {
            Long count = workflowMapper.selectCount(
                    Wrappers
                            .<WorkflowDefinition>lambdaQuery()
                            .eq(
                                    WorkflowDefinition::getWorkflowCode,
                                    workflowCode
                            )
            );

            if (count != null && count > 0) {
                throw new BusinessException(
                        400,
                        "工作流编码已经存在：" +
                                workflowCode
                );
            }

            definition = new WorkflowDefinition();
            definition.setWorkflowCode(workflowCode);
            definition.setConfigRevision(1);
            definition.setPublishStatus("DRAFT");
            definition.setEnabled(0);
            definition.setActiveVersionId(null);
            definition.setConfigChecksum(null);
            definition.setDraftDirty(1);
            definition.setValidatedAt(null);
            definition.setCreatedBy(operator);
            definition.setCreatedAt(
                    LocalDateTime.now()
            );

        } else {
            definition =
                    workflowMapper.selectByIdForUpdate(
                            dto.getId()
                    );

            if (definition == null) {
                throw new BusinessException(
                        404,
                        "工作流不存在：" + dto.getId()
                );
            }

            if (!Objects.equals(
                    definition.getWorkflowCode(),
                    workflowCode
            )) {
                throw new BusinessException(
                        400,
                        "工作流编码创建后不能修改"
                );
            }

            int currentRevision =
                    definition.getConfigRevision() == null
                            ? 0
                            : definition.getConfigRevision();

            definition.setConfigRevision(
                    currentRevision + 1
            );
        }

        definition.setWorkflowName(workflowName);
        definition.setDescription(
                trimToNull(dto.getDescription())
        );
        definition.setGraphSpecJson(
                material.normalizedGraphSpecJson()
        );
        definition.setDraftChecksum(
                material.checksum()
        );
        definition.setUpdatedBy(operator);
        definition.setUpdatedAt(
                LocalDateTime.now()
        );

        /*
         * 尚未发布过的工作流保持DRAFT状态。
         */
        if (definition.getActiveVersionId() == null) {
            definition.setPublishStatus("DRAFT");
            definition.setEnabled(0);
            definition.setConfigChecksum(null);
            definition.setDraftDirty(1);
            definition.setValidatedAt(null);

        } else {
            /*
             * 已发布工作流只更新草稿。
             * activeVersionId和configChecksum继续指向旧版本。
             */
            boolean unchanged = Objects.equals(
                    definition.getConfigChecksum(),
                    material.checksum()
            );

            definition.setDraftDirty(
                    unchanged ? 0 : 1
            );

            if (!unchanged) {
                /*
                 * 当前草稿已经发生变化，
                 * 不能继续把旧发布时间视为草稿校验时间。
                 */
                definition.setValidatedAt(null);
            }
        }

        int affected;

        if (definition.getId() == null) {
            affected = workflowMapper.insert(
                    definition
            );
        } else {
            affected = workflowMapper.updateById(
                    definition
            );
        }

        if (affected != 1) {
            throw new IllegalStateException(
                    "工作流草稿保存失败：" +
                            workflowCode
            );
        }

        return definition;
    }

    @Override
    public WorkflowDetailVO detail(Long id) {
        WorkflowDefinition definition =
                getRequiredDefinition(id);

        WorkflowVersion activeVersion = null;

        if (definition.getActiveVersionId() != null) {
            activeVersion = versionMapper.selectById(
                    definition.getActiveVersionId()
            );
        }

        return WorkflowDetailVO.builder()
                .definition(definition)
                .activeVersion(activeVersion)
                .build();
    }

    /**
     * 纯校验接口，不修改数据库。
     */
    @Override
    public WorkflowValidationVO validateDraft(
            Long id) {

        WorkflowDefinition definition =
                getRequiredDefinition(id);

        WorkflowGraphMaterial material =
                snapshotFactory.analyzeDraft(
                        definition.getWorkflowCode(),
                        definition.getWorkflowName(),
                        definition.getGraphSpecJson()
                );

        return WorkflowValidationVO.builder()
                .workflowId(definition.getId())
                .workflowCode(
                        definition.getWorkflowCode()
                )
                .configRevision(
                        definition.getConfigRevision()
                )
                .valid(material.valid())
                .nodeCount(material.nodeCount())
                .edgeCount(material.edgeCount())
                .draftChecksum(material.checksum())
                .errors(
                        material
                                .compilationResult()
                                .errors()
                )
                .build();
    }

    /**
     * 发布不可变工作流版本。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkflowPublishResultVO publish(
            Long id,
            String publishedBy) {

        if (!StringUtils.hasText(publishedBy)) {
            throw new BusinessException(
                    400,
                    "发布用户不能为空"
            );
        }

        WorkflowDefinition definition =
                workflowMapper.selectByIdForUpdate(id);

        if (definition == null) {
            throw new BusinessException(
                    404,
                    "工作流不存在：" + id
            );
        }

        /*
         * 发布时必须重新解析和重新编译，
         * 不能直接相信之前的校验结果。
         */
        WorkflowGraphMaterial material =
                snapshotFactory.analyzeDraft(
                        definition.getWorkflowCode(),
                        definition.getWorkflowName(),
                        definition.getGraphSpecJson()
                );

        if (!material.valid()) {
            return WorkflowPublishResultVO.builder()
                    .published(false)
                    .reused(false)
                    .workflowId(definition.getId())
                    .workflowCode(
                            definition.getWorkflowCode()
                    )
                    .configRevision(
                            definition.getConfigRevision()
                    )
                    .errors(
                            material
                                    .compilationResult()
                                    .errors()
                    )
                    .build();
        }

        WorkflowVersion version;
        boolean reused = false;

        if (definition.getActiveVersionId() != null) {
            WorkflowVersion active =
                    versionMapper.selectById(
                            definition.getActiveVersionId()
                    );

            validateActiveVersion(
                    definition,
                    active
            );

            /*
             * 草稿内容没有变化时复用当前版本。
             */
            if (Objects.equals(
                    active.getConfigChecksum(),
                    material.checksum()
            )) {
                version = active;
                reused = true;
            } else {
                version = createNewVersion(
                        definition,
                        material,
                        publishedBy.trim()
                );
            }

        } else {
            version = createNewVersion(
                    definition,
                    material,
                    publishedBy.trim()
            );
        }

        LocalDateTime now =
                LocalDateTime.now();

        definition.setActiveVersionId(
                version.getId()
        );
        definition.setConfigChecksum(
                version.getConfigChecksum()
        );
        definition.setDraftChecksum(
                material.checksum()
        );
        definition.setDraftDirty(0);
        definition.setPublishStatus("PUBLISHED");
        definition.setEnabled(1);
        definition.setValidatedAt(now);
        definition.setUpdatedBy(
                publishedBy.trim()
        );
        definition.setUpdatedAt(now);

        int updated =
                workflowMapper.updateById(
                        definition
                );

        if (updated != 1) {
            throw new IllegalStateException(
                    "工作流发布状态更新失败：" +
                            definition.getWorkflowCode()
            );
        }

        return WorkflowPublishResultVO.builder()
                .published(true)
                .reused(reused)
                .workflowId(definition.getId())
                .workflowCode(
                        definition.getWorkflowCode()
                )
                .versionId(version.getId())
                .versionNo(version.getVersionNo())
                .configRevision(
                        version.getConfigRevision()
                )
                .configChecksum(
                        version.getConfigChecksum()
                )
                .errors(List.of())
                .build();
    }

    private WorkflowVersion createNewVersion(
            WorkflowDefinition definition,
            WorkflowGraphMaterial material,
            String publishedBy) {

        WorkflowVersion latest =
                versionMapper
                        .selectLatestByWorkflowId(
                                definition.getId()
                        );

        int nextVersionNo =
                latest == null
                        ? 1
                        : latest.getVersionNo() + 1;

        LocalDateTime now =
                LocalDateTime.now();

        /*
         * 先退役旧版本，再创建新ACTIVE版本。
         * 外层事务保证任意一步失败都会整体回滚。
         */
        versionMapper.retireActiveVersions(
                definition.getId()
        );

        WorkflowVersion version =
                new WorkflowVersion();

        version.setWorkflowId(
                definition.getId()
        );
        version.setWorkflowCode(
                definition.getWorkflowCode()
        );
        version.setVersionNo(nextVersionNo);
        version.setConfigRevision(
                definition.getConfigRevision()
        );
        version.setSnapshotJson(
                material.normalizedGraphSpecJson()
        );
        version.setConfigChecksum(
                material.checksum()
        );
        version.setNodeCount(
                material.nodeCount()
        );
        version.setEdgeCount(
                material.edgeCount()
        );
        version.setStatus("ACTIVE");
        version.setPublishedBy(publishedBy);
        version.setPublishedAt(now);
        version.setCreatedAt(now);

        int inserted = versionMapper.insert(
                version
        );

        if (inserted != 1
                || version.getId() == null) {
            throw new IllegalStateException(
                    "工作流版本创建失败：" +
                            definition.getWorkflowCode()
            );
        }

        return version;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateEnabled(
            Long id,
            Integer enabled,
            String operator) {

        if (!List.of(0, 1).contains(enabled)) {
            throw new BusinessException(
                    400,
                    "enabled只允许为0或1"
            );
        }

        WorkflowDefinition definition =
                workflowMapper.selectByIdForUpdate(id);

        if (definition == null) {
            throw new BusinessException(
                    404,
                    "工作流不存在：" + id
            );
        }

        if (enabled == 1
                && definition.getActiveVersionId() == null) {
            throw new BusinessException(
                    400,
                    "工作流尚未发布，不能直接启用"
            );
        }

        definition.setEnabled(enabled);
        definition.setPublishStatus(
                enabled == 1
                        ? "PUBLISHED"
                        : "DISABLED"
        );
        definition.setUpdatedBy(operator);
        definition.setUpdatedAt(
                LocalDateTime.now()
        );

        return workflowMapper.updateById(
                definition
        ) == 1;
    }

    @Override
    public List<WorkflowVersion> listVersions(
            Long workflowId) {

        getRequiredDefinition(workflowId);

        return versionMapper.selectList(
                Wrappers
                        .<WorkflowVersion>lambdaQuery()
                        .eq(
                                WorkflowVersion::getWorkflowId,
                                workflowId
                        )
                        .orderByDesc(
                                WorkflowVersion::getVersionNo
                        )
        );
    }

    @Override
    public WorkflowVersion getVersion(
            Long workflowId,
            Integer versionNo) {

        WorkflowVersion version =
                versionMapper.selectOne(
                        Wrappers
                                .<WorkflowVersion>lambdaQuery()
                                .eq(
                                        WorkflowVersion::getWorkflowId,
                                        workflowId
                                )
                                .eq(
                                        WorkflowVersion::getVersionNo,
                                        versionNo
                                )
                );

        if (version == null) {
            throw new BusinessException(
                    404,
                    "工作流版本不存在：" +
                            workflowId + "/" + versionNo
            );
        }

        return version;
    }

    @Override
    public List<WorkflowDefinition> listAgentCallableDefinitions() {
        return lambdaQuery()
                .eq(WorkflowDefinition::getEnabled,1)
                .eq(WorkflowDefinition::getPublishStatus,"PUBLISHED")
                .isNotNull( WorkflowDefinition::getActiveVersionId)
                .orderByAsc(WorkflowDefinition::getWorkflowCode )
                /*
                 * L1-5先限制候选目录大小。
                 * 工作流数量继续增加后再建设向量召回。
                 */
                .last("LIMIT 30")
                .list();
    }

    private void validateActiveVersion(
            WorkflowDefinition definition,
            WorkflowVersion version) {

        if (version == null) {
            throw new IllegalStateException(
                    "activeVersionId对应版本不存在：" +
                            definition.getActiveVersionId()
            );
        }

        if (!Objects.equals(
                definition.getId(),
                version.getWorkflowId()
        )) {
            throw new IllegalStateException(
                    "活动版本不属于当前工作流"
            );
        }

        if (!"ACTIVE".equals(
                version.getStatus()
        )) {
            throw new IllegalStateException(
                    "activeVersionId指向的不是ACTIVE版本"
            );
        }
    }

    private WorkflowDefinition getRequiredDefinition(
            Long id) {

        if (id == null) {
            throw new BusinessException(
                    400,
                    "工作流ID不能为空"
            );
        }

        WorkflowDefinition definition =
                workflowMapper.selectById(id);

        if (definition == null) {
            throw new BusinessException(
                    404,
                    "工作流不存在：" + id
            );
        }

        return definition;
    }

    private void validateSaveRequest(
            WorkflowSaveDTO dto,
            String operator) {

        if (dto == null) {
            throw new BusinessException(
                    400,
                    "工作流保存参数不能为空"
            );
        }

        if (!StringUtils.hasText(
                dto.getWorkflowCode()
        )) {
            throw new BusinessException(
                    400,
                    "workflowCode不能为空"
            );
        }

        String workflowCode =
                dto.getWorkflowCode().trim();

        if (!WORKFLOW_CODE_PATTERN
                .matcher(workflowCode)
                .matches()) {
            throw new BusinessException(
                    400,
                    "workflowCode格式不合法"
            );
        }

        if (!StringUtils.hasText(
                dto.getWorkflowName()
        )) {
            throw new BusinessException(
                    400,
                    "workflowName不能为空"
            );
        }

        if (dto.getWorkflowName()
                .trim()
                .length() > 128) {
            throw new BusinessException(
                    400,
                    "workflowName不能超过128个字符"
            );
        }

        if (StringUtils.hasText(
                dto.getDescription()
        ) && dto.getDescription().length() > 512) {
            throw new BusinessException(
                    400,
                    "description不能超过512个字符"
            );
        }

        if (!StringUtils.hasText(
                dto.getGraphSpecJson()
        )) {
            throw new BusinessException(
                    400,
                    "graphSpecJson不能为空"
            );
        }

        if (dto.getGraphSpecJson().length()
                > MAX_GRAPH_JSON_LENGTH) {
            throw new BusinessException(
                    400,
                    "GraphSpec不能超过1000000个字符"
            );
        }

        if (!StringUtils.hasText(operator)) {
            throw new BusinessException(
                    400,
                    "操作用户不能为空"
            );
        }
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value)
                ? value.trim()
                : null;
    }
}