package org.example.ai.agent.chat.protocol.response;

import org.example.ai.agent.common.enums.protocol.PresentationMode;
import org.example.ai.agent.common.enums.protocol.ResponseStatus;

import java.util.List;

/**
 * REPORT模式统一响应。
 *
 * 报告由章节组织Block，
 * CHAT和REPORT共用同一套Block协议。
 */
public record ReportSchema(
        int schemaVersion,
        String responseId,
        String runId,
        String conversationId,
        PresentationMode mode,
        ResponseStatus status,
        boolean dataComplete,
        String reportId,
        String reportType,
        String queryType,
        String title,
        String subtitle,
        List<ReportSection> sections,
        List<ResponseReference> references,
        ResponseMeta meta)
        implements ResponseDocument {

    public ReportSchema {
        schemaVersion = ResponseSupport.normalizeSchemaVersion(schemaVersion);

        responseId = ResponseSupport.requireText(
                responseId,
                "报告responseId不能为空"
        );

        runId = ResponseSupport.requireText(
                runId,
                "报告runId不能为空"
        );

        conversationId = ResponseSupport.requireText(
                conversationId,
                "报告conversationId不能为空"
        );

        mode = ResponseSupport.normalizeMode(
                mode,
                PresentationMode.REPORT
        );

        status = ResponseSupport.normalizeStatus(status);
        reportId = ResponseSupport.normalizeText(reportId);
        reportType = ResponseSupport.normalizeText(reportType);
        queryType = ResponseSupport.normalizeText(queryType);
        title = ResponseSupport.normalizeText(title);
        subtitle = ResponseSupport.normalizeText(subtitle);

        sections = sections == null
                ? List.of()
                : List.copyOf(sections);

        references = references == null
                ? List.of()
                : List.copyOf(references);

        meta = meta == null
                ? ResponseMeta.empty()
                : meta;
    }
}