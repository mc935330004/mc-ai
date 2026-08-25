package org.example.ai.agent.chat.protocol.response;

import org.example.ai.agent.chat.vo.ReportSchemaVO;
import org.example.ai.agent.common.enums.protocol.PresentationMode;
import org.example.ai.agent.common.enums.protocol.ResponseStatus;

import java.util.List;
import java.util.Objects;

/**
 * REPORT模式统一响应。
 *
 * ReportSchema负责统一响应状态和运行信息，
 * ReportSchemaVO继续承载已经稳定运行的业务报告结构。
 */
public record ReportSchema(
        int schemaVersion,
        String responseId,
        String runId,
        String conversationId,
        PresentationMode mode,
        ResponseStatus status,
        boolean dataComplete,
        ReportSchemaVO reportSchema,
        List<ResponseReference> references,
        ResponseMeta meta)
        implements ResponseDocument {

    public ReportSchema {
        schemaVersion =
                ResponseSupport.normalizeSchemaVersion(
                        schemaVersion
                );

        responseId =
                ResponseSupport.requireText(
                        responseId,
                        "报告responseId不能为空"
                );

        runId =
                ResponseSupport.requireText(
                        runId,
                        "报告runId不能为空"
                );

        conversationId =
                ResponseSupport.requireText(
                        conversationId,
                        "报告conversationId不能为空"
                );

        mode =
                ResponseSupport.normalizeMode(
                        mode,
                        PresentationMode.REPORT
                );

        status =
                ResponseSupport.normalizeStatus(
                        status
                );

        reportSchema =
                Objects.requireNonNull(
                        reportSchema,
                        "业务报告结构不能为空"
                );

        references =
                references == null
                        ? List.of()
                        : List.copyOf(references);

        meta =
                meta == null
                        ? ResponseMeta.empty()
                        : meta;
    }

}