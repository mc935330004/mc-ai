package org.example.ai.agent.chat.memory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.ai.agent.chat.memory.entity.AiConversationState;

/**
 * 中文注释：会话业务状态只需要 MyBatis Plus 单表操作。
 */
@Mapper
public interface AiConversationStateMapper extends BaseMapper<AiConversationState> {
}