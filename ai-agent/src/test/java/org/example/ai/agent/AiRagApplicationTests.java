package org.example.ai.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("dev")
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
     * 验证整个 Spring ApplicationContext 可以正常加载。
     */
    @Test
    void contextLoads() {
    }
}
