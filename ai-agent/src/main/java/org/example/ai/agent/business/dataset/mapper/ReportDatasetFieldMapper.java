package org.example.ai.agent.business.dataset.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.ai.agent.business.dataset.entity.ReportDatasetField;

/**
 * 数据集字段策略数据访问接口。
 */
@Mapper
public interface ReportDatasetFieldMapper extends BaseMapper<ReportDatasetField> {
}
