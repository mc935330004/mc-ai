package org.example.ai.agent.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.service.CapabilityDefinitionService;
import org.example.ai.agent.common.exception.BusinessException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * 动态能力规划器。
 *
 * 作用：
 * 1. 从数据库读取已启用能力
 * 2. 让大模型根据用户问题选择 capabilityCode
 * 3. 生成业务接口入参
 */
@Component
@RequiredArgsConstructor
public class DynamicCapabilityPlanner {

    private final CapabilityDefinitionService capabilityDefinitionService;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public DynamicCapabilityPlan plan(String userQuestion) {
        String capabilitiesPrompt = capabilityDefinitionService.buildEnabledCapabilitiesPrompt();

        String systemPrompt = """
                你是企业 PM 系统的业务能力选择器。
                你只能从【可用业务能力】中选择一个 capabilityCode。
                你必须根据用户问题和能力入参说明，生成该能力需要的 input。
                只允许输出 JSON，不要输出 Markdown，不要解释。
                
                JSON 格式：
                {
                  "capabilityCode": "能力编码",
                  "input": {
                    "参数名": "参数值"
                  },
                  "reason": "选择原因"
                }
                """;

        String userPrompt = """
                用户问题：
                %s
                
                %s
                """.formatted(userQuestion, capabilitiesPrompt);
        String content = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();
        try {
            return objectMapper.readValue(content, DynamicCapabilityPlan.class);
        } catch (Exception e) {
            throw new BusinessException(400, "动态能力规划失败：" + content);
        }
    }
}