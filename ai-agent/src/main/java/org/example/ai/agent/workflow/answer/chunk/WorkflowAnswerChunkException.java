package org.example.ai.agent.workflow.answer.chunk;

import lombok.Getter;

/**
 * 工作流回答数据分块异常。
 *
 * 所有无法保证“全量消费”的情况都必须明确失败，
 * 不能截断数据后继续生成看似正常的回答。
 */
@Getter
public class WorkflowAnswerChunkException extends RuntimeException {

    private final String errorCode;

    public WorkflowAnswerChunkException(
            String errorCode,
            String message) {

        super(message);
        this.errorCode = errorCode;
    }

    public WorkflowAnswerChunkException(
            String errorCode,
            String message,
            Throwable cause) {

        super(message, cause);
        this.errorCode = errorCode;
    }
}