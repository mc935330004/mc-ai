package org.example.ai.agent.chat.protocol.stream;

import org.example.ai.agent.common.enums.protocol.PresentationMode;
import org.example.ai.agent.common.enums.protocol.ResponseStatus;

import java.util.Objects;

/**
 * 一次AI回答开始时发送的数据。
 */
public record ResponseStartPayload(
        PresentationMode mode,
        ResponseStatus status,
        boolean resumable) {

    public ResponseStartPayload {
        mode = Objects.requireNonNull(
                mode,
                "回答展示模式不能为空"
        );

        if (mode == PresentationMode.AUTO) {
            throw new IllegalArgumentException(
                    "RESPONSE_START不能使用AUTO模式"
            );
        }

        status = status == null
                ? ResponseStatus.RUNNING
                : status;
    }
}