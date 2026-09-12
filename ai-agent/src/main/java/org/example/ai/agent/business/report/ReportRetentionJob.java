package org.example.ai.agent.business.report;

import org.example.ai.agent.business.report.entity.CompositeReportTask;
import org.example.ai.agent.business.report.mapper.CompositeReportTaskMapper;
import org.example.ai.agent.business.report.mapper.CompositeReportSectionMapper;
import org.example.ai.agent.common.file.SafeArtifactStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 过期报告制品清理任务，逐任务先删物理文件，再清元数据并保留最小审计主记录。
 */
@Component
@ConditionalOnProperty(
        prefix = "ai.business.composite-report.retention",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false
)
public class ReportRetentionJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReportRetentionJob.class);
    private static final String SAFE_DELETE_ERROR = "REPORT_ARTIFACT_DELETE_FAILED";

    private final CompositeReportTaskMapper taskMapper;
    private final CompositeReportSectionMapper sectionMapper;
    private final SafeArtifactStorageService storage;
    private final int batchSize;

    public ReportRetentionJob(
            CompositeReportTaskMapper taskMapper,
            CompositeReportSectionMapper sectionMapper,
            SafeArtifactStorageService storage,
            @Value("${ai.business.composite-report.retention.batch-size:200}") int batchSize) {
        this.taskMapper = Objects.requireNonNull(taskMapper, "taskMapper不能为空");
        this.sectionMapper = Objects.requireNonNull(sectionMapper, "sectionMapper不能为空");
        this.storage = Objects.requireNonNull(storage, "storage不能为空");
        if (batchSize < 1 || batchSize > 5000) {
            throw new IllegalArgumentException("报告清理批次必须在1至5000之间");
        }
        this.batchSize = batchSize;
    }

    /** 单个任务失败只记安全错误码，继续处理同批次其余任务。 */
    @Scheduled(cron = "${ai.business.composite-report.retention.cron:0 30 3 * * *}")
    public CleanupResult cleanupBatch() {
        List<CompositeReportTask> candidates = taskMapper.selectExpiredArtifactCandidates(batchSize);
        int scanned = 0;
        int expired = 0;
        int failed = 0;
        if (candidates == null) {
            return new CleanupResult(0, 0, 0);
        }
        for (CompositeReportTask task : candidates) {
            scanned++;
            try {
                if (task.getStoragePath() != null) {
                    storage.delete(task.getStoragePath());
                }
                if (taskMapper.markExpiredAndClearArtifact(
                        task.getTaskId(), task.getStoragePath()) == 1) {
                    sectionMapper.clearSnapshotReferences(task.getTaskId());
                    expired++;
                } else {
                    failed++;
                    LOGGER.warn("报告制品清理失败 taskId={} safeErrorCode={}",
                            safeTaskId(task), SAFE_DELETE_ERROR);
                }
            } catch (Exception exception) {
                failed++;
                LOGGER.warn("报告制品清理失败 taskId={} safeErrorCode={}",
                        safeTaskId(task), SAFE_DELETE_ERROR);
            }
        }
        return new CleanupResult(scanned, expired, failed);
    }

    /** 清理批次安全计数结果。 */
    public record CleanupResult(int scanned, int expired, int failed) {
    }

    private String safeTaskId(CompositeReportTask task) {
        String taskId = task == null ? null : task.getTaskId();
        return taskId != null && taskId.matches("[A-Za-z0-9_-]{1,64}") ? taskId : "UNKNOWN";
    }
}
