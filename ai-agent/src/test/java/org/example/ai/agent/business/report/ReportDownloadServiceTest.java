package org.example.ai.agent.business.report;

import org.example.ai.agent.common.config.StorageProperties;
import org.example.ai.agent.common.file.SafeArtifactStorageService;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.report.entity.CompositeReportSection;
import org.example.ai.agent.business.report.entity.CompositeReportTask;
import org.example.ai.agent.business.report.mapper.CompositeReportSectionMapper;
import org.example.ai.agent.business.report.mapper.CompositeReportTaskMapper;
import org.example.ai.agent.business.snapshot.BusinessSnapshotAccessService;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportDownloadServiceTest {

    private static final String SHA = "a".repeat(64);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-08T02:00:00Z"), ZoneOffset.UTC
    );

    @TempDir
    Path temporaryDirectory;

    @Test
    void storageShouldGenerateControlledNormalizedPathAndRejectTraversal() throws Exception {
        StorageProperties properties = new StorageProperties();
        properties.setReportDir(temporaryDirectory.resolve("reports"));
        SafeArtifactStorageService storage = new SafeArtifactStorageService(properties);

        SafeArtifactStorageService.StoredArtifact artifact = storage.store(
                "reports/task-123/report.pdf", "safe-content".getBytes()
        );

        assertThat(artifact.relativePath()).isEqualTo("reports/task-123/report.pdf");
        assertThat(artifact.fileName()).isEqualTo("report.pdf");
        assertThat(artifact.fileSize()).isEqualTo(12L);
        assertThat(artifact.checksum()).hasSize(64);
        assertThat(storage.readVerified(artifact.relativePath(), 12L, artifact.checksum()))
                .isEqualTo("safe-content".getBytes());
        assertThatThrownBy(() -> storage.store("../escape.pdf", new byte[]{1}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.readVerified("../escape.pdf", 1L, "a".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.store(temporaryDirectory.resolve("absolute.pdf").toString(),
                new byte[]{1})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.store(".", new byte[]{1}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.store("reports/task\u0000/report.pdf", new byte[]{1}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void storageShouldRejectEmptyContentOverwriteAtomicallyAndCleanTemporarySibling()
            throws Exception {
        StorageProperties properties = new StorageProperties();
        Path root = temporaryDirectory.resolve("atomic-root");
        properties.setReportDir(root);
        SafeArtifactStorageService storage = new SafeArtifactStorageService(properties);

        assertThatThrownBy(() -> storage.store("reports/task-1/report.pdf", new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);

        storage.store("reports/task-1/report.pdf", "old".getBytes());
        SafeArtifactStorageService.StoredArtifact current = storage.store(
                "reports/task-1/report.pdf", "new-content".getBytes());

        assertThat(storage.readVerified(current.relativePath(), current.fileSize(), current.checksum()))
                .isEqualTo("new-content".getBytes());
        try (var siblings = java.nio.file.Files.list(
                root.resolve("reports/task-1"))) {
            assertThat(siblings.map(path -> path.getFileName().toString()).toList())
                    .containsExactly("report.pdf");
        }
    }

    @Test
    void storageShouldRejectSymbolicLinkWithoutCreatingOutsideDirectories() throws Exception {
        StorageProperties properties = new StorageProperties();
        Path root = temporaryDirectory.resolve("symlink-root");
        Path outside = temporaryDirectory.resolve("outside");
        java.nio.file.Files.createDirectories(root.resolve("reports"));
        java.nio.file.Files.createDirectories(outside);
        try {
            java.nio.file.Files.createSymbolicLink(root.resolve("reports/link"), outside);
        } catch (java.nio.file.FileSystemException | UnsupportedOperationException exception) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "当前文件系统不允许创建符号链接");
            return;
        }
        properties.setReportDir(root);
        SafeArtifactStorageService storage = new SafeArtifactStorageService(properties);

        assertThatThrownBy(() -> storage.store(
                "reports/link/escaped/report.pdf", new byte[]{1}))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("SYMLINK");
        assertThat(outside.resolve("escaped")).doesNotExist();
    }

    @Test
    void downloadShouldRequireOwnerAndReauthorizeEverySection() throws Exception {
        Fixture fixture = fixture();
        CompositeReportTask task = successfulTask();
        fixture.storage.store(task.getStoragePath(), "report-content".getBytes());
        when(fixture.taskMapper.selectByTaskId("task-1")).thenReturn(task);
        when(fixture.currentUser.getRequiredUserId()).thenReturn("user-1");
        when(fixture.currentUser.getRequiredAuthorization()).thenReturn("Bearer current");
        when(fixture.sectionMapper.selectByTaskId("task-1")).thenReturn(List.of(
                section("summary", SHA), section("cost", SHA)
        ));
        when(fixture.accessService.reauthorize(any())).thenReturn(Optional.of(
                new BusinessSnapshotAccessService.AccessGrant(1L, "b".repeat(64), SHA, 60)
        ));

        ReportDownloadService.DownloadArtifact download = fixture.service.download("task-1");

        assertThat(download.content()).isEqualTo("report-content".getBytes());
        assertThat(download.fileName()).isEqualTo("项目报告.pdf");
        verify(fixture.accessService, org.mockito.Mockito.times(2)).reauthorize(any());
    }

    @Test
    void oneRevokedSectionOrChangedFieldPolicyShouldDenyWholeFileGenerically() throws Exception {
        Fixture fixture = fixture();
        CompositeReportTask task = successfulTask();
        fixture.storage.store(task.getStoragePath(), "report-content".getBytes());
        when(fixture.taskMapper.selectByTaskId("task-1")).thenReturn(task);
        when(fixture.currentUser.getRequiredUserId()).thenReturn("user-1");
        when(fixture.currentUser.getRequiredAuthorization()).thenReturn("Bearer current");
        when(fixture.sectionMapper.selectByTaskId("task-1")).thenReturn(List.of(
                section("summary", SHA), section("cost", SHA)
        ));
        when(fixture.accessService.reauthorize(any()))
                .thenReturn(Optional.of(new BusinessSnapshotAccessService.AccessGrant(
                        1L, "b".repeat(64), SHA, 60)))
                .thenReturn(Optional.empty());

        assertGenericDenial(() -> fixture.service.download("task-1"));

        when(fixture.accessService.reauthorize(any())).thenReturn(Optional.of(
                new BusinessSnapshotAccessService.AccessGrant(1L, "b".repeat(64), "c".repeat(64), 60)
        ));
        assertGenericDenial(() -> fixture.service.download("task-1"));
    }

    @Test
    void ownerStillCannotDownloadExpiredNonSuccessfulOrTamperedArtifact() throws Exception {
        Fixture fixture = fixture();
        CompositeReportTask task = successfulTask();
        when(fixture.taskMapper.selectByTaskId("task-1")).thenReturn(task);
        when(fixture.currentUser.getRequiredUserId()).thenReturn("other-user");
        assertGenericDenial(() -> fixture.service.download("task-1"));

        when(fixture.currentUser.getRequiredUserId()).thenReturn("user-1");
        task.setExpiresAt(LocalDateTime.of(2026, 9, 8, 1, 59));
        assertGenericDenial(() -> fixture.service.download("task-1"));

        task.setExpiresAt(LocalDateTime.of(2026, 9, 9, 2, 0));
        task.setStatus("FAILED");
        assertGenericDenial(() -> fixture.service.download("task-1"));

        task.setStatus("PARTIAL_SUCCESS");
        fixture.storage.store(task.getStoragePath(), "wrong".getBytes());
        when(fixture.currentUser.getRequiredAuthorization()).thenReturn("Bearer current");
        when(fixture.sectionMapper.selectByTaskId("task-1"))
                .thenReturn(List.of(section("summary", SHA)));
        when(fixture.accessService.reauthorize(any())).thenReturn(Optional.of(
                new BusinessSnapshotAccessService.AccessGrant(1L, "b".repeat(64), SHA, 60)
        ));
        assertGenericDenial(() -> fixture.service.download("task-1"));
    }

    @Test
    void unsafeFileNameOrUnexpectedMimeTypeShouldBeDeniedGenerically() throws Exception {
        Fixture fixture = fixture();
        CompositeReportTask task = successfulTask();
        fixture.storage.store(task.getStoragePath(), "report-content".getBytes());
        when(fixture.taskMapper.selectByTaskId("task-1")).thenReturn(task);
        when(fixture.currentUser.getRequiredUserId()).thenReturn("user-1");
        when(fixture.currentUser.getRequiredAuthorization()).thenReturn("Bearer current");
        when(fixture.sectionMapper.selectByTaskId("task-1"))
                .thenReturn(List.of(section("summary", SHA)));
        when(fixture.accessService.reauthorize(any())).thenReturn(Optional.of(
                new BusinessSnapshotAccessService.AccessGrant(1L, "b".repeat(64), SHA, 60)
        ));

        task.setFileName("bad\r\nname.pdf");
        assertGenericDenial(() -> fixture.service.download("task-1"));

        task.setFileName("项目报告.pdf");
        task.setMimeType("text/html");
        assertGenericDenial(() -> fixture.service.download("task-1"));
    }

    @Test
    void authorizationProviderBusinessExceptionShouldStillBeMappedToGenericDenial()
            throws Exception {
        Fixture fixture = fixture();
        CompositeReportTask task = successfulTask();
        fixture.storage.store(task.getStoragePath(), "report-content".getBytes());
        when(fixture.taskMapper.selectByTaskId("task-1")).thenReturn(task);
        when(fixture.currentUser.getRequiredUserId()).thenReturn("user-1");
        when(fixture.currentUser.getRequiredAuthorization())
                .thenThrow(new BusinessException(418, "上游泄露的内部授权错误"));

        assertThatThrownBy(() -> fixture.service.download("task-1"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(404);
                    assertThat(exception.getMessage()).isEqualTo("报告任务不存在或无权访问");
                });
    }

    @Test
    void downloadArtifactShouldExposeLengthStreamBytesAndKeepTestCopiesIsolated()
            throws Exception {
        byte[] owned = "stream-content".getBytes();
        ReportDownloadService.DownloadArtifact artifact =
                ReportDownloadService.DownloadArtifact.takeOwnership(
                        owned, "report.pdf", "application/pdf");
        byte[] testCopy = artifact.content();
        testCopy[0] = 'X';
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        artifact.writeTo(output);

        assertThat(artifact.contentLength()).isEqualTo(14L);
        assertThat(output.toByteArray()).isEqualTo("stream-content".getBytes());
    }

    @Test
    void statusShouldDynamicallyExposeExpiredAndCancelShouldUseConditionalUpdate() {
        Fixture fixture = fixture();
        CompositeReportTask task = successfulTask();
        task.setExpiresAt(LocalDateTime.of(2026, 9, 8, 2, 0));
        when(fixture.taskMapper.selectByTaskId("task-1")).thenReturn(task);
        when(fixture.currentUser.getRequiredUserId()).thenReturn("user-1");

        assertThat(fixture.service.status("task-1").status()).isEqualTo("EXPIRED");

        when(fixture.taskMapper.cancelIfCancellable("task-1", "user-1")).thenReturn(1);
        fixture.service.cancel("task-1");
        verify(fixture.taskMapper).cancelIfCancellable("task-1", "user-1");
    }

    @Test
    void retentionShouldDeletePhysicalFileBeforeExpiringMetadataAndContinueAfterFailure()
            throws Exception {
        CompositeReportTaskMapper mapper = mock(CompositeReportTaskMapper.class);
        SafeArtifactStorageService storage = mock(SafeArtifactStorageService.class);
        CompositeReportTask first = successfulTask();
        first.setTaskId("task-failed-delete");
        first.setStoragePath("reports/task-failed-delete/report.pdf");
        CompositeReportTask second = successfulTask();
        second.setTaskId("task-deleted");
        second.setStoragePath("reports/task-deleted/report.pdf");
        when(mapper.selectExpiredArtifactCandidates(20)).thenReturn(List.of(first, second));
        when(storage.delete(first.getStoragePath())).thenThrow(new java.io.IOException("secret path"));
        when(storage.delete(second.getStoragePath())).thenReturn(true);
        when(mapper.markExpiredAndClearArtifact(
                "task-deleted", second.getStoragePath())).thenReturn(1);
        CompositeReportSectionMapper sectionMapper = mock(CompositeReportSectionMapper.class);
        ReportRetentionJob job = new ReportRetentionJob(mapper, sectionMapper, storage, 20);

        ReportRetentionJob.CleanupResult result = job.cleanupBatch();

        assertThat(result).isEqualTo(new ReportRetentionJob.CleanupResult(2, 1, 1));
        verify(mapper, org.mockito.Mockito.never())
                .markExpiredAndClearArtifact(
                        "task-failed-delete", first.getStoragePath());
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(storage, mapper, sectionMapper);
        order.verify(storage).delete(second.getStoragePath());
        order.verify(mapper).markExpiredAndClearArtifact(
                "task-deleted", second.getStoragePath());
        order.verify(sectionMapper).clearSnapshotReferences("task-deleted");
    }

    @Test
    void retentionShouldRetrySectionCleanupAndContinueAfterOneSectionFailure() throws Exception {
        CompositeReportTaskMapper mapper = mock(CompositeReportTaskMapper.class);
        CompositeReportSectionMapper sectionMapper = mock(CompositeReportSectionMapper.class);
        SafeArtifactStorageService storage = mock(SafeArtifactStorageService.class);
        CompositeReportTask first = successfulTask();
        first.setTaskId("task-section-failed");
        first.setStoragePath(null);
        CompositeReportTask second = successfulTask();
        second.setTaskId("task-section-cleared");
        second.setStoragePath(null);
        when(mapper.selectExpiredArtifactCandidates(20)).thenReturn(List.of(first, second));
        when(mapper.markExpiredAndClearArtifact("task-section-failed", null)).thenReturn(1);
        when(mapper.markExpiredAndClearArtifact("task-section-cleared", null)).thenReturn(1);
        when(sectionMapper.clearSnapshotReferences("task-section-failed"))
                .thenThrow(new IllegalStateException("database secret"));
        ReportRetentionJob job = new ReportRetentionJob(mapper, sectionMapper, storage, 20);

        ReportRetentionJob.CleanupResult result = job.cleanupBatch();

        assertThat(result).isEqualTo(new ReportRetentionJob.CleanupResult(2, 1, 1));
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(mapper, sectionMapper);
        order.verify(mapper).markExpiredAndClearArtifact("task-section-failed", null);
        order.verify(sectionMapper).clearSnapshotReferences("task-section-failed");
        order.verify(mapper).markExpiredAndClearArtifact("task-section-cleared", null);
        order.verify(sectionMapper).clearSnapshotReferences("task-section-cleared");
        verify(storage, org.mockito.Mockito.never()).delete(any());
    }

    private Fixture fixture() {
        StorageProperties properties = new StorageProperties();
        properties.setReportDir(temporaryDirectory.resolve("report-root"));
        SafeArtifactStorageService storage = new SafeArtifactStorageService(properties);
        CompositeReportTaskMapper taskMapper = mock(CompositeReportTaskMapper.class);
        CompositeReportSectionMapper sectionMapper = mock(CompositeReportSectionMapper.class);
        BusinessSnapshotAccessService accessService = mock(BusinessSnapshotAccessService.class);
        CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
        return new Fixture(taskMapper, sectionMapper, accessService, currentUser, storage,
                new ReportDownloadService(taskMapper, sectionMapper, accessService, currentUser, storage, CLOCK));
    }

    private CompositeReportTask successfulTask() {
        CompositeReportTask task = new CompositeReportTask();
        task.setTaskId("task-1");
        task.setUserId("user-1");
        task.setSessionId("session-1");
        task.setSubjectType(BusinessSubjectType.PROJECT.name());
        task.setSubjectId("project-1");
        task.setFormat("PDF");
        task.setStatus("SUCCESS");
        task.setStoragePath("reports/task-1/report.pdf");
        task.setFileName("项目报告.pdf");
        task.setMimeType("application/pdf");
        task.setFileSize(14L);
        task.setChecksum("362636f5a34836946783f440c823fd9b5604a42a3deebe7b9bb97dfc1084f6ed");
        task.setExpiresAt(LocalDateTime.of(2026, 9, 9, 2, 0));
        return task;
    }

    private CompositeReportSection section(String datasetCode, String checksum) {
        CompositeReportSection section = new CompositeReportSection();
        section.setDatasetCode(datasetCode);
        section.setFieldPolicyChecksum(checksum);
        section.setStatus("REUSED");
        return section;
    }

    private void assertGenericDenial(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOf(BusinessException.class)
                .hasMessage("报告任务不存在或无权访问");
    }

    private record Fixture(
            CompositeReportTaskMapper taskMapper,
            CompositeReportSectionMapper sectionMapper,
            BusinessSnapshotAccessService accessService,
            CurrentUserProvider currentUser,
            SafeArtifactStorageService storage,
            ReportDownloadService service) {
    }
}
