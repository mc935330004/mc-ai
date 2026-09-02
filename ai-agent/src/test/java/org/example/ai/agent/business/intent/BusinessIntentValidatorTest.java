package org.example.ai.agent.business.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.modelusage.ModelCallContext;
import org.example.ai.agent.common.modelusage.TrackedChatClientService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 业务查询意图的不可变性、输入边界和模型解析安全契约测试。
 */
class BusinessIntentValidatorTest {

    private final BusinessIntentValidator validator = new BusinessIntentValidator();

    @Test
    void projectYearDoesNotPopulateDatasetPeriod() {
        BusinessQueryIntent intent = intent(
                2025,
                null,
                null,
                List.of("PROJECT_OVERVIEW"),
                null
        );

        BusinessQueryIntent validated = validator.validate(intent);

        assertThat(validated).isSameAs(intent);
        assertThat(validated.projectYear()).isEqualTo(2025);
        assertThat(validated.periodStart()).isNull();
        assertThat(validated.periodEnd()).isNull();
    }

    @Test
    void rejectsDatasetCodeThatLooksLikeExecutionTarget() {
        BusinessQueryIntent intent = intent(
                null,
                null,
                null,
                List.of("workflow:raw-http"),
                null
        );

        assertThatThrownBy(() -> validator.validate(intent))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("数据集编码");
    }

    @Test
    void rejectsPeriodWhoseEndPrecedesStart() {
        BusinessQueryIntent intent = intent(
                null,
                LocalDate.of(2025, 2, 1),
                LocalDate.of(2025, 1, 31),
                List.of("PROJECT_OVERVIEW"),
                null
        );

        assertThatThrownBy(() -> validator.validate(intent))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("结束日期")
                .hasMessageContaining("开始日期");
    }

    @Test
    void rejectsUnsupportedExportFormat() {
        BusinessQueryIntent intent = intent(
                null,
                null,
                null,
                List.of("PROJECT_OVERVIEW"),
                "CSV"
        );

        assertThatThrownBy(() -> validator.validate(intent))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("导出格式");
    }

    @Test
    void defensivelyCopiesDatasetCodes() {
        List<String> source = new ArrayList<>();
        source.add("PROJECT_OVERVIEW");

        BusinessQueryIntent intent = intent(
                null,
                null,
                null,
                source,
                null
        );
        source.add("PERSON_OVERVIEW");

        assertThat(intent.datasetCodes()).containsExactly("PROJECT_OVERVIEW");
        assertThatThrownBy(() -> intent.datasetCodes().add("ANOTHER_DATASET"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void normalizesNullDatasetCodesToEmptyImmutableList() {
        BusinessQueryIntent intent = intent(null, null, null, null, null);

        assertThat(intent.datasetCodes()).isEmpty();
        assertThatThrownBy(() -> intent.datasetCodes().add("PROJECT_OVERVIEW"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsNullIntentExplicitly() {
        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("业务查询意图");
    }

    @Test
    void resolverUsesSemanticOnlyPromptAndValidatesParsedIntent() throws Exception {
        TrackedChatClientService chatClientService = mock(TrackedChatClientService.class);
        ChatResponse response = mock(ChatResponse.class, RETURNS_DEEP_STUBS);
        when(response.getResult().getOutput().getText()).thenReturn(
                """
                ```json
                {
                  "subjectType": "PROJECT",
                  "projectCode": "P-1001",
                  "personName": null,
                  "employeeNo": null,
                  "projectYear": 2025,
                  "periodStart": null,
                  "periodEnd": null,
                  "datasetCodes": ["PROJECT_OVERVIEW"],
                  "refresh": false,
                  "exportFormat": "XLSX"
                }
                ```
                """
        );
        when(chatClientService.call(
                any(),
                anyString(),
                anyString(),
                any(ChatOptions.Builder.class)
        )).thenReturn(response);
        BusinessQueryIntentResolver resolver = new BusinessQueryIntentResolver(
                chatClientService,
                new ObjectMapper().findAndRegisterModules(),
                validator
        );
        ModelCallContext context = ModelCallContext.builder().build();

        BusinessQueryIntent resolved = resolver.resolve("导出 2025 年 P-1001 项目概览", context);

        assertThat(resolved.projectYear()).isEqualTo(2025);
        assertThat(resolved.periodStart()).isNull();
        assertThat(resolved.periodEnd()).isNull();
        assertThat(resolved.exportFormat()).isEqualTo("XLSX");

        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(chatClientService).call(
                same(context),
                systemPrompt.capture(),
                userPrompt.capture(),
                any(ChatOptions.Builder.class)
        );

        String prompts = (systemPrompt.getValue() + userPrompt.getValue())
                .toLowerCase(Locale.ROOT);
        assertThat(prompts)
                .doesNotContain("workflow")
                .doesNotContain("capability")
                .doesNotContain("api path")
                .doesNotContain("permission")
                .doesNotContain("raw schema")
                .doesNotContain("calculation");
    }

    private BusinessQueryIntent intent(
            Integer projectYear,
            LocalDate periodStart,
            LocalDate periodEnd,
            List<String> datasetCodes,
            String exportFormat
    ) {
        return new BusinessQueryIntent(
                BusinessSubjectType.PROJECT,
                "P-1001",
                null,
                null,
                projectYear,
                periodStart,
                periodEnd,
                datasetCodes,
                false,
                exportFormat
        );
    }
}
