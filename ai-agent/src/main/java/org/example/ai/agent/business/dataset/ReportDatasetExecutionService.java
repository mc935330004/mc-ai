package org.example.ai.agent.business.dataset;

import org.example.ai.agent.business.dataset.model.DatasetExecutionRequest;
import org.example.ai.agent.business.dataset.model.DatasetExecutionResult;

/**
 * 报告数据集授权执行服务。
 */
public interface ReportDatasetExecutionService {

    /**
     * 先校验来源系统权限，再执行已配置的只读查询工作流。
     */
    DatasetExecutionResult execute(DatasetExecutionRequest request);
}
