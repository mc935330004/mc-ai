package org.example.ai.agent.capability.parameter;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.modelusage.ModelCallContext;
import org.example.ai.agent.common.modelusage.TrackedChatClientService;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 基于大模型的接口参数提取器。
 *
 * 与能力选择器的区别：
 *
 * 1. 这里只能看到一个已经确定的能力
 * 2. 不能修改 capabilityCode
 * 3. 不能调用接口
 * 4. 只负责把自然语言转换成结构化参数
 */
@Component
@RequiredArgsConstructor
public class LlmCapabilityParameterExtractor implements CapabilityParameterExtractor {

    private final ObjectMapper objectMapper;
    private final TrackedChatClientService trackedChatClientService;

    @Override
    public CapabilityParameterExtractionResult extract(
            String userQuestion,
            CapabilityDefinition capability,
            ModelCallContext callContext) {

        if (capability == null) {
            throw new BusinessException(400,
                    "参数提取失败：业务能力不能为空");
        }

        if (!StringUtils.hasText( capability.getInputSchemaJson())) {
            throw new BusinessException(400,
                    "参数提取失败：能力【" + capability.getCapabilityName() + "】没有配置 inputSchemaJson");
        }

        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(userQuestion,capability);

        /*
         * 参数提取同样需要低随机性。
         */
        ChatOptions.Builder<?> options =
                ChatOptions.builder()
                        .temperature(0.0D)
                        .topP(0.1D);

        ChatResponse response = trackedChatClientService.call(
                        callContext,
                        systemPrompt,
                        userPrompt,
                        options);

        String content =response.getResult().getOutput().getText();

        try {
            String json = extractJson(content);

            CapabilityParameterExtractionResult result =objectMapper.readValue(json, CapabilityParameterExtractionResult.class );

            if (result.getInput() == null) {
                result.setInput(new java.util.LinkedHashMap<>());
            }

            if (result.getMissingParameters() == null) {
                result.setMissingParameters(new java.util.ArrayList<>());
            }
            return result;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(
                    400,
                    "业务能力参数提取失败，模型返回内容："
                            + content
            );
        }
    }

    /**
     * 参数提取系统提示词。
     */
    private String buildSystemPrompt() {
        return """
                你是企业业务接口参数提取器。

                当前业务能力已经由上游选择完成。
                你的任务只是根据用户问题和 inputSchemaJson 提取接口参数。

                必须遵守以下规则：

                1. 不允许选择或修改 capabilityCode。
                2. input 中只能出现 inputSchemaJson.properties 定义的字段。
                3. 用户没有明确提供的业务值禁止编造。
                4. JSON Schema 中存在 default 的参数可以不输出，由后端补齐。
                5. required 参数缺失时，放入 missingParameters。
                6. 数字字段输出 JSON 数字，不要输出带单位的字符串。
                7. boolean 字段输出 true 或 false。
                8. 日期格式严格遵守 Schema 中的 format 或 description。
                9. 不要把用户的完整问题放入任意参数。
                10. 只允许输出 JSON，不要输出 Markdown，不要解释。
                11. 召回分数只用于候选初筛。最终必须根据用户真实意图和能力用途判断，禁止仅选择分数最高的能力。

                输出格式：

                {
                  "input": {
                    "参数名": "参数值"
                  },
                  "missingParameters": [
                    "缺失的必填参数名"
                  ],
                  "reason": "参数提取说明"
                }
                """;
    }

    /**
     * 参数提取用户提示词。
     *
     * 这里仅提供唯一能力，不再提供其他候选接口。
     */
    private String buildUserPrompt( String userQuestion, CapabilityDefinition capability) {
        return """
                【用户问题】
                %s

                【已确定的业务能力】
                能力编码：%s
                能力名称：%s
                能力说明：%s
                操作类型：%s

                【接口入参 JSON Schema】
                %s

                【接口调用示例】
                %s
                """.formatted(
                safe(userQuestion),
                safe(capability.getCapabilityCode()),
                safe(capability.getCapabilityName()),
                safe(capability.getDescription()),
                safe(capability.getSideEffect()),
                safe(capability.getInputSchemaJson()),
                safe(capability.getExampleJson())
        );
    }

    /**
     * 从模型文本中提取 JSON 对象。
     */
    private String extractJson(String content) {
        if (!StringUtils.hasText(content)) {
            throw new BusinessException(
                    400,
                    "参数提取失败：模型返回内容为空"
            );
        }

        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');

        if (start < 0 || end < start) {
            throw new BusinessException( 400, "参数提取失败：模型没有返回合法 JSON");
        }

        return content.substring(start, end + 1);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}