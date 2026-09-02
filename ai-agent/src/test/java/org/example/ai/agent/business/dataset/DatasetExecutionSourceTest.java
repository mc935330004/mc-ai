package org.example.ai.agent.business.dataset;

import org.example.ai.agent.business.dataset.model.DatasetExecutionSource;
import org.example.ai.agent.business.dataset.model.DatasetExecutionResult;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.model.DatasetExecutionStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatasetExecutionSourceTest {

    @Test
    void shouldKeepExecutionBindingWithoutLeakingValuesFromToString() {
        DatasetExecutionSource source = new DatasetExecutionSource(
                "user-secret",
                "session-secret",
                BusinessSubjectType.PROJECT,
                "project-secret",
                "PROJECT_BASE",
                "a".repeat(64),
                "QUERY_PROJECT",
                22L,
                "b".repeat(64),
                "c".repeat(64)
        );

        assertThat(source.toString())
                .doesNotContain(
                        "user-secret",
                        "session-secret",
                        "project-secret",
                        "PROJECT_BASE",
                        "QUERY_PROJECT",
                        "aaaa",
                        "bbbb",
                        "cccc"
                )
                .contains("workflowVersionPresent=true");
    }

    @Test
    void shouldRejectInvalidCanonicalOrConfigurationChecksum() {
        assertThatThrownBy(() -> new DatasetExecutionSource(
                "user-1",
                "session-1",
                BusinessSubjectType.PROJECT,
                "project-1",
                "PROJECT_BASE",
                "not-a-hash",
                "QUERY_PROJECT",
                22L,
                "b".repeat(64),
                "c".repeat(64)
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void executionResultShouldRequireBoundSource() {
        assertThatThrownBy(() -> new DatasetExecutionResult(
                null,
                DatasetExecutionStatus.FAILED,
                false,
                Map.of(),
                null,
                null,
                "FAILED",
                "数据查询失败",
                null
        )).isInstanceOf(NullPointerException.class).hasMessageContaining("source");
    }
}
