package org.example.ai.agent.business.report;

import jakarta.annotation.PreDestroy;
import org.example.ai.agent.business.dataset.entity.ReportDataset;
import org.example.ai.agent.business.dataset.mapper.ReportDatasetMapper;
import org.example.ai.agent.business.report.CompositeReportTaskService.ArtifactMetadata;
import org.example.ai.agent.business.report.entity.CompositeReportSection;
import org.example.ai.agent.business.report.entity.CompositeReportTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 组合报告任务的最小worker边界。
 *
 * Task13只负责租约、幂等和状态协调，不实现任何具体文件渲染器。
 */
@Component
@ConditionalOnProperty(
        prefix = "ai.business.composite-report.worker",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class CompositeReportWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(CompositeReportWorker.class);
    private static final String SAFE_ERROR_CODE = "REPORT_GENERATION_FAILED";
    private static final String SAFE_ERROR_MESSAGE = "报告文件生成失败";
    private static final String POLICY_ERROR_CODE = "REPORT_POLICY_CHANGED";
    private static final String POLICY_ERROR_MESSAGE = "报告字段策略已变更，请重新创建";
    private static final int RETRY_DELAY_SECONDS = 30;

    private final CompositeReportTaskService taskService;
    private final ReportDatasetMapper datasetMapper;
    private final ReportFileHandler fileHandler;
    private final ScheduledExecutorService renewalScheduler;
    private final String scheduledWorkerId;
    private final int leaseSeconds;
    private final int scanLimit;

    /** 生产环境允许渲染处理器稍后由Task14/15提供。 */
    @Autowired
    public CompositeReportWorker(
            CompositeReportTaskService taskService,
            ReportDatasetMapper datasetMapper,
            ObjectProvider<ReportFileHandler> fileHandlerProvider,
            @Value("${ai.business.composite-report.worker.lease-seconds:60}") int leaseSeconds,
            @Value("${ai.business.composite-report.worker.scan-limit:10}") int scanLimit) {
        this(
                taskService, datasetMapper, fileHandlerProvider.getIfAvailable(),
                leaseSeconds, scanLimit, newRenewalScheduler(), true
        );
    }

    public CompositeReportWorker(
            CompositeReportTaskService taskService,
            ReportDatasetMapper datasetMapper,
            ReportFileHandler fileHandler,
            int leaseSeconds,
            int scanLimit) {
        this(
                taskService, datasetMapper, fileHandler, leaseSeconds, scanLimit,
                newRenewalScheduler(), false
        );
    }

    CompositeReportWorker(
            CompositeReportTaskService taskService,
            ReportDatasetMapper datasetMapper,
            ReportFileHandler fileHandler,
            int leaseSeconds,
            int scanLimit,
            ScheduledExecutorService renewalScheduler) {
        this(
                taskService, datasetMapper, fileHandler, leaseSeconds, scanLimit,
                renewalScheduler, false
        );
    }

    private CompositeReportWorker(
            CompositeReportTaskService taskService,
            ReportDatasetMapper datasetMapper,
            ReportFileHandler fileHandler,
            int leaseSeconds,
            int scanLimit,
            ScheduledExecutorService renewalScheduler,
            boolean optionalHandler) {
        this.taskService = Objects.requireNonNull(taskService, "taskService不能为空");
        this.datasetMapper = Objects.requireNonNull(datasetMapper, "datasetMapper不能为空");
        this.fileHandler = optionalHandler
                ? fileHandler
                : Objects.requireNonNull(fileHandler, "fileHandler不能为空");
        this.renewalScheduler = Objects.requireNonNull(
                renewalScheduler, "renewalScheduler不能为空"
        );
        if (leaseSeconds < 2 || leaseSeconds > 3600) {
            throw new IllegalArgumentException("leaseSeconds必须在2至3600之间");
        }
        if (scanLimit < 1 || scanLimit > 100) {
            throw new IllegalArgumentException("scanLimit必须在1至100之间");
        }
        this.leaseSeconds = leaseSeconds;
        this.scanLimit = scanLimit;
        this.scheduledWorkerId = "report-" + UUID.randomUUID();
    }

    /** 调度入口隔离单轮异常，避免后续轮询被终止。 */
    @Scheduled(
            initialDelayString = "${ai.business.composite-report.worker.initial-delay-ms:5000}",
            fixedDelayString = "${ai.business.composite-report.worker.fixed-delay-ms:1000}"
    )
    public void poll() {
        if (fileHandler == null) {
            return;
        }
        try {
            runOnce(scheduledWorkerId);
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "组合报告worker单轮执行失败 category=POLL exceptionType={}",
                    exception.getClass().getSimpleName()
            );
        }
    }

    /** 关闭本worker独占的守护续租线程。 */
    @PreDestroy
    public void shutdown() {
        renewalScheduler.shutdownNow();
    }

    /** 领取由数据库条件UPDATE裁决，只有update=1的worker能得到任务。 */
    public Optional<CompositeReportTask> claim(String workerId) {
        return taskService.claimNext(workerId, leaseSeconds, scanLimit);
    }

    /** 仅当前worker持有活动运行态租约时才允许续租。 */
    public boolean renew(String taskId, String workerId) {
        return taskService.renewLease(taskId, workerId, leaseSeconds);
    }

    /**
     * 执行一个任务。生成前复核字段策略版本，重试始终使用同一受控目标路径。
     */
    public boolean runOnce(String workerId) {
        if (fileHandler == null) {
            return false;
        }
        Optional<CompositeReportTask> claimed = claim(workerId);
        if (claimed.isEmpty()) {
            return false;
        }
        CompositeReportTask task = claimed.orElseThrow();
        AtomicBoolean leaseLost = new AtomicBoolean(false);
        ScheduledFuture<?> renewal = null;
        try {
            int renewalPeriodSeconds = Math.max(1, leaseSeconds / 3);
            renewal = renewalScheduler.scheduleAtFixedRate(
                    () -> renewLease(task.getTaskId(), workerId, leaseLost),
                    renewalPeriodSeconds,
                    renewalPeriodSeconds,
                    TimeUnit.SECONDS
            );
            List<CompositeReportSection> sections = taskService.sections(task.getTaskId());
            PolicyState policyState = policyState(sections);
            if (leaseLost.get()) {
                return false;
            }
            if (policyState == PolicyState.CHANGED) {
                taskService.fail(
                        task.getTaskId(), workerId, POLICY_ERROR_CODE, POLICY_ERROR_MESSAGE
                );
                return false;
            }
            if (policyState == PolicyState.UNAVAILABLE) {
                return scheduleRetry(task, workerId);
            }
            String targetPath = targetPath(task);
            ArtifactMetadata artifact = fileHandler.generate(
                    task.getTaskId(), task.getFormat(), targetPath, sections
            );
            if (leaseLost.get()) {
                return false;
            }
            if (artifact == null || !targetPath.equals(artifact.storagePath())) {
                throw new IllegalStateException("报告处理器返回了非预期目标路径");
            }
            if (leaseLost.get()) {
                return false;
            }
            return taskService.complete(task, workerId, artifact);
        } catch (RuntimeException exception) {
            if (leaseLost.get()) {
                return false;
            }
            return scheduleRetry(task, workerId);
        } finally {
            if (renewal != null) {
                renewal.cancel(true);
            }
        }
    }

    private void renewLease(String taskId, String workerId, AtomicBoolean leaseLost) {
        if (leaseLost.get()) {
            return;
        }
        try {
            if (!renew(taskId, workerId)) {
                leaseLost.set(true);
            }
        } catch (RuntimeException exception) {
            leaseLost.set(true);
        }
    }

    private static ScheduledExecutorService newRenewalScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "composite-report-lease-renewal");
            thread.setDaemon(true);
            return thread;
        });
    }

    private PolicyState policyState(List<CompositeReportSection> sections) {
        if (sections == null || sections.isEmpty()) {
            return PolicyState.CHANGED;
        }
        try {
            for (CompositeReportSection section : sections) {
                if (section == null || section.getDatasetCode() == null
                        || section.getFieldPolicyChecksum() == null) {
                    return PolicyState.CHANGED;
                }
                ReportDataset current = datasetMapper.selectEnabledByCode(
                        section.getDatasetCode()
                );
                if (current == null || !Boolean.TRUE.equals(current.getEnabled())
                        || !Objects.equals(
                        section.getFieldPolicyChecksum(), current.getFieldPolicyChecksum()
                )) {
                    return PolicyState.CHANGED;
                }
            }
            return PolicyState.CURRENT;
        } catch (RuntimeException exception) {
            return PolicyState.UNAVAILABLE;
        }
    }

    private boolean scheduleRetry(CompositeReportTask task, String workerId) {
        taskService.retry(
                task.getTaskId(), workerId, SAFE_ERROR_CODE,
                SAFE_ERROR_MESSAGE, RETRY_DELAY_SECONDS
        );
        return false;
    }

    private String targetPath(CompositeReportTask task) {
        String taskId = task.getTaskId();
        if (taskId == null || !taskId.matches("[A-Za-z0-9_-]{1,32}")) {
            throw new IllegalStateException("报告任务ID不适合构造受控路径");
        }
        String extension = switch (Objects.toString(task.getFormat(), "")) {
            case "XLSX" -> "xlsx";
            case "DOCX" -> "docx";
            case "PDF" -> "pdf";
            default -> throw new IllegalStateException("报告格式不受支持");
        };
        return "reports/" + taskId + "/report." + extension;
    }

    /**
     * 后续存储实现必须在给定目标路径使用临时文件加原子替换，并只消费安全章节数据。
     */
    @FunctionalInterface
    public interface ReportFileHandler {
        ArtifactMetadata generate(
                String taskId,
                String format,
                String targetPath,
                List<CompositeReportSection> sections);
    }

    private enum PolicyState {
        CURRENT,
        CHANGED,
        UNAVAILABLE
    }
}
