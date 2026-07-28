package org.example.ai.agent.pending.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.ai.agent.pending.entity.ActionAuditLog;

/**
 * WRITE 操作审计 Mapper。
 *
 * 当前只有单表新增操作，直接使用 BaseMapper，不需要 XML。
 */
@Mapper
public interface ActionAuditLogMapper extends BaseMapper<ActionAuditLog> {
}