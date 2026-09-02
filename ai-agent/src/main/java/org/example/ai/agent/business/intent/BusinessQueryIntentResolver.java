package org.example.ai.agent.business.intent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.exception.ErrorCode;
import org.example.ai.agent.common.modelusage.ModelCallContext;
import org.example.ai.agent.common.modelusage.TrackedChatClientService;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 将自然语言解析为结构化业务查询语义，并在返回前执行确定性边界校验。
 */
@Component
@RequiredArgsConstructor
public class BusinessQueryIntentResolver {

    private static final String SYSTEM_PROMPT = """
            你是企业 PM 系统的业务查询语义解析器。
            你的唯一任务是把用户表达转换为一个结构化查询意图。

            只允许识别以下语义：
            1. 业务主体及定位信息：subjectType、projectCode、personName、employeeNo。
            2. 项目年度：projectYear。
            3. 业务数据时间范围：periodStart、periodEnd。
            4. 请求的数据集语义：datasetCodes。
            5. 是否要求刷新：refresh。
            6. 导出格式：exportFormat。

            输出要求：
            1. 只输出一个完整 JSON 对象，不要输出解释文字。
            2. subjectType 仅允许 PROJECT、PERSON、DEPARTMENT；无法判断时为 null。
            3. 日期使用 yyyy-MM-dd；无法判断时为 null。
            4. projectYear 只表达项目年度，不得据此生成 periodStart 或 periodEnd。
            5. periodStart 和 periodEnd 只来自用户明确的数据时间范围，不得据此生成 projectYear。
            6. datasetCodes 使用大写字母、数字和下划线表达数据集语义；无法判断时为空数组。
            7. refresh 仅在用户明确要求刷新或最新数据时为 true，否则为 false。
            8. exportFormat 仅允许 XLSX、DOCX、PDF；未要求导出时为 null。

            返回字段必须完整：
            {
              "subjectType": null,
              "projectCode": null,
              "personName": null,
              "employeeNo": null,
              "projectYear": null,
              "periodStart": null,
              "periodEnd": null,
              "datasetCodes": [],
              "refresh": false,
              "exportFormat": null
            }
            """;

    private final TrackedChatClientService trackedChatClientService;
    private final ObjectMapper objectMapper;
    private final BusinessIntentValidator validator;

    /**
     * 解析用户输入，并只返回经过确定性校验的业务查询意图。
     */
    public BusinessQueryIntent resolve(
            String userInput,
            ModelCallContext context
    ) {
        ChatResponse response = trackedChatClientService.call(
                context,
                SYSTEM_PROMPT,
                "用户输入：\n" + userInput,
                ChatOptions.builder()
                        .temperature(0.0D)
                        .topP(0.1D)
        );

        if (response == null
                || response.getResult() == null
                || response.getResult().getOutput() == null) {
            throw new BusinessException(400, "业务查询意图解析失败：模型未返回有效内容");
        }

        String content = response.getResult().getOutput().getText();
        final BusinessQueryIntent intent;
        try {
            intent = objectMapper.readValue(
                    extractJson(content),
                    BusinessQueryIntent.class
            );
        } catch (JsonProcessingException ignored) {
            // 原始模型输出可能包含敏感业务文本，异常中只保留确定性错误描述。
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "业务查询意图解析失败：模型返回的 JSON 不合法"
            );
        }
        return validator.validate(intent);
    }

    /**
     * 兼容模型偶尔返回 Markdown JSON 代码块，只读取其中完整对象。
     */
    private String extractJson(String content) {
        if (!StringUtils.hasText(content)) {
            throw new BusinessException(400, "业务查询意图解析失败：模型返回内容为空");
        }

        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new BusinessException(400, "业务查询意图解析失败：模型没有返回合法 JSON");
        }
        return content.substring(start, end + 1);
    }
}
