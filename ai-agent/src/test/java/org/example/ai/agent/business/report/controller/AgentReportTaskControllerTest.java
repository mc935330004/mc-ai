package org.example.ai.agent.business.report.controller;

import org.example.ai.agent.business.report.ReportDownloadService;
import org.example.ai.agent.business.report.ReportDownloadService.DownloadArtifact;
import org.example.ai.agent.business.report.ReportDownloadService.TaskView;
import org.junit.jupiter.api.Test;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentReportTaskControllerTest {

    @Test
    void shouldExposeExactlyStatusDownloadAndCancelEndpoints() throws Exception {
        RequestMapping root = AgentReportTaskController.class.getAnnotation(RequestMapping.class);
        assertThat(root.value()).containsExactly("/api/agent/report-tasks");

        Method status = AgentReportTaskController.class.getMethod("status", String.class);
        Method download = AgentReportTaskController.class.getMethod("download", String.class);
        Method cancel = AgentReportTaskController.class.getMethod("cancel", String.class);

        assertThat(status.getAnnotation(GetMapping.class).value()).containsExactly("/{taskId}");
        assertThat(download.getAnnotation(GetMapping.class).value())
                .containsExactly("/{taskId}/download");
        assertThat(cancel.getAnnotation(PostMapping.class).value())
                .containsExactly("/{taskId}/cancel");
    }

    @Test
    void downloadShouldUseUtf8AttachmentAndNoStore() {
        ReportDownloadService service = mock(ReportDownloadService.class);
        when(service.download("task-1")).thenReturn(new DownloadArtifact(
                "content".getBytes(), "项目 月报.pdf", "application/pdf"
        ));
        AgentReportTaskController controller = new AgentReportTaskController(service);

        ResponseEntity<StreamingResponseBody> response = controller.download("task-1");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            response.getBody().writeTo(output);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }

        assertThat(output.toByteArray()).isEqualTo("content".getBytes());
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment")
                .contains("filename*=UTF-8''")
                .doesNotContain("项目");
        assertThat(response.getHeaders().getCacheControl())
                .isEqualTo(CacheControl.noStore().getHeaderValue());
        assertThat(response.getHeaders().getContentLength()).isEqualTo(7L);
    }

    @Test
    void statusAndCancelShouldDelegateWithoutReturningPermanentUrl() {
        ReportDownloadService service = mock(ReportDownloadService.class);
        TaskView view = new TaskView(
                "task-1", "PENDING", "PDF", false,
                "a".repeat(64), java.time.LocalDateTime.of(2026, 9, 17, 10, 0),
                null, null, null, null, null, null, java.util.List.of()
        );
        when(service.status("task-1")).thenReturn(view);
        AgentReportTaskController controller = new AgentReportTaskController(service);

        ResponseEntity<TaskView> status = controller.status("task-1");
        assertThat(status.getBody()).isSameAs(view);
        assertThat(status.getBody().contentVersion()).hasSize(64);
        assertThat(status.getBody().frozenAt())
                .isEqualTo(java.time.LocalDateTime.of(2026, 9, 17, 10, 0));
        assertThat(status.getHeaders().getCacheControl())
                .isEqualTo(CacheControl.noStore().getHeaderValue());
        assertThat(controller.cancel("task-1").getStatusCode().value()).isEqualTo(204);
        verify(service).cancel("task-1");
        assertThat(TaskView.class.getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("url", "downloadUrl", "storagePath", "checksum");
    }

    @Test
    void cancelSqlShouldCoverAllConfirmedCancellableStates() throws Exception {
        String xml;
        try (var input = getClass().getClassLoader()
                .getResourceAsStream("mapper/CompositeReportTaskMapper.xml")) {
            assertThat(input).isNotNull();
            xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(xml).contains("status IN ('PENDING', 'RETRY', 'RUNNING', 'COLLECTING', 'RENDERING')");
    }

    @Test
    void retentionSqlShouldRetryTasksWithSectionReferencesAndClearOnlyExpiredTaskSections()
            throws Exception {
        String taskXml;
        try (var input = getClass().getClassLoader()
                .getResourceAsStream("mapper/CompositeReportTaskMapper.xml")) {
            assertThat(input).isNotNull();
            taskXml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        String sectionXml;
        try (var input = getClass().getClassLoader()
                .getResourceAsStream("mapper/CompositeReportSectionMapper.xml")) {
            assertThat(input).isNotNull();
            sectionXml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(taskXml)
                .contains("storage_path IS NOT NULL")
                .contains("snapshot_id IS NOT NULL")
                .contains("EXISTS");
        assertThat(sectionXml)
                .contains("clearSnapshotReferences")
                .contains("JOIN ai_composite_report_task")
                .contains("expires_at &lt;= CURRENT_TIMESTAMP(3)")
                .contains("status = 'EXPIRED'")
                .contains("snapshot_id = NULL");
    }
}
