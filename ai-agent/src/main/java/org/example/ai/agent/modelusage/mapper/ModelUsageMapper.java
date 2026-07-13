package org.example.ai.agent.modelusage.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.ai.agent.modelusage.entity.ModelUsageRecord;

/**
 * 大模型 Token 使用明细 Mapper。
 *
 * 当前阶段只有简单 CRUD，直接继承 BaseMapper，
 * 不需要创建 Mapper XML。
 */
@Mapper
public interface ModelUsageMapper extends BaseMapper<ModelUsageRecord> {
}