package org.example.ai.agent;

import org.example.ai.agent.modules.knowledgebase.repository.VectorRepository;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class AiRagApplicationTests {

    /**
     * 测试环境已经关闭 OpenAI Chat 自动配置，
     * 因此使用 Mockito 创建一个假的 ChatModel Bean。
     *
     * ChatClient 仍然可以正常创建，但测试过程中不会访问真实模型。
     */
    @MockitoBean
    private ChatModel chatModel;

    /**
     * 测试环境关闭了向量库，因此生产环境中受条件控制的 VectorRepository 不会注册。
     * 上下文仍会装配依赖它的知识库服务，这里提供替代 Bean 以隔离真实 PGVector。
     */
    @MockitoBean
    private VectorRepository vectorRepository;

    /**
     * 测试上下文不连接 Redis，而模型配置监听容器会在上下文刷新时主动建立订阅连接。
     * 使用同名替代 Bean，避免 contextLoads 依赖本机 Redis 服务。
     */
    @MockitoBean(name = "modelConfigCacheListenerContainer")
    private RedisMessageListenerContainer modelConfigCacheListenerContainer;

    /**
     * 验证整个 Spring ApplicationContext 可以正常加载。
     */
    @Test
    void contextLoads() {
    }
}
