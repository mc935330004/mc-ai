package org.example.ai.agent.workflow.answer.chunk;

import lombok.Getter;

/**
 * 工作流回答分块模型调用异常。
 *
 * 异常中必须携带覆盖率台账，
 * 方便后续前端显示失败分块以及未执行数量。
 */
@Getter
public class WorkflowAnswerChunkConsumeException
        extends RuntimeException {

    private final String errorCode;

    private final WorkflowAnswerChunkCoverage coverage;

    public WorkflowAnswerChunkConsumeException(
            String errorCode,
            String message,
            WorkflowAnswerChunkCoverage coverage,
            Throwable cause) {

        super(message, cause);

        this.errorCode = errorCode;
        this.coverage = coverage;
    }
}