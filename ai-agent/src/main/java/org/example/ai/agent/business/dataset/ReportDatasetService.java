package org.example.ai.agent.business.dataset;

import com.baomidou.mybatisplus.extension.service.IService;
import org.example.ai.agent.business.dataset.entity.ReportDataset;
import org.example.ai.agent.business.dataset.entity.ReportDatasetField;

import java.util.List;

/**
 * 报告数据集当前配置服务。
 */
public interface ReportDatasetService extends IService<ReportDataset> {

    /**
     * 保存同一数据集编码的当前配置，并整体替换字段策略集合。
     */
    ReportDataset saveCurrent(
            ReportDataset dataset,
            List<ReportDatasetField> fields,
            String operatorId);
}
