package org.example.ai.agent.business.intent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
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
            7. 是否需要展示异常人员明细：anomalyPeopleRequested。
            8. 是否要求按项目起止日期查询：projectPeriodRequested。
            9. 是否明确查询单个业务指标：singleMetricRequested。
            输出要求：
            1. 只输出一个完整 JSON 对象，不要输出解释文字。
            2. subjectType 仅允许 PROJECT、PERSON、DEPARTMENT；无法判断时为 null。
            3. 日期使用 yyyy-MM-dd；无法判断时为 null。
            4. projectYear 只表达项目年度，不得据此生成 periodStart 或 periodEnd。
            5. periodStart 和 periodEnd 只来自用户明确的数据时间范围，不得据此生成 projectYear。
            6. PERSON、DEPARTMENT 的 datasetCodes 只允许 TRAVEL、ATTENDANCE、REIMBURSEMENT；用户提到打卡、缺卡或考勤时输出 ATTENDANCE，PUNCH 仅作为确定性输入别名，不主动输出。
            7. PERSON、DEPARTMENT 未明确点名业务类型时，datasetCodes 输出空数组，由后端选择默认全览。
            8. PROJECT 的 datasetCodes 使用大写字母、数字和下划线表达数据集语义，具体范围由项目全景配置决定；无法判断时为空数组。
            9. refresh 仅在用户明确要求刷新或最新数据时为 true，否则为 false。
            10. exportFormat 仅允许 XLSX、DOCX、PDF；未要求导出时为 null。
            11. anomalyPeopleRequested 仅当用户明确询问谁、哪些人、人员名单或人员明细时为 true；该字段只控制展示，不改变可查询数据范围，也不能替代后端校验。
            12. projectPeriodRequested 仅当用户明确表达“项目期间”等按项目起止日期查询的语义时为 true；该字段不得用于生成 periodStart 或 periodEnd。
            13. singleMetricRequested 仅当用户明确询问一个金额、数量、比例、日期或状态指标时为 true。
            14. “人员费用已用金额”“合同金额”“预算使用率”等明确单指标问题为 true。
            15. “分析概算”“查看项目情况”“完整分析”不是单指标问题，必须为 false。
            16. PROJECT 的 datasetCodes 只表达模块语义，例如概算使用 BUDGET；不能生成 URL、SQL、接口名或认证信息。
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
              "exportFormat": null,
              "anomalyPeopleRequested": false,
              "projectPeriodRequested": false,
              "singleMetricRequested": false
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
            intent = objectMapper
                    .readerFor(BusinessQueryIntent.class)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(extractJson(content));
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
