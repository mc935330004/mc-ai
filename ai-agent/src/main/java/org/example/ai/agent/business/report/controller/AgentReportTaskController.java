package org.example.ai.agent.business.report.controller;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.business.report.ReportDownloadService;
import org.example.ai.agent.business.report.ReportDownloadService.DownloadArtifact;
import org.example.ai.agent.business.report.ReportDownloadService.TaskView;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;

/**
 * 组合报告任务协议入口，只负责状态、下载响应和取消命令转换。
 */
@RestController
@RequestMapping("/api/agent/report-tasks")
@RequiredArgsConstructor
public class AgentReportTaskController {

    private final ReportDownloadService reportDownloadService;

    /** 获取当前用户拥有的报告任务安全状态。 */
    @GetMapping("/{taskId}")
    public ResponseEntity<TaskView> status(@PathVariable String taskId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(reportDownloadService.status(taskId));
    }

    /** 下载经过重新授权和完整性校验的报告字节。 */
    @GetMapping("/{taskId}/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable String taskId) {
        DownloadArtifact artifact = reportDownloadService.download(taskId);
        String disposition = ContentDisposition.attachment()
                .filename(artifact.fileName(), StandardCharsets.UTF_8)
                .build().toString();
        StreamingResponseBody body = artifact::writeTo;
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(artifact.mimeType()))
                .contentLength(artifact.contentLength())
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .body(body);
    }

    /** 取消仍处于可取消状态的当前用户报告任务。 */
    @PostMapping("/{taskId}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable String taskId) {
        reportDownloadService.cancel(taskId);
        return ResponseEntity.noContent().build();
    }
}
