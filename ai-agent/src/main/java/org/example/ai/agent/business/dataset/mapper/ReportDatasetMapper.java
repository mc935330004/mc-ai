package org.example.ai.agent.business.dataset.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.ai.agent.business.dataset.entity.ReportDataset;

/**
 * 报告数据集当前配置数据访问接口。
 */
@Mapper
public interface ReportDatasetMapper extends BaseMapper<ReportDataset> {
}
