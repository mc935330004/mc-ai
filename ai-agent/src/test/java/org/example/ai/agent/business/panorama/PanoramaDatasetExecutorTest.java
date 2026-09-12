package org.example.ai.agent.business.panorama;

import org.example.ai.agent.business.dataset.ReportDatasetExecutionService;
import org.example.ai.agent.business.dataset.model.DatasetExecutionRequest;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.model.DatasetExecutionStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PanoramaDatasetExecutorTest {

    @Test
    void convertsModuleDeadlineToTimeoutAndCancelsTheCall() {
        ReportDatasetExecutionService service = mock(ReportDatasetExecutionService.class);
        when(service.execute(any())).thenAnswer(ignored -> {
            Thread.sleep(5_000);
            return null;
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            PanoramaDatasetExecutor.Result result = new PanoramaDatasetExecutor(
                    service,
                    executor
            ).execute(request(), 100);

            assertThat(result.status()).isEqualTo(DatasetExecutionStatus.TIMEOUT);
            assertThat(result.executionResult()).isNull();
        } finally {
            executor.shutdownNow();
        }
    }

    private DatasetExecutionRequest request() {
        return new DatasetExecutionRequest(
                "run-1", "user-1", "session-1", "Bearer token", Map.of(),
                "CONTRACT", BusinessSubjectType.PROJECT, "project-id-1",
                Map.of("projectCode", "XXXT2674040")
        );
    }
}
