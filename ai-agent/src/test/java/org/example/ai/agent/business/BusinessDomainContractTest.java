package org.example.ai.agent.business;

import org.example.ai.agent.business.model.AssociationType;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.model.DatasetExecutionStatus;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务助手最小领域契约测试。
 */
class BusinessDomainContractTest {

    @Test
    void businessSubjectTypesKeepStableNamesAndOrder() {
        assertThat(Arrays.stream(BusinessSubjectType.values()).map(Enum::name))
                .containsExactly("PROJECT", "PERSON", "DEPARTMENT");
    }

    @Test
    void datasetExecutionStatusesKeepStableNamesAndOrder() {
        assertThat(Arrays.stream(DatasetExecutionStatus.values()).map(Enum::name))
                .containsExactly("PENDING", "RUNNING", "SUCCESS", "EMPTY", "DENIED", "FAILED", "TIMEOUT");
    }

    @Test
    void associationTypesKeepStableNamesAndOrder() {
        assertThat(Arrays.stream(AssociationType.values()).map(Enum::name))
                .containsExactly("DIRECT", "PROJECT_PERSON_PERIOD", "UNRELATED");
    }
}
