package org.example.ai.agent.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent 回答生成配置。
 *
 * 用于灰度发布和紧急回滚，
 * 不需要修改代码即可关闭新回答链路。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.agent.answer")
public class AgentAnswerProperties {

    /**
     * 是否启用标准事实模型。
     */
    private boolean structuredEnabled = true;

    /**
     * 是否启用 Java 确定性 Markdown 渲染。
     */
    private boolean deterministicMarkdownEnabled = true;
}