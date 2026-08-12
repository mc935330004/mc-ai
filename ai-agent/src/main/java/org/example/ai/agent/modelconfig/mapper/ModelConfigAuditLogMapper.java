package org.example.ai.agent.modelconfig.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.ai.agent.modelconfig.entity.ModelConfigAuditLog;

/**
 * 模型配置审计Mapper。
 *
 * 当前使用MyBatis Plus完成新增和分页查询，
 * 不需要创建Mapper XML。
 */
@Mapper
public interface ModelConfigAuditLogMapper extends BaseMapper<ModelConfigAuditLog> {
}