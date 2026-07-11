package org.example.ai.agent.pending.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.ai.agent.pending.entity.PendingAction;

/**
 * 待确认操作 Mapper。
 *
 * 当前只有单表操作，直接使用 BaseMapper，不需要 XML。
 */
@Mapper
public interface PendingActionMapper extends BaseMapper<PendingAction> {
}