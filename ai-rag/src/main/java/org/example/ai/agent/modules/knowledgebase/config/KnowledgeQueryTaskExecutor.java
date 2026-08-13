package org.example.ai.agent.modules.knowledgebase.config;

import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 知识库查询专用执行器类型。
 *
 * 使用独立类型避免与Agent聊天线程池按接口类型注入时发生歧义。
 */
public class KnowledgeQueryTaskExecutor
        extends ThreadPoolTaskExecutor {
}
