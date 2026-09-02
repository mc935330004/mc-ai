package org.example.ai.agent.business.dataset.impl;

import org.example.ai.agent.business.dataset.model.DatasetExecutionResult;
import org.example.ai.agent.business.dataset.model.DatasetExecutionSource;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.model.DatasetExecutionStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DatasetExecutionProofServiceTest {

    private final DatasetExecutionProofService proofService =
            new DatasetExecutionProofService();

    @Test
    void shouldRejectUnsignedResultAndAcceptSignedResult() {
        DatasetExecutionResult unsigned = unsignedResult();

        assertThat(proofService.verify(unsigned)).isFalse();
        assertThat(proofService.verify(proofService.sign(unsigned))).isTrue();
    }

    @Test
    void shouldRejectAnySignedMaterialChange() {
        DatasetExecutionResult signed = proofService.sign(unsignedResult());

        assertThat(proofService.verify(copy(signed, source("project-2"),
                signed.status(), signed.dataComplete(), signed.safeFacts(),
                signed.workflowRunId(), signed.resultArtifactId(),
                signed.safeErrorCode(), signed.safeMessage()))).isFalse();
        assertThat(proofService.verify(copy(signed, signed.source(),
                signed.status(), signed.dataComplete(), rawHashFacts(),
                signed.workflowRunId(), signed.resultArtifactId(),
                signed.safeErrorCode(), signed.safeMessage()))).isFalse();
        assertThat(proofService.verify(copy(signed, signed.source(),
                DatasetExecutionStatus.EMPTY, signed.dataComplete(), signed.safeFacts(),
                signed.workflowRunId(), signed.resultArtifactId(),
                signed.safeErrorCode(), signed.safeMessage()))).isFalse();
        assertThat(proofService.verify(copy(signed, signed.source(),
                signed.status(), !signed.dataComplete(), signed.safeFacts(),
                signed.workflowRunId(), signed.resultArtifactId(),
                signed.safeErrorCode(), signed.safeMessage()))).isFalse();
        assertThat(proofService.verify(copy(signed, signed.source(),
                signed.status(), signed.dataComplete(), signed.safeFacts(),
                "other-run", signed.resultArtifactId(),
                signed.safeErrorCode(), signed.safeMessage()))).isFalse();
        assertThat(proofService.verify(copy(signed, signed.source(),
                signed.status(), signed.dataComplete(), signed.safeFacts(),
                signed.workflowRunId(), "other-artifact",
                signed.safeErrorCode(), signed.safeMessage()))).isFalse();
        assertThat(proofService.verify(copy(signed, signed.source(),
                signed.status(), signed.dataComplete(), signed.safeFacts(),
                signed.workflowRunId(), signed.resultArtifactId(),
                "OTHER_ERROR", signed.safeMessage()))).isFalse();
        assertThat(proofService.verify(copy(signed, signed.source(),
                signed.status(), signed.dataComplete(), signed.safeFacts(),
                signed.workflowRunId(), signed.resultArtifactId(),
                signed.safeErrorCode(), "其他安全消息"))).isFalse();
    }

    private DatasetExecutionResult unsignedResult() {
        return new DatasetExecutionResult(
                source("project-1"),
                DatasetExecutionStatus.SUCCESS,
                true,
                safeFacts("*******8000"),
                "run-1",
                "artifact-1",
                null,
                "数据查询完成",
                null
        );
    }

    private DatasetExecutionResult copy(
            DatasetExecutionResult signed,
            DatasetExecutionSource source,
            DatasetExecutionStatus status,
            boolean dataComplete,
            Map<String, Object> safeFacts,
            String runId,
            String artifactId,
            String errorCode,
            String safeMessage) {
        return new DatasetExecutionResult(
                source,
                status,
                dataComplete,
                safeFacts,
                runId,
                artifactId,
                errorCode,
                safeMessage,
                signed.integrityProof()
        );
    }

    private DatasetExecutionSource source(String subjectId) {
        return new DatasetExecutionSource(
                "user-1",
                "session-1",
                BusinessSubjectType.PROJECT,
                subjectId,
                "CONTRACT",
                "a".repeat(64),
                "contract.query",
                7L,
                "b".repeat(64),
                "c".repeat(64)
        );
    }

    private Map<String, Object> rawHashFacts() {
        return safeFacts("d".repeat(64));
    }

    private Map<String, Object> safeFacts(Object visibleValue) {
        return Map.of(
                "calculation", Map.of("amount", "13800138000"),
                "display", Map.of("amount", visibleValue),
                "export", Map.of("amount", visibleValue),
                "model", Map.of("amount", visibleValue)
        );
    }
}
