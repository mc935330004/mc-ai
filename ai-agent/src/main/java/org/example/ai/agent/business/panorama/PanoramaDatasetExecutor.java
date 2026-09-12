package org.example.ai.agent.business.panorama;

import org.example.ai.agent.business.dataset.ReportDatasetExecutionService;
import org.example.ai.agent.business.dataset.model.DatasetExecutionRequest;
import org.example.ai.agent.business.dataset.model.DatasetExecutionResult;
import org.example.ai.agent.business.model.DatasetExecutionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 在独立有界线程池中执行单个报告数据集，并应用方案配置的模块时限。
 */
@Service
public class PanoramaDatasetExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(PanoramaDatasetExecutor.class);

    private final ReportDatasetExecutionService executionService;
    private final ExecutorService executor;

    public PanoramaDatasetExecutor(
            ReportDatasetExecutionService executionService,
            @Qualifier("projectPanoramaModuleExecutor") ExecutorService executor) {
        this.executionService = Objects.requireNonNull(
                executionService,
                "executionService不能为空"
        );
        this.executor = Objects.requireNonNull(executor, "executor不能为空");
    }

    /**
     * 超时会取消当前 Future；底层 HTTP 客户端仍需配置连接和读取超时作为最终资源边界。
     */
    public Result execute(DatasetExecutionRequest request, int timeoutMs) {
        if (request == null || timeoutMs < 100 || timeoutMs > 300_000) {
            throw new IllegalArgumentException("项目全景模块执行参数不合法");
        }
        Future<DatasetExecutionResult> future;
        try {
            future = executor.submit(() -> executionService.execute(request));
        } catch (RejectedExecutionException exception) {
            audit(request, "REJECTED");
            return new Result(DatasetExecutionStatus.FAILED, null);
        }
        try {
            DatasetExecutionResult result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            if (result == null || result.status() == null) {
                audit(request, "INVALID_RESULT");
                return new Result(DatasetExecutionStatus.FAILED, null);
            }
            return new Result(result.status(), result);
        } catch (TimeoutException exception) {
            future.cancel(true);
            audit(request, "TIMEOUT");
            return new Result(DatasetExecutionStatus.TIMEOUT, null);
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            audit(request, "INTERRUPTED");
            return new Result(DatasetExecutionStatus.FAILED, null);
        } catch (ExecutionException exception) {
            audit(request, "EXECUTION_FAILED");
            return new Result(DatasetExecutionStatus.FAILED, null);
        }
    }

    private void audit(DatasetExecutionRequest request, String category) {
        LOGGER.warn(
                "项目全景模块执行失败 category={} datasetCode={}",
                category,
                request.datasetCode()
        );
    }

    public record Result(
            DatasetExecutionStatus status,
            DatasetExecutionResult executionResult) {
    }
}
