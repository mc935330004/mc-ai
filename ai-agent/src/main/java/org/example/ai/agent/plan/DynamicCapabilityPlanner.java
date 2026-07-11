package org.example.ai.agent.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.service.CapabilityDefinitionService;
import org.example.ai.agent.common.exception.BusinessException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import java.util.LinkedHashMap;

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
                        你的任务：
                        1. 根据用户问题，从【可用业务能力】中选择一个最匹配的能力。
                        2. 根据能力的 inputSchemaJson 生成接口入参。
                        3. 如果没有任何能力明确匹配，必须返回 matched=false。
                        4. 不允许为了完成任务而选择一个含义相近但实际不匹配的能力。
                        5. capabilityCode 必须完全复制可用能力中的编码，禁止自行编造。
                        6. 只允许输出 JSON，不要输出 Markdown，不要解释。
                        找到匹配能力时输出：
                        {
                          "matched": true,
                          "capabilityCode": "可用能力中的完整编码",
                          "input": {
                            "参数名": "参数值"
                          },
                          "reason": "选择该能力的原因",
                          "clarifyQuestion": null
                        }
                
                        没有匹配能力时输出：
                        {
                          "matched": false,
                          "capabilityCode": null,
                          "input": {},
                          "reason": "没有匹配能力的原因",
                          "clarifyQuestion": "需要用户补充的信息"
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
            // 解析 JSON
            DynamicCapabilityPlan plan = objectMapper.readValue(content, DynamicCapabilityPlan.class);
            // 没有匹配业务能力时，不补参数，也不能继续调用业务系统
            if (!plan.isMatched()) {
                plan.setCapabilityCode(null);
                plan.setInput(new LinkedHashMap<>());
                return plan;
            }
            // 模型只能选择数据库中真实存在并且已经启用的能力
            validateSelectedCapability(plan);

            // 根据 inputSchemaJson 自动补齐默认参数
            applyInputDefaults(plan);

            return plan;
        } catch (Exception e) {
            throw new BusinessException(400, "动态能力规划失败：" + content);
        }
    }
    /**
     * 根据能力的 inputSchemaJson 自动补齐默认参数。
     *
     * 例如 current 未传时补 1，size 未传时补 10。
     * 默认值完全来自能力配置，不在代码中写死具体接口参数。
     */
    private void applyInputDefaults(DynamicCapabilityPlan plan) {
        if (plan == null || !StringUtils.hasText(plan.getCapabilityCode())) {
            return;
        }
        CapabilityDefinition capability =capabilityDefinitionService.getEnabledByCode(plan.getCapabilityCode());
        if (capability == null || !StringUtils.hasText(capability.getInputSchemaJson())) {
            return;
        }
        try {
            if (plan.getInput() == null) {
                plan.setInput(new LinkedHashMap<>());
            }
            JsonNode schema = objectMapper.readTree(capability.getInputSchemaJson());
            JsonNode properties = schema.path("properties");

            if (!properties.isObject()) {
                return;
            }
            // 遍历 Schema 配置的参数，未传且存在 default 时自动补齐
            properties.fields().forEachRemaining(entry -> {
                String fieldName = entry.getKey();
                JsonNode fieldSchema = entry.getValue();
                if (!plan.getInput().containsKey(fieldName)
                        && fieldSchema.has("default")) {
                    Object defaultValue = objectMapper.convertValue(
                            fieldSchema.get("default"),
                            Object.class
                    );
                    plan.getInput().put(fieldName, defaultValue);
                }
            });
        } catch (Exception e) {
            throw new BusinessException(400,"能力【" + capability.getCapabilityName() + "】的 inputSchemaJson 配置不正确"
            );
        }
    }

    /**
     * 校验大模型选择的能力是否真实存在并且已经启用。
     *
     * 大模型只负责推荐能力，最终是否允许执行必须由代码确定。
     */
    private void validateSelectedCapability(DynamicCapabilityPlan plan) {
        if (!StringUtils.hasText(plan.getCapabilityCode())) {
            throw new BusinessException(400,"动态能力规划失败：模型没有返回 capabilityCode"
            );
        }
        CapabilityDefinition capability =capabilityDefinitionService.getEnabledByCode(plan.getCapabilityCode());
        if (capability == null) {
            throw new BusinessException( 400,"动态能力规划失败：能力不存在或未启用，能力编码："+ plan.getCapabilityCode()
            );
        }
        // 操作类型和确认要求必须来自数据库，不能使用模型自行生成的值
        plan.setCapabilityName(capability.getCapabilityName());
        plan.setSideEffect(capability.getSideEffect());
        plan.setRequireConfirm(Boolean.TRUE.equals(capability.getRequireConfirm()));
        if (plan.getInput() == null) {
            plan.setInput(new LinkedHashMap<>());
        }
    }
}