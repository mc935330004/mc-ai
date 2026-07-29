package org.example.ai.agent.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.ai.agent.chat.entity.AiChatMessage;

@Mapper
public interface AiChatMessageMapper extends BaseMapper<AiChatMessage> {
}