package org.example.ai.agent.business.report;

import org.example.ai.agent.business.dataset.entity.ReportDataset;
import org.example.ai.agent.business.dataset.mapper.ReportDatasetMapper;
import org.example.ai.agent.business.report.entity.CompositeReportSection;
import org.example.ai.agent.business.report.entity.CompositeReportTask;
import org.example.ai.agent.business.report.mapper.CompositeReportSectionMapper;
import org.example.ai.agent.business.report.mapper.CompositeReportTaskMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompositeReportWorkerTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-08T02:00:00Z"), ZoneOffset.UTC
    );

    @Test
    void onlyWorkerWinningConditionalUpdateShouldClaimCandidate() {
        CompositeReportTaskMapper taskMapper = mock(CompositeReportTaskMapper.class);
        CompositeReportSectionMapper sectionMapper = mock(CompositeReportSectionMapper.class);
        CompositeReportTask task = task("task-1", "PENDING");
        when(taskMapper.recoverExpiredLeases()).thenReturn(0);
        when(taskMapper.selectClaimCandidateIds(10)).thenReturn(List.of("task-1"));
        when(taskMapper.tryClaim("task-1", "worker-a", 60)).thenReturn(1);
        when(taskMapper.tryClaim("task-1", "worker-b", 60)).thenReturn(0);
        when(taskMapper.selectByTaskId("task-1")).thenReturn(task);
        CompositeReportTaskService service = service(taskMapper, sectionMapper);

        Optional<CompositeReportTask> first = service.claimNext("worker-a", 60, 10);
        Optional<CompositeReportTask> second = service.claimNext("worker-b", 60, 10);

        assertThat(first).containsSame(task);
        assertThat(second).isEmpty();
        verify(taskMapper, times(2)).recoverExpiredLeases();
    }

    @Test
    void recoveryMustRunBeforePendingOrRetryClaim() {
        CompositeReportTaskMapper taskMapper = mock(CompositeReportTaskMapper.class);
        CompositeReportSectionMapper sectionMapper = mock(CompositeReportSectionMapper.class);
        when(taskMapper.recoverExpiredLeases()).thenReturn(1);
        when(taskMapper.selectClaimCandidateIds(10)).thenReturn(List.of());
        CompositeReportTaskService service = service(taskMapper, sectionMapper);

        assertThat(service.claimNext("worker-a", 60, 10)).isEmpty();

        InOrder order = inOrder(taskMapper);
        order.verify(taskMapper).recoverExpiredLeases();
        order.verify(taskMapper).selectClaimCandidateIds(10);
    }

    @Test
    void leaseRenewalShouldRequireMatchingWorkerAndRunnableStateInXml() throws Exception {
        CompositeReportTaskMapper taskMapper = mock(CompositeReportTaskMapper.class);
        CompositeReportSectionMapper sectionMapper = mock(CompositeReportSectionMapper.class);
        when(taskMapper.renewLease("task-1", "worker-a", 60)).thenReturn(1);
        when(taskMapper.renewLease("task-1", "worker-b", 60)).thenReturn(0);
        CompositeReportTaskService service = service(taskMapper, sectionMapper);

        assertThat(service.renewLease("task-1", "worker-a", 60)).isTrue();
        assertThat(service.renewLease("task-1", "worker-b", 60)).isFalse();

        String xml = resource("mapper/CompositeReportTaskMapper.xml");
        assertThat(xml)
                .contains("worker_id = #{workerId}")
                .contains("status IN ('RUNNING', 'COLLECTING', 'RENDERING')")
                .contains("lease_until &gt; CURRENT_TIMESTAMP(3)");
    }

    @Test
    void recoveryAndClaimSqlShouldProtectActiveLeaseAndTerminalStates() throws Exception {
        String xml = resource("mapper/CompositeReportTaskMapper.xml");
        String recoverySql = xml.substring(
                xml.indexOf("<update id=\"recoverExpiredLeases\">"),
                xml.indexOf("</update>", xml.indexOf("<update id=\"recoverExpiredLeases\">"))
        );

        assertThat(xml)
                .contains("status IN ('RUNNING', 'COLLECTING', 'RENDERING')")
                .contains("lease_until &lt;= CURRENT_TIMESTAMP(3)")
                .contains("status IN ('PENDING', 'RETRY')")
                .contains("attempt_count &lt; max_attempts")
                .contains("ELSE 'FAILED'")
                .contains("completed_at = CASE")
                .contains("expires_at &gt; CURRENT_TIMESTAMP(3)")
                .doesNotContain("status IN ('SUCCESS', 'PARTIAL_SUCCESS', 'FAILED', 'CANCELLED', 'EXPIRED')");
        assertThat(recoverySql).doesNotContain("expires_at");
    }

    @Test
    void changedFieldPolicyShouldFailClosedBeforeRenderingIncludingDeniedSection() {
        CompositeReportTaskMapper taskMapper = mock(CompositeReportTaskMapper.class);
        CompositeReportSectionMapper sectionMapper = mock(CompositeReportSectionMapper.class);
        ReportDatasetMapper datasetMapper = mock(ReportDatasetMapper.class);
        CompositeReportWorker.ReportFileHandler handler = mock(
                CompositeReportWorker.ReportFileHandler.class
        );
        CompositeReportTask task = task("task-1", "RUNNING");
        task.setWorkerId("worker-a");
        task.setFormat("PDF");
        CompositeReportSection summary = section("task-1", "summary", "a".repeat(64));
        CompositeReportSection denied = section("task-1", "salary", "b".repeat(64));
        denied.setStatus("DENIED");
        when(taskMapper.recoverExpiredLeases()).thenReturn(0);
        when(taskMapper.selectClaimCandidateIds(10)).thenReturn(List.of("task-1"));
        when(taskMapper.tryClaim("task-1", "worker-a", 60)).thenReturn(1);
        when(taskMapper.selectByTaskId("task-1")).thenReturn(task);
        when(sectionMapper.selectByTaskId("task-1")).thenReturn(List.of(summary, denied));
        when(datasetMapper.selectEnabledByCode("summary")).thenReturn(dataset("summary", "a"));
        when(datasetMapper.selectEnabledByCode("salary")).thenReturn(dataset("salary", "a"));
        when(taskMapper.markFailed(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1);
        CompositeReportWorker worker = new CompositeReportWorker(
                service(taskMapper, sectionMapper), datasetMapper, handler, 60, 10
        );

        assertThat(worker.runOnce("worker-a")).isFalse();

        verify(datasetMapper).selectEnabledByCode("summary");
        verify(datasetMapper).selectEnabledByCode("salary");
        verify(handler, never()).generate(anyString(), anyString(), anyString(), any());
        verify(taskMapper).markFailed(
                "task-1", "worker-a", "REPORT_POLICY_CHANGED", "报告字段策略已变更，请重新创建"
        );
    }

    @Test
    void crashWindowRetryShouldReuseSameDeterministicTargetPath() {
        CompositeReportTaskMapper taskMapper = mock(CompositeReportTaskMapper.class);
        CompositeReportSectionMapper sectionMapper = mock(CompositeReportSectionMapper.class);
        ReportDatasetMapper datasetMapper = mock(ReportDatasetMapper.class);
        CompositeReportTask task = task("task-1", "RUNNING");
        task.setWorkerId("worker-a");
        task.setFormat("PDF");
        CompositeReportSection section = section("task-1", "summary", "a".repeat(64));
        List<String> targetPaths = new java.util.ArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        CompositeReportWorker.ReportFileHandler handler = (taskId, format, targetPath, sections) -> {
            targetPaths.add(targetPath);
            if (attempts.getAndIncrement() == 0) {
                /* 模拟目标文件已经原子发布，但worker在持久化元数据前崩溃。 */
                throw new IllegalStateException("crash after target publish");
            }
            return new CompositeReportTaskService.ArtifactMetadata(
                    targetPath, "report.pdf", "application/pdf", 128L, "a".repeat(64)
            );
        };
        when(taskMapper.recoverExpiredLeases()).thenReturn(0);
        when(taskMapper.selectClaimCandidateIds(10)).thenReturn(List.of("task-1"));
        when(taskMapper.tryClaim("task-1", "worker-a", 60)).thenReturn(1);
        when(taskMapper.selectByTaskId("task-1")).thenReturn(task);
        when(sectionMapper.selectByTaskId("task-1")).thenReturn(List.of(section));
        when(datasetMapper.selectEnabledByCode("summary")).thenReturn(dataset("summary", "a"));
        when(taskMapper.markRetry(anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(1);
        when(taskMapper.completeWithArtifact(
                anyString(), anyString(), anyBoolean(), anyString(), anyString(),
                anyString(), anyLong(), anyString()
        )).thenReturn(1);
        CompositeReportWorker worker = new CompositeReportWorker(
                service(taskMapper, sectionMapper), datasetMapper, handler, 60, 10
        );

        assertThat(worker.runOnce("worker-a")).isFalse();
        assertThat(worker.runOnce("worker-a")).isTrue();

        assertThat(targetPaths).containsExactly(
                "reports/task-1/report.pdf", "reports/task-1/report.pdf"
        );
        assertThat(targetPaths.stream().distinct()).containsExactly("reports/task-1/report.pdf");
        verify(taskMapper).markRetry(
                "task-1", "worker-a", "REPORT_GENERATION_FAILED", "报告文件生成失败", 30
        );
    }

    @Test
    void sectionLookupFailureShouldReleaseLeaseThroughSafeRetry() {
        CompositeReportTaskMapper taskMapper = mock(CompositeReportTaskMapper.class);
        CompositeReportSectionMapper sectionMapper = mock(CompositeReportSectionMapper.class);
        ReportDatasetMapper datasetMapper = mock(ReportDatasetMapper.class);
        CompositeReportWorker.ReportFileHandler handler = mock(
                CompositeReportWorker.ReportFileHandler.class
        );
        CompositeReportTask task = task("task-1", "RUNNING");
        task.setFormat("PDF");
        when(taskMapper.selectClaimCandidateIds(10)).thenReturn(List.of("task-1"));
        when(taskMapper.tryClaim("task-1", "worker-a", 60)).thenReturn(1);
        when(taskMapper.selectByTaskId("task-1")).thenReturn(task);
        when(sectionMapper.selectByTaskId("task-1"))
                .thenThrow(new IllegalStateException("database unavailable"));
        when(taskMapper.markRetry(anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(1);
        CompositeReportWorker worker = new CompositeReportWorker(
                service(taskMapper, sectionMapper), datasetMapper, handler, 60, 10
        );

        assertThat(worker.runOnce("worker-a")).isFalse();

        verify(taskMapper).markRetry(
                "task-1", "worker-a", "REPORT_GENERATION_FAILED", "报告文件生成失败", 30
        );
        verify(handler, never()).generate(anyString(), anyString(), anyString(), any());
    }

    @Test
    void policyLookupUnavailableShouldRetryInsteadOfMarkingTerminalFailure() {
        CompositeReportTaskMapper taskMapper = mock(CompositeReportTaskMapper.class);
        CompositeReportSectionMapper sectionMapper = mock(CompositeReportSectionMapper.class);
        ReportDatasetMapper datasetMapper = mock(ReportDatasetMapper.class);
        CompositeReportWorker.ReportFileHandler handler = mock(
                CompositeReportWorker.ReportFileHandler.class
        );
        CompositeReportTask task = task("task-1", "RUNNING");
        task.setFormat("PDF");
        CompositeReportSection section = section("task-1", "summary", "a".repeat(64));
        when(taskMapper.selectClaimCandidateIds(10)).thenReturn(List.of("task-1"));
        when(taskMapper.tryClaim("task-1", "worker-a", 60)).thenReturn(1);
        when(taskMapper.selectByTaskId("task-1")).thenReturn(task);
        when(sectionMapper.selectByTaskId("task-1")).thenReturn(List.of(section));
        when(datasetMapper.selectEnabledByCode("summary"))
                .thenThrow(new IllegalStateException("database unavailable"));
        when(taskMapper.markRetry(anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(1);
        CompositeReportWorker worker = new CompositeReportWorker(
                service(taskMapper, sectionMapper), datasetMapper, handler, 60, 10
        );

        assertThat(worker.runOnce("worker-a")).isFalse();

        verify(taskMapper).markRetry(
                "task-1", "worker-a", "REPORT_GENERATION_FAILED", "报告文件生成失败", 30
        );
        verify(taskMapper, never()).markFailed(anyString(), anyString(), anyString(), anyString());
        verify(handler, never()).generate(anyString(), anyString(), anyString(), any());
    }

    @Test
    void blockingHandlerShouldRenewLeaseBeforeCompletion() throws Exception {
        CompositeReportTaskMapper taskMapper = mock(CompositeReportTaskMapper.class);
        CompositeReportSectionMapper sectionMapper = mock(CompositeReportSectionMapper.class);
        ReportDatasetMapper datasetMapper = mock(ReportDatasetMapper.class);
        CompositeReportTask task = task("task-1", "RUNNING");
        task.setFormat("PDF");
        CompositeReportSection section = section("task-1", "summary", "a".repeat(64));
        CountDownLatch renewed = new CountDownLatch(1);
        when(taskMapper.selectClaimCandidateIds(10)).thenReturn(List.of("task-1"));
        when(taskMapper.tryClaim("task-1", "worker-a", 3)).thenReturn(1);
        when(taskMapper.selectByTaskId("task-1")).thenReturn(task);
        when(sectionMapper.selectByTaskId("task-1")).thenReturn(List.of(section));
        when(datasetMapper.selectEnabledByCode("summary")).thenReturn(dataset("summary", "a"));
        when(taskMapper.renewLease("task-1", "worker-a", 3)).thenAnswer(invocation -> {
            renewed.countDown();
            return 1;
        });
        when(taskMapper.completeWithArtifact(
                anyString(), anyString(), anyBoolean(), anyString(), anyString(),
                anyString(), anyLong(), anyString()
        )).thenReturn(1);
        CompositeReportWorker.ReportFileHandler handler = (taskId, format, target, sections) -> {
            if (!await(renewed)) {
                throw new IllegalStateException("续租未执行");
            }
            return new CompositeReportTaskService.ArtifactMetadata(
                    target, "report.pdf", "application/pdf", 128L, "a".repeat(64)
            );
        };
        ScheduledExecutorService renewalScheduler = Executors.newSingleThreadScheduledExecutor();
        ExecutorService runner = Executors.newSingleThreadExecutor();
        CompositeReportWorker worker = new CompositeReportWorker(
                service(taskMapper, sectionMapper), datasetMapper, handler, 3, 10,
                renewalScheduler
        );
        try {
            Future<Boolean> result = runner.submit(() -> worker.runOnce("worker-a"));

            assertThat(renewed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(result.get(2, TimeUnit.SECONDS)).isTrue();
            verify(taskMapper).renewLease("task-1", "worker-a", 3);
            verify(taskMapper).completeWithArtifact(
                    anyString(), anyString(), anyBoolean(), anyString(), anyString(),
                    anyString(), anyLong(), anyString()
            );
        } finally {
            worker.shutdown();
            runner.shutdownNow();
        }
    }

    @Test
    void blockingPolicyLookupShouldAlreadyBeProtectedByLeaseRenewal() throws Exception {
        CompositeReportTaskMapper taskMapper = mock(CompositeReportTaskMapper.class);
        CompositeReportSectionMapper sectionMapper = mock(CompositeReportSectionMapper.class);
        ReportDatasetMapper datasetMapper = mock(ReportDatasetMapper.class);
        CompositeReportTask task = task("task-1", "RUNNING");
        task.setFormat("PDF");
        CompositeReportSection section = section("task-1", "summary", "a".repeat(64));
        CountDownLatch renewed = new CountDownLatch(1);
        when(taskMapper.selectClaimCandidateIds(10)).thenReturn(List.of("task-1"));
        when(taskMapper.tryClaim("task-1", "worker-a", 3)).thenReturn(1);
        when(taskMapper.selectByTaskId("task-1")).thenReturn(task);
        when(sectionMapper.selectByTaskId("task-1")).thenReturn(List.of(section));
        when(taskMapper.renewLease("task-1", "worker-a", 3)).thenAnswer(invocation -> {
            renewed.countDown();
            return 1;
        });
        when(datasetMapper.selectEnabledByCode("summary")).thenAnswer(invocation -> {
            if (!await(renewed)) {
                throw new IllegalStateException("策略复核期间未续租");
            }
            return dataset("summary", "a");
        });
        CompositeReportWorker.ReportFileHandler handler = (taskId, format, target, sections) ->
                new CompositeReportTaskService.ArtifactMetadata(
                        target, "report.pdf", "application/pdf", 128L, "a".repeat(64)
                );
        when(taskMapper.completeWithArtifact(
                anyString(), anyString(), anyBoolean(), anyString(), anyString(),
                anyString(), anyLong(), anyString()
        )).thenReturn(1);
        ScheduledExecutorService renewalScheduler = Executors.newSingleThreadScheduledExecutor();
        ExecutorService runner = Executors.newSingleThreadExecutor();
        CompositeReportWorker worker = new CompositeReportWorker(
                service(taskMapper, sectionMapper), datasetMapper, handler, 3, 10,
                renewalScheduler
        );
        try {
            Future<Boolean> result = runner.submit(() -> worker.runOnce("worker-a"));

            assertThat(renewed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(result.get(2, TimeUnit.SECONDS)).isTrue();
            verify(taskMapper).renewLease("task-1", "worker-a", 3);
        } finally {
            worker.shutdown();
            runner.shutdownNow();
        }
    }

    @Test
    void lostLeaseDuringHandlerShouldNeverCompleteTask() throws Exception {
        CompositeReportTaskMapper taskMapper = mock(CompositeReportTaskMapper.class);
        CompositeReportSectionMapper sectionMapper = mock(CompositeReportSectionMapper.class);
        ReportDatasetMapper datasetMapper = mock(ReportDatasetMapper.class);
        CompositeReportTask task = task("task-1", "RUNNING");
        task.setFormat("PDF");
        CompositeReportSection section = section("task-1", "summary", "a".repeat(64));
        CountDownLatch renewalRejected = new CountDownLatch(1);
        when(taskMapper.selectClaimCandidateIds(10)).thenReturn(List.of("task-1"));
        when(taskMapper.tryClaim("task-1", "worker-a", 3)).thenReturn(1);
        when(taskMapper.selectByTaskId("task-1")).thenReturn(task);
        when(sectionMapper.selectByTaskId("task-1")).thenReturn(List.of(section));
        when(datasetMapper.selectEnabledByCode("summary")).thenReturn(dataset("summary", "a"));
        when(taskMapper.renewLease("task-1", "worker-a", 3)).thenAnswer(invocation -> {
            renewalRejected.countDown();
            return 0;
        });
        CompositeReportWorker.ReportFileHandler handler = (taskId, format, target, sections) -> {
            if (!await(renewalRejected)) {
                throw new IllegalStateException("续租未执行");
            }
            return new CompositeReportTaskService.ArtifactMetadata(
                    target, "report.pdf", "application/pdf", 128L, "a".repeat(64)
            );
        };
        ScheduledExecutorService renewalScheduler = Executors.newSingleThreadScheduledExecutor();
        ExecutorService runner = Executors.newSingleThreadExecutor();
        CompositeReportWorker worker = new CompositeReportWorker(
                service(taskMapper, sectionMapper), datasetMapper, handler, 3, 10,
                renewalScheduler
        );
        try {
            Future<Boolean> result = runner.submit(() -> worker.runOnce("worker-a"));

            assertThat(renewalRejected.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(result.get(2, TimeUnit.SECONDS)).isFalse();
            verify(taskMapper, never()).completeWithArtifact(
                    anyString(), anyString(), anyBoolean(), anyString(), anyString(),
                    anyString(), anyLong(), anyString()
            );
        } finally {
            worker.shutdown();
            runner.shutdownNow();
        }
    }

    @Test
    void scheduledPollShouldContainSingleRunFailure() {
        CompositeReportTaskService taskService = mock(CompositeReportTaskService.class);
        ReportDatasetMapper datasetMapper = mock(ReportDatasetMapper.class);
        CompositeReportWorker.ReportFileHandler handler = mock(
                CompositeReportWorker.ReportFileHandler.class
        );
        ScheduledExecutorService renewalScheduler = mock(ScheduledExecutorService.class);
        when(taskService.claimNext(anyString(), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("database unavailable"));
        CompositeReportWorker worker = new CompositeReportWorker(
                taskService, datasetMapper, handler, 3, 10, renewalScheduler
        );

        assertThatCode(worker::poll).doesNotThrowAnyException();
    }

    private CompositeReportTaskService service(
            CompositeReportTaskMapper taskMapper,
            CompositeReportSectionMapper sectionMapper) {
        return new CompositeReportTaskService(taskMapper, sectionMapper, CLOCK);
    }

    private CompositeReportTask task(String taskId, String status) {
        CompositeReportTask task = new CompositeReportTask();
        task.setTaskId(taskId);
        task.setStatus(status);
        task.setDataComplete(false);
        return task;
    }

    private CompositeReportSection section(
            String taskId,
            String datasetCode,
            String fieldPolicyChecksum) {
        CompositeReportSection section = new CompositeReportSection();
        section.setTaskId(taskId);
        section.setDatasetCode(datasetCode);
        section.setFieldPolicyChecksum(fieldPolicyChecksum);
        section.setStatus("REUSED");
        return section;
    }

    private ReportDataset dataset(String datasetCode, String checksumSeed) {
        ReportDataset dataset = new ReportDataset();
        dataset.setDatasetCode(datasetCode);
        dataset.setEnabled(true);
        dataset.setFieldPolicyChecksum(checksumSeed.repeat(64));
        return dataset;
    }

    private String resource(String path) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private boolean await(CountDownLatch latch) {
        try {
            return latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待续租时被中断", exception);
        }
    }
}
