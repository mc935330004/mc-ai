package org.example.ai.agent.workflow.run.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.enums.WorkflowRunStatus;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.graph.runtime.ForEachItemResult;
import org.example.ai.agent.trace.entity.RunStep;
import org.example.ai.agent.trace.mapper.RunStepMapper;
import org.example.ai.agent.workflow.run.entity.WorkflowRun;
import org.example.ai.agent.workflow.run.entity.WorkflowRunItem;
import org.example.ai.agent.workflow.run.mapper.WorkflowRunItemMapper;
import org.example.ai.agent.workflow.run.mapper.WorkflowRunMapper;
import org.example.ai.agent.workflow.run.model.WorkflowRunStartCommand;
import org.example.ai.agent.workflow.run.service.WorkflowRunService;
import org.example.ai.agent.workflow.runtime.WorkflowBatchSummary;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionOutcome;
import org.example.ai.agent.workflow.vo.WorkflowRunDetailVO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Workflow RunOps运行生命周期服务。
 */
@Service
@RequiredArgsConstructor
public class WorkflowRunServiceImpl extends ServiceImpl<WorkflowRunMapper,WorkflowRun>
        implements WorkflowRunService {

    private final WorkflowRunMapper runMapper;
    private final WorkflowRunItemMapper itemMapper;
    private final RunStepMapper runStepMapper;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkflowRun start( WorkflowRunStartCommand command) {

        if (command == null
                || !StringUtils.hasText(
                        command.runId()
                )) {
            throw new BusinessException(
                    400,
                    "工作流运行参数不能为空"
            );
        }

        WorkflowRun run = new WorkflowRun();

        run.setRunId(command.runId());
        run.setAgentRunId(
                command.agentRunId()
        );

        run.setRootRunId( StringUtils.hasText(
                        command.rootRunId() )
                        ? command.rootRunId()
                        : command.runId());

        run.setSourceRunId(
                command.sourceRunId()
        );
        run.setRequestId(
                trimToNull(command.requestId())
        );

        run.setWorkflowId(
                command.workflowId()
        );
        run.setWorkflowCode(
                command.workflowCode()
        );
        run.setWorkflowName(
                command.workflowName()
        );
        run.setWorkflowVersionId(
                command.workflowVersionId()
        );
        run.setWorkflowVersionNo(
                command.workflowVersionNo()
        );
        run.setConfigRevision(
                command.configRevision()
        );
        run.setConfigChecksum(
                command.configChecksum()
        );

        run.setOrigin(
                command.origin().name()
        );
        run.setStatus(
                WorkflowRunStatus.RUNNING.name()
        );
        run.setUserId(command.userId());

        /*
         * 这里只保存清洗后的业务输入。
         * 不保存Authorization和secureContext。
         */
        run.setInputJson(
                writeJson(command.input())
        );

        run.setTotalItemCount(0);
        run.setSuccessItemCount(0);
        run.setFailureItemCount(0);
        run.setSkippedItemCount(0);

        LocalDateTime now =
                LocalDateTime.now();

        run.setStartedAt(now);
        run.setCreatedAt(now);

        try {
            int inserted = runMapper.insert(run);

            if (inserted != 1
                    || run.getId() == null) {
                throw new IllegalStateException(
                        "工作流运行记录创建失败"
                );
            }
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(
                    409,
                    "相同工作流运行请求已经提交"
            );
        }

        return run;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void complete(
            String runId,
            WorkflowExecutionOutcome outcome) {

        WorkflowRun run =
                runMapper.selectByRunIdForUpdate(
                        runId
                );

        if (run == null) {
            throw new IllegalStateException(
                    "工作流运行记录不存在：" +
                            runId
            );
        }

        if (!WorkflowRunStatus.RUNNING.name()
                .equals(run.getStatus())) {
            throw new IllegalStateException(
                    "工作流运行已经结束：" +
                            runId
            );
        }

        int total = 0;
        int success = 0;
        int failure = 0;
        int skipped = 0;

        for (WorkflowBatchSummary batch :
                outcome.batches()) {

            total += batch.totalCount();
            success += batch.successCount();
            failure += batch.failureCount();
            skipped += batch.skippedCount();

            saveBatchItems(
                    runId,
                    batch
            );
        }

        WorkflowRunStatus status;

        if (!outcome.success()) {
            status = WorkflowRunStatus.FAILED;
        } else if (outcome.partialSuccess()) {
            status =
                    WorkflowRunStatus
                            .PARTIAL_SUCCESS;
        } else {
            status = WorkflowRunStatus.SUCCESS;
        }

        run.setStatus(status.name());
        run.setResultJson(writeJson(outcome));
        run.setTotalItemCount(total);
        run.setSuccessItemCount(success);
        run.setFailureItemCount(failure);
        run.setSkippedItemCount(skipped);
        run.setErrorCode(
                outcome.errorCode()
        );
        run.setErrorMessage(
                truncate(
                        outcome.errorMessage(),
                        1000
                )
        );
        run.setDurationMs(
                outcome.durationMs()
        );
        run.setFinishedAt(
                LocalDateTime.now()
        );

        if (runMapper.updateById(run) != 1) {
            throw new IllegalStateException(
                    "工作流运行状态更新失败：" +
                            runId
            );
        }
    }

    private void saveBatchItems(
            String runId,
            WorkflowBatchSummary batch) {

        for (ForEachItemResult item :
                batch.items()) {

            WorkflowRunItem entity =
                    new WorkflowRunItem();

            entity.setRunId(runId);
            entity.setNodeId(
                    batch.nodeId()
            );
            entity.setItemIndex(
                    item.index()
            );
            entity.setItemJson(
                    writeJson(item.item())
            );
            entity.setResultJson(
                    item.data() == null
                            ? null
                            : writeJson(item.data())
            );
            entity.setStatus(
                    item.status().name()
            );
            entity.setErrorCode(
                    item.errorCode()
            );
            entity.setErrorMessage(
                    truncate(
                            item.errorMessage(),
                            1000
                    )
            );
            entity.setDurationMs(
                    item.durationMs()
            );
            entity.setCreatedAt(
                    LocalDateTime.now()
            );

            if (itemMapper.insert(entity) != 1) {
                throw new IllegalStateException(
                        "工作流项目结果保存失败"
                );
            }
        }
    }

    @Override
    public void markFailed(
            String runId,
            String errorCode,
            String errorMessage,
            long durationMs) {

        WorkflowRun update =
                new WorkflowRun();

        update.setStatus(
                WorkflowRunStatus.FAILED.name()
        );
        update.setErrorCode(errorCode);
        update.setErrorMessage(
                truncate(errorMessage, 1000)
        );
        update.setDurationMs(durationMs);
        update.setFinishedAt(
                LocalDateTime.now()
        );

        runMapper.update(
                update,
                Wrappers
                        .<WorkflowRun>lambdaUpdate()
                        .eq(
                                WorkflowRun::getRunId,
                                runId
                        )
                        .eq(
                                WorkflowRun::getStatus,
                                WorkflowRunStatus
                                        .RUNNING
                                        .name()
                        )
        );
    }

    @Override
    public Page<WorkflowRun> pageRuns(
            Page<WorkflowRun> page,
            String userId,
            String workflowCode,
            String status,
            String origin) {

        return runMapper.pageRuns(
                page,
                userId,
                workflowCode,
                status,
                origin
        );
    }

    @Override
    public WorkflowRunDetailVO detailOwned(
            String runId,
            String userId) {

        WorkflowRun run =
                getRequiredOwned(
                        runId,
                        userId
                );

        List<WorkflowRunItem> items =
                itemMapper.selectList(
                        Wrappers
                                .<WorkflowRunItem>lambdaQuery()
                                .eq(
                                        WorkflowRunItem::getRunId,
                                        runId
                                )
                                .orderByAsc(
                                        WorkflowRunItem::getNodeId
                                )
                                .orderByAsc(
                                        WorkflowRunItem::getItemIndex
                                )
                );

        List<RunStep> steps =
                runStepMapper.selectList(
                        Wrappers
                                .<RunStep>lambdaQuery()
                                .eq(
                                        RunStep::getRunId,
                                        runId
                                )
                                .orderByAsc(
                                        RunStep::getCreatedAt
                                )
                                .orderByAsc(
                                        RunStep::getId
                                )
                );

        return WorkflowRunDetailVO.builder()
                .run(run)
                .items(items)
                .steps(steps)
                .build();
    }

    @Override
    public WorkflowRun getRequiredOwned(
            String runId,
            String userId) {

        WorkflowRun run =
                runMapper.selectOne(
                        Wrappers
                                .<WorkflowRun>lambdaQuery()
                                .eq(
                                        WorkflowRun::getRunId,
                                        runId
                                )
                                .eq(
                                        WorkflowRun::getUserId,
                                        userId
                                )
                );

        if (run == null) {
            throw new BusinessException(
                    404,
                    "工作流运行记录不存在"
            );
        }

        return run;
    }

    @Override
    public List<WorkflowRunItem>
    listFailedItems(
            String runId,
            String nodeId) {

        return itemMapper.selectList(
                Wrappers
                        .<WorkflowRunItem>lambdaQuery()
                        .eq(
                                WorkflowRunItem::getRunId,
                                runId
                        )
                        .eq(
                                WorkflowRunItem::getNodeId,
                                nodeId
                        )
                        .eq(
                                WorkflowRunItem::getStatus,
                                "FAILED"
                        )
                        .orderByAsc(
                                WorkflowRunItem::getItemIndex
                        )
        );
    }

    @Override
    public WorkflowRun findByRequestId(
            String requestId) {

        if (!StringUtils.hasText(requestId)) {
            return null;
        }

        return runMapper.selectOne(
                Wrappers
                        .<WorkflowRun>lambdaQuery()
                        .eq(
                                WorkflowRun::getRequestId,
                                requestId.trim()
                        )
        );
    }

    @Override
    public WorkflowExecutionOutcome readOutcome(
            WorkflowRun run) {

        if (run == null
                || !StringUtils.hasText(
                        run.getResultJson()
                )) {
            return null;
        }

        try {
            return objectMapper.readValue(
                    run.getResultJson(),
                    WorkflowExecutionOutcome.class
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "历史工作流结果解析失败",
                    exception
            );
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(
                    value
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "工作流运行数据序列化失败",
                    exception
            );
        }
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value)
                ? value.trim()
                : null;
    }

    private String truncate(
            String value,
            int maxLength) {

        if (value == null
                || value.length() <= maxLength) {
            return value;
        }

        return value.substring(
                0,
                maxLength
        );
    }
}