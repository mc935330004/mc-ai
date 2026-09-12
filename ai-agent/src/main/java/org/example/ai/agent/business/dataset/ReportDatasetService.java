package org.example.ai.agent.business.dataset;

import com.baomidou.mybatisplus.extension.service.IService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.ai.agent.business.dataset.dto.ReportDatasetSaveDTO;
import org.example.ai.agent.business.dataset.vo.ReportDatasetDetailVO;
import org.example.ai.agent.business.dataset.vo.ReportDatasetListVO;
import org.example.ai.agent.business.dataset.vo.ReportDatasetValidationVO;
import org.example.ai.agent.business.dataset.entity.ReportDataset;
import org.example.ai.agent.business.dataset.entity.ReportDatasetField;

import java.util.List;

/**
 * 报告数据集当前配置服务。
 */
public interface ReportDatasetService extends IService<ReportDataset> {

    /** 分页查询当前数据集配置。 */
    Page<ReportDatasetListVO> pageCurrent(
            long current,
            long size,
            String keyword,
            String domainCode,
            Boolean enabled);

    /** 查询一份数据集当前配置及字段策略。 */
    ReportDatasetDetailVO detailCurrent(Long id);

    /** 校验管理端配置，不写数据库。 */
    ReportDatasetValidationVO validateCurrent(ReportDatasetSaveDTO dto);

    /** 保存管理端结构化配置并返回最新详情。 */
    ReportDatasetDetailVO saveCurrent(ReportDatasetSaveDTO dto, String operatorId);

    /** 使用乐观锁启用或停用当前配置。 */
    void updateStatus(Long id, Boolean enabled, Integer version, String operatorId);

    /**
     * 保存同一数据集编码的当前配置，并整体替换字段策略集合。
     */
    ReportDataset saveCurrent(
            ReportDataset dataset,
            List<ReportDatasetField> fields,
            String operatorId);
}
