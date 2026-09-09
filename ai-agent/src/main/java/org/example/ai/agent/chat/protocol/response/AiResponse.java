package org.example.ai.agent.chat.protocol.response;

import org.example.ai.agent.chat.protocol.block.ResponseBlock;
import org.example.ai.agent.common.enums.protocol.PresentationMode;
import org.example.ai.agent.common.enums.protocol.ResponseStatus;

import java.util.List;

/**
 * CHAT模式的统一回答对象。
 *
 * 文字回答、核心指标、风险提示和少量列表，
 * 都通过有序Block组合展示。
 */
public record AiResponse(
        int schemaVersion,
        String responseId,
        String runId,
        String conversationId,
        PresentationMode mode,
        ResponseStatus status,
        boolean dataComplete,
        ResponseContext context,
        List<ResponseBlock> blocks,
        List<ResponseReference> references,
        ResponseMeta meta)
        implements ResponseDocument {

    /**
     * CHAT回答当前协议版本。
     *
     * REPORT协议继续使用ResponseDocument中的版本1。
     */
    public static final int CURRENT_SCHEMA_VERSION = 2;

    public AiResponse {
        schemaVersion = schemaVersion <= 0
                ? CURRENT_SCHEMA_VERSION
                : schemaVersion;

        responseId = ResponseSupport.requireText(
                responseId,
                "回答responseId不能为空"
        );

        runId = ResponseSupport.requireText(
                runId,
                "回答runId不能为空"
        );

        conversationId = ResponseSupport.requireText(
                conversationId,
                "回答conversationId不能为空"
        );

        mode = ResponseSupport.normalizeMode(
                mode,
                PresentationMode.CHAT
        );

        status = ResponseSupport.normalizeStatus(status);

        blocks = blocks == null
                ? List.of()
                : List.copyOf(blocks);

        references = references == null
                ? List.of()
                : List.copyOf(references);

        meta = meta == null
                ? ResponseMeta.empty()
                : meta;
    }

    /**
     * 兼容尚未携带context的旧调用方和历史数据。
     */
    public AiResponse(
            int schemaVersion,
            String responseId,
            String runId,
            String conversationId,
            PresentationMode mode,
            ResponseStatus status,
            boolean dataComplete,
            List<ResponseBlock> blocks,
            List<ResponseReference> references,
            ResponseMeta meta) {
        this(
                schemaVersion,
                responseId,
                runId,
                conversationId,
                mode,
                status,
                dataComplete,
                null,
                blocks,
                references,
                meta
        );
    }
}
