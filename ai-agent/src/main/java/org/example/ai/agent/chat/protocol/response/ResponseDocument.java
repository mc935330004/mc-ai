package org.example.ai.agent.chat.protocol.response;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.example.ai.agent.common.enums.protocol.PresentationMode;
import org.example.ai.agent.common.enums.protocol.ResponseStatus;

/**
 * AI回答和固定报告共用的顶层响应协议。
 *
 * CHAT模式使用AiResponse，
 * REPORT模式使用ReportSchema。
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "mode",
        visible = true
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = AiResponse.class, name = "CHAT"),
        @JsonSubTypes.Type(value = ReportSchema.class, name = "REPORT")
})
public sealed interface ResponseDocument permits AiResponse, ReportSchema {

    /**
     * 当前响应协议版本。
     */
    int CURRENT_SCHEMA_VERSION = 1;

    /**
     * 响应协议版本。
     */
    int schemaVersion();

    /**
     * 本次回答唯一标识。
     */
    String responseId();

    /**
     * 本次执行记录标识。
     */
    String runId();

    /**
     * 当前会话标识。
     */
    String conversationId();

    /**
     * 最终展示模式。
     */
    PresentationMode mode();

    /**
     * 整体响应状态。
     */
    ResponseStatus status();

    /**
     * 业务基础数据是否完整。
     *
     * AI总结失败但业务数据完整时，
     * 该值仍然可以为true。
     */
    boolean dataComplete();
}

/**
 * 顶层响应协议的公共校验方法。
 *
 * 只处理协议参数，不承载业务判断。
 */
final class ResponseSupport {

    private ResponseSupport() {
    }

    static int normalizeSchemaVersion(int schemaVersion) {
        return schemaVersion <= 0
                ? ResponseDocument.CURRENT_SCHEMA_VERSION
                : schemaVersion;
    }

    static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }

        return value.trim();
    }

    static String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }

    static PresentationMode normalizeMode(
            PresentationMode mode,
            PresentationMode expectedMode) {

        if (mode == null) {
            return expectedMode;
        }

        if (mode != expectedMode) {
            throw new IllegalArgumentException(
                    "响应模式必须是" + expectedMode
            );
        }

        return mode;
    }

    static ResponseStatus normalizeStatus(ResponseStatus status) {
        return status == null
                ? ResponseStatus.RUNNING
                : status;
    }
}