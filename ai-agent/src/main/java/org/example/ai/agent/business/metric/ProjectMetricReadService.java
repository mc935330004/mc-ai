package org.example.ai.agent.business.metric;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.business.dataset.model.DatasetExecutionRequest;
import org.example.ai.agent.business.dataset.model.DatasetExecutionResult;
import org.example.ai.agent.business.metric.BusinessMetricCatalogService.MetricOption;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.model.DatasetExecutionStatus;
import org.example.ai.agent.business.panorama.PanoramaDatasetExecutor;
import org.example.ai.agent.business.panorama.ProjectPanoramaProfileService;
import org.example.ai.agent.business.panorama.ProjectSubjectAuthorizationService;
import org.example.ai.agent.business.panorama.model.AuthorizedProjectSubject;
import org.example.ai.agent.business.panorama.model.ProjectPanoramaCommand;
import org.example.ai.agent.business.panorama.model.ProjectPanoramaPlan;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 项目单指标只读执行服务。
 *
 * 只执行目标指标所属的一个数据集。
 * 即使来源接口返回完整模块，离开本服务时也只保留目标 factCode。
 */
@Service
@RequiredArgsConstructor
public class ProjectMetricReadService {

    private final ProjectSubjectAuthorizationService authorizationService;
    private final ProjectPanoramaProfileService profileService;
    private final BusinessMetricCatalogService metricCatalogService;
    private final PanoramaDatasetExecutor datasetExecutor;

    /**
     * 执行一次项目单指标查询。
     */
    public Result execute(
            ProjectPanoramaCommand command,
            List<String> requestedDatasetCodes,
            String question) {
        if (command == null) {
            throw new IllegalArgumentException("项目单指标查询命令不能为空");
        }

        AuthorizedProjectSubject subject =
                authorizationService.authorize(command);

        ProjectPanoramaPlan plan =
                profileService.resolve(subject.projectType());

        List<String> availableDatasetCodes = plan.modules().stream()
                .map(ProjectPanoramaPlan.Module::datasetCode)
                .toList();

        Optional<MetricOption> selected =
                metricCatalogService.selectProjectMetric(
                        availableDatasetCodes,
                        requestedDatasetCodes,
                        question
                );

        if (selected.isEmpty()) {
            return Result.unconfigured();
        }

        MetricOption metric = selected.get();
        ProjectPanoramaPlan.Module module =
                findModule(plan, metric.datasetCode());

        DatasetExecutionRequest request =
                executionRequest(command, subject, metric.datasetCode());

        PanoramaDatasetExecutor.Result timedResult =
                datasetExecutor.execute(request, module.timeoutMs());

        return toResult(metric, timedResult);
    }

    private ProjectPanoramaPlan.Module findModule(
            ProjectPanoramaPlan plan,
            String datasetCode) {
        return plan.modules().stream()
                .filter(module ->
                        module.datasetCode().equals(datasetCode))
                .findFirst()
                .orElseThrow(() ->
                        new IllegalStateException(
                                "指标数据集不在当前项目全景方案中"
                        ));
    }

    private DatasetExecutionRequest executionRequest(
            ProjectPanoramaCommand command,
            AuthorizedProjectSubject subject,
            String datasetCode) {
        Map<String, Object> canonicalInput =
                new LinkedHashMap<>(command.canonicalQuery());

        /*
         * projectYear只负责主体定位。
         * 数据集时间范围只使用明确的startDate和endDate。
         */
        canonicalInput.remove("projectYear");
        canonicalInput.put("projectCode", subject.projectCode());

        return new DatasetExecutionRequest(
                command.agentRunId(),
                command.userId(),
                command.sessionId(),
                command.authorization(),
                command.secureContext(),
                datasetCode,
                BusinessSubjectType.PROJECT,
                subject.projectId(),
                Map.copyOf(canonicalInput)
        );
    }

    private Result toResult(
            MetricOption metric,
            PanoramaDatasetExecutor.Result timedResult) {
        if (timedResult == null
                || timedResult.status() == null) {
            return Result.failed(metric);
        }

        DatasetExecutionResult executionResult =
                timedResult.executionResult();

        if (executionResult == null) {
            return Result.terminal(
                    metric,
                    timedResult.status(),
                    terminalMessage(timedResult.status())
            );
        }

        DatasetExecutionStatus status =
                executionResult.status();

        if (status != DatasetExecutionStatus.SUCCESS) {
            return Result.terminal(
                    metric,
                    status,
                    safeMessage(executionResult, status)
            );
        }

        Map<String, Object> displayFacts = selectMetric(
                executionResult,
                "display",
                metric.metricCode()
        );

        if (displayFacts.isEmpty()) {
            return Result.missing(
                    metric,
                    "指标已配置，但数据来源未返回该值"
            );
        }

        Object displayValue =
                displayFacts.get(metric.metricCode());

        if (!compatible(metric.valueType(), displayValue)) {
            return Result.missing(
                    metric,
                    "指标返回类型与已发布字段配置不一致"
            );
        }

        Map<String, Object> modelFacts = selectMetric(
                executionResult,
                "model",
                metric.metricCode()
        );

        return new Result(
                metric,
                DatasetExecutionStatus.SUCCESS,
                executionResult.dataComplete(),
                displayFacts,
                modelFacts,
                safeMessage(
                        executionResult,
                        DatasetExecutionStatus.SUCCESS
                )
        );
    }

    private Map<String, Object> selectMetric(
            DatasetExecutionResult result,
            String channel,
            String metricCode) {
        Object channelValue = result.safeFacts().get(channel);

        if (!(channelValue instanceof Map<?, ?> values)
                || !values.containsKey(metricCode)) {
            return Map.of();
        }

        Object value = values.get(metricCode);
        if (value == null) {
            return Map.of();
        }

        return Map.of(metricCode, value);
    }

    /**
     * 类型不一致时按缺失处理，禁止依赖字符串转换掩盖来源错误。
     */
    private boolean compatible(
            org.example.ai.agent.common.enums.protocol.ValueType type,
            Object value) {
        if (value == null) {
            return false;
        }

        return switch (type) {
            case NUMBER, AMOUNT, PERCENT ->
                    value instanceof Number;
            case BOOLEAN ->
                    value instanceof Boolean;
            case TEXT, DATE, DATETIME, ENUM ->
                    value instanceof String;
            default -> false;
        };
    }

    private String safeMessage(
            DatasetExecutionResult result,
            DatasetExecutionStatus status) {
        if (result != null
                && StringUtils.hasText(result.safeMessage())) {
            return result.safeMessage();
        }

        return terminalMessage(status);
    }

    private String terminalMessage(
            DatasetExecutionStatus status) {
        return switch (status) {
            case SUCCESS -> "查询完成";
            case EMPTY -> "未查询到指标数据";
            case DENIED -> "因权限不足未返回数据";
            case TIMEOUT -> "指标查询超时";
            case RUNNING -> "正在查询指标";
            case PENDING -> "指标等待查询";
            case FAILED -> "指标查询失败";
        };
    }

    /**
     * 只保存一个目标指标的安全结果。
     */
    public record Result(
            MetricOption metric,
            DatasetExecutionStatus status,
            boolean dataComplete,
            Map<String, Object> displayFacts,
            Map<String, Object> modelFacts,
            String safeMessage) {

        public Result {
            status = status == null
                    ? DatasetExecutionStatus.FAILED
                    : status;
            displayFacts = displayFacts == null
                    ? Map.of()
                    : Map.copyOf(displayFacts);
            modelFacts = modelFacts == null
                    ? Map.of()
                    : Map.copyOf(modelFacts);
            safeMessage = StringUtils.hasText(safeMessage)
                    ? safeMessage.trim()
                    : "指标查询失败";
        }

        public static Result unconfigured() {
            return new Result(
                    null,
                    DatasetExecutionStatus.EMPTY,
                    false,
                    Map.of(),
                    Map.of(),
                    "该指标尚未配置"
            );
        }

        public static Result missing(
                MetricOption metric,
                String message) {
            return new Result(
                    metric,
                    DatasetExecutionStatus.EMPTY,
                    false,
                    Map.of(),
                    Map.of(),
                    message
            );
        }

        public static Result terminal(
                MetricOption metric,
                DatasetExecutionStatus status,
                String message) {
            return new Result(
                    metric,
                    status,
                    false,
                    Map.of(),
                    Map.of(),
                    message
            );
        }

        public static Result failed(MetricOption metric) {
            return terminal(
                    metric,
                    DatasetExecutionStatus.FAILED,
                    "指标查询失败"
            );
        }

        /**
         * 日志字符串不输出指标值。
         */
        @Override
        public String toString() {
            return "Result[metricConfigured="
                    + (metric != null)
                    + ", status=" + status
                    + ", dataComplete=" + dataComplete
                    + ", displayFactCount=" + displayFacts.size()
                    + ", modelFactCount=" + modelFacts.size()
                    + ']';
        }
    }
}