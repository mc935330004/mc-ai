package org.example.ai.agent.business.metric;

import org.example.ai.agent.business.dataset.model.DatasetExecutionRequest;
import org.example.ai.agent.business.dataset.model.DatasetExecutionResult;
import org.example.ai.agent.business.dataset.model.DatasetExecutionSource;
import org.example.ai.agent.business.metric.BusinessMetricCatalogService.MetricOption;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.model.DatasetExecutionStatus;
import org.example.ai.agent.business.panorama.PanoramaDatasetExecutor;
import org.example.ai.agent.business.panorama.ProjectPanoramaProfileService;
import org.example.ai.agent.business.panorama.ProjectSubjectAuthorizationService;
import org.example.ai.agent.business.panorama.model.AuthorizedProjectSubject;
import org.example.ai.agent.business.panorama.model.ProjectPanoramaCommand;
import org.example.ai.agent.business.panorama.model.ProjectPanoramaPlan;
import org.example.ai.agent.common.enums.protocol.ValueType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 项目单指标查询的最小执行、安全裁剪和异常状态测试。
 */
class ProjectMetricReadServiceTest {

    private static final String USER_ID = "user-1";
    private static final String SESSION_ID = "conversation-1";
    private static final String PROJECT_ID = "project-raw-1";
    private static final String PROJECT_CODE = "XXXT2674040";
    private static final String DATASET_CODE = "PROJECT_BUDGET";
    private static final String METRIC_CODE =
            "personnelExpenseUsed";

    private final ProjectSubjectAuthorizationService authorizationService =
            mock(ProjectSubjectAuthorizationService.class);

    private final ProjectPanoramaProfileService profileService =
            mock(ProjectPanoramaProfileService.class);

    private final BusinessMetricCatalogService metricCatalogService =
            mock(BusinessMetricCatalogService.class);

    private final PanoramaDatasetExecutor datasetExecutor =
            mock(PanoramaDatasetExecutor.class);

    private final ProjectMetricReadService service =
            new ProjectMetricReadService(
                    authorizationService,
                    profileService,
                    metricCatalogService,
                    datasetExecutor
            );

    private final MetricOption metric = new MetricOption(
            DATASET_CODE,
            "项目概算",
            METRIC_CODE,
            "人员费用已用金额",
            ValueType.AMOUNT,
            "元"
    );

    @BeforeEach
    void setUp() {
        when(authorizationService.authorize(any()))
                .thenReturn(new AuthorizedProjectSubject(
                        PROJECT_ID,
                        PROJECT_CODE,
                        "DELIVERY",
                        "滨江智慧园区建设项目"
                ));

        when(profileService.resolve("DELIVERY"))
                .thenReturn(new ProjectPanoramaPlan(
                        1L,
                        "DELIVERY",
                        "项目全景",
                        "a".repeat(64),
                        List.of(new ProjectPanoramaPlan.Module(
                                DATASET_CODE,
                                true,
                                10,
                                30_000
                        ))
                ));
    }

    /**
     * A03、A14：
     * 只执行目标数据集，并删除响应中的其他展示和模型字段。
     */
    @Test
    void executesOnlySelectedDatasetAndKeepsOnlyTargetMetric() {
        String question =
                "查询项目概算中人员费用已用金额";

        when(metricCatalogService.selectProjectMetric(
                eq(List.of(DATASET_CODE)),
                eq(List.of("BUDGET")),
                eq(question)
        )).thenReturn(Optional.of(metric));

        when(datasetExecutor.execute(
                any(DatasetExecutionRequest.class),
                eq(30_000)
        )).thenAnswer(invocation -> {
            DatasetExecutionRequest request =
                    invocation.getArgument(0);

            return new PanoramaDatasetExecutor.Result(
                    DatasetExecutionStatus.SUCCESS,
                    executionResult(
                            request,
                            Map.of(
                                    "display",
                                    Map.of(
                                            METRIC_CODE,
                                            new BigDecimal("125000.00"),
                                            "contractAmount",
                                            new BigDecimal("999999.00")
                                    ),
                                    "model",
                                    Map.of(
                                            METRIC_CODE,
                                            new BigDecimal("125000.00"),
                                            "internalCostDetail",
                                            "禁止进入模型"
                                    ),
                                    "calculation",
                                    Map.of(),
                                    "export",
                                    Map.of()
                            )
                    )
            );
        });

        ProjectMetricReadService.Result result =
                service.execute(
                        command(),
                        List.of("BUDGET"),
                        question
                );

        assertThat(result.status())
                .isEqualTo(DatasetExecutionStatus.SUCCESS);

        assertThat(result.dataComplete()).isTrue();

        assertThat(result.displayFacts())
                .containsOnlyKeys(METRIC_CODE)
                .containsEntry(
                        METRIC_CODE,
                        new BigDecimal("125000.00")
                );

        assertThat(result.modelFacts())
                .containsOnlyKeys(METRIC_CODE)
                .doesNotContainKey("internalCostDetail");

        // 日志摘要只能记录状态和数量，不能带出业务数值或隐藏字段内容。
        assertThat(result.toString())
                .doesNotContain(
                        "125000.00",
                        "999999.00",
                        "禁止进入模型"
                );

        ArgumentCaptor<DatasetExecutionRequest> request =
                ArgumentCaptor.forClass(
                        DatasetExecutionRequest.class
                );

        verify(datasetExecutor).execute(
                request.capture(),
                eq(30_000)
        );

        assertThat(request.getValue().datasetCode())
                .isEqualTo(DATASET_CODE);

        assertThat(request.getValue().subjectType())
                .isEqualTo(BusinessSubjectType.PROJECT);

        assertThat(request.getValue().subjectId())
                .isEqualTo(PROJECT_ID);

        assertThat(request.getValue().canonicalInput())
                .containsEntry("projectCode", PROJECT_CODE)
                .doesNotContainKey("projectYear");

        verifyNoMoreInteractions(datasetExecutor);
    }

    /**
     * A04：
     * 来源成功但没有目标字段时必须返回EMPTY，不能生成0。
     */
    @Test
    void missingMetricReturnsEmptyWithoutZero() {
        String question =
                "查询项目概算中人员费用已用金额";

        when(metricCatalogService.selectProjectMetric(
                any(),
                any(),
                eq(question)
        )).thenReturn(Optional.of(metric));

        when(datasetExecutor.execute(any(), eq(30_000)))
                .thenAnswer(invocation -> {
                    DatasetExecutionRequest request =
                            invocation.getArgument(0);

                    return new PanoramaDatasetExecutor.Result(
                            DatasetExecutionStatus.SUCCESS,
                            executionResult(
                                    request,
                                    Map.of(
                                            "display",
                                            Map.of(
                                                    "otherMetric",
                                                    BigDecimal.ZERO
                                            ),
                                            "model",
                                            Map.of(),
                                            "calculation",
                                            Map.of(),
                                            "export",
                                            Map.of()
                                    )
                            )
                    );
                });

        ProjectMetricReadService.Result result =
                service.execute(
                        command(),
                        List.of("BUDGET"),
                        question
                );

        assertThat(result.status())
                .isEqualTo(DatasetExecutionStatus.EMPTY);

        assertThat(result.dataComplete()).isFalse();
        assertThat(result.displayFacts()).isEmpty();
        assertThat(result.modelFacts()).isEmpty();

        assertThat(result.safeMessage())
                .contains("未返回该值");
    }

    /**
     * A04：
     * 真实的数值0必须保留，不能与字段缺失混为一谈。
     */
    @Test
    void realZeroMetricRemainsSuccessful() {
        String question =
                "查询项目概算中人员费用已用金额";

        when(metricCatalogService.selectProjectMetric(
                any(),
                any(),
                eq(question)
        )).thenReturn(Optional.of(metric));

        when(datasetExecutor.execute(any(), eq(30_000)))
                .thenAnswer(invocation -> {
                    DatasetExecutionRequest request =
                            invocation.getArgument(0);

                    return new PanoramaDatasetExecutor.Result(
                            DatasetExecutionStatus.SUCCESS,
                            executionResult(
                                    request,
                                    Map.of(
                                            "display",
                                            Map.of(
                                                    METRIC_CODE,
                                                    BigDecimal.ZERO
                                            ),
                                            "model",
                                            Map.of(),
                                            "calculation",
                                            Map.of(),
                                            "export",
                                            Map.of()
                                    )
                            )
                    );
                });

        ProjectMetricReadService.Result result =
                service.execute(
                        command(),
                        List.of("BUDGET"),
                        question
                );

        assertThat(result.status())
                .isEqualTo(DatasetExecutionStatus.SUCCESS);

        assertThat(result.displayFacts())
                .containsEntry(
                        METRIC_CODE,
                        BigDecimal.ZERO
                );
    }

    /**
     * A04：
     * 超时必须保持TIMEOUT，不能转换成空值或0。
     */
    @Test
    void timeoutKeepsTimeoutStatusAndNoMetricValue() {
        String question =
                "查询项目概算中人员费用已用金额";

        when(metricCatalogService.selectProjectMetric(
                any(),
                any(),
                eq(question)
        )).thenReturn(Optional.of(metric));

        when(datasetExecutor.execute(any(), eq(30_000)))
                .thenReturn(new PanoramaDatasetExecutor.Result(
                        DatasetExecutionStatus.TIMEOUT,
                        null
                ));

        ProjectMetricReadService.Result result =
                service.execute(
                        command(),
                        List.of("BUDGET"),
                        question
                );

        assertThat(result.status())
                .isEqualTo(DatasetExecutionStatus.TIMEOUT);

        assertThat(result.dataComplete()).isFalse();
        assertThat(result.displayFacts()).isEmpty();
        assertThat(result.modelFacts()).isEmpty();
        assertThat(result.safeMessage()).contains("超时");
    }

    /**
     * A04：
     * 权限拒绝必须保持DENIED，不能转换成空值、0或正常状态。
     */
    @Test
    void deniedKeepsDeniedStatusAndNoMetricValue() {
        String question =
                "查询项目概算中人员费用已用金额";

        when(metricCatalogService.selectProjectMetric(
                any(),
                any(),
                eq(question)
        )).thenReturn(Optional.of(metric));

        when(datasetExecutor.execute(any(), eq(30_000)))
                .thenAnswer(invocation -> {
                    DatasetExecutionRequest request =
                            invocation.getArgument(0);

                    return new PanoramaDatasetExecutor.Result(
                            DatasetExecutionStatus.DENIED,
                            new DatasetExecutionResult(
                                    new DatasetExecutionSource(
                                            request.userId(),
                                            request.sessionId(),
                                            request.subjectType(),
                                            request.subjectId(),
                                            request.datasetCode(),
                                            "b".repeat(64),
                                            "budget-query-workflow",
                                            1L,
                                            "c".repeat(64),
                                            "d".repeat(64)
                                    ),
                                    DatasetExecutionStatus.DENIED,
                                    false,
                                    Map.of(),
                                    "workflow-run-1",
                                    "ACCESS_DENIED",
                                    "无权访问当前项目",
                                    "因权限不足未返回数据",
                                    "e".repeat(64)
                            )
                    );
                });

        ProjectMetricReadService.Result result =
                service.execute(
                        command(),
                        List.of("BUDGET"),
                        question
                );

        assertThat(result.status())
                .isEqualTo(DatasetExecutionStatus.DENIED);

        assertThat(result.dataComplete()).isFalse();
        assertThat(result.displayFacts()).isEmpty();
        assertThat(result.modelFacts()).isEmpty();
        assertThat(result.safeMessage()).contains("权限不足");
    }

    /**
     * 未配置指标不能触发数据集执行。
     */
    @Test
    void unconfiguredMetricDoesNotExecuteDataset() {
        String question =
                "查询未配置的项目指标";

        when(metricCatalogService.selectProjectMetric(
                any(),
                any(),
                eq(question)
        )).thenReturn(Optional.empty());

        ProjectMetricReadService.Result result =
                service.execute(
                        command(),
                        List.of("BUDGET"),
                        question
                );

        assertThat(result.metric()).isNull();

        assertThat(result.status())
                .isEqualTo(DatasetExecutionStatus.EMPTY);

        assertThat(result.safeMessage())
                .isEqualTo("该指标尚未配置");

        verifyNoMoreInteractions(datasetExecutor);
    }

    private ProjectPanoramaCommand command() {
        return new ProjectPanoramaCommand(
                "run-1",
                USER_ID,
                SESSION_ID,
                "Bearer current-user",
                Map.of(),
                "project-selection-token",
                Map.of(
                        "projectCode",
                        PROJECT_CODE,
                        "projectYear",
                        2026
                )
        );
    }

    private DatasetExecutionResult executionResult(
            DatasetExecutionRequest request,
            Map<String, Object> safeFacts) {
        return new DatasetExecutionResult(
                new DatasetExecutionSource(
                        request.userId(),
                        request.sessionId(),
                        request.subjectType(),
                        request.subjectId(),
                        request.datasetCode(),
                        "b".repeat(64),
                        "budget-query-workflow",
                        1L,
                        "c".repeat(64),
                        "d".repeat(64)
                ),
                DatasetExecutionStatus.SUCCESS,
                true,
                safeFacts,
                "workflow-run-1",
                null,
                null,
                "数据查询完成",
                "e".repeat(64)
        );
    }
}
