package org.example.ai.agent.workflow.answer.chunk;

import lombok.Getter;

import java.util.List;

/**
 * 工作流分层汇总异常。
 */
@Getter
public class WorkflowAnswerReduceException
        extends RuntimeException {

    private final String errorCode;

    private final int reductionLevel;

    private final int callSequence;

    private final List<Integer> coveredIndexes;

    public WorkflowAnswerReduceException(
            String errorCode,
            String message,
            int reductionLevel,
            int callSequence,
            List<Integer> coveredIndexes,
            Throwable cause) {

        super(message, cause);

        this.errorCode = errorCode;
        this.reductionLevel = reductionLevel;
        this.callSequence = callSequence;
        this.coveredIndexes = coveredIndexes == null ? List.of() : List.copyOf(coveredIndexes);
    }
}