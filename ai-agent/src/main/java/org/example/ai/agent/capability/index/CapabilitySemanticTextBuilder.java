package org.example.ai.agent.capability.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 构建能力向量化文本。
 *
 * 不能只向量化 capabilityName。
 * 应尽可能包含业务域、适用场景、参数语义和操作语义。
 */
@Component
@RequiredArgsConstructor
public class CapabilitySemanticTextBuilder {

    private final ObjectMapper objectMapper;

    public String build( CapabilityDefinition capability) {

        StringBuilder builder =new StringBuilder();
        append(builder,
                "能力名称",
                capability.getCapabilityName());

        append(builder,
                "能力编码",
                capability.getCapabilityCode()
        );

        append(builder,
                "业务域",
                capability.getDomain()
        );

        append(builder,
                "业务模块",
                capability.getModuleName()
        );

        append(builder,
                "适用场景",
                capability.getDescription()
        );

        append(builder,"操作类型",buildOperationMeaning(capability.getSideEffect()));

        List<String> parameterDescriptions =
                readParameterDescriptions(
                        capability.getInputSchemaJson()
                );

        if (!parameterDescriptions.isEmpty()) {
            append(
                    builder,
                    "可用查询条件和参数",
                    String.join("；", parameterDescriptions)
            );
        }

        /*
         * exampleJson 如果包含真实业务示例，
         * 对自然语言语义召回有帮助。
         */
        append(
                builder,
                "调用示例",
                capability.getExampleJson()
        );

        return builder.toString().trim();
    }

    /**
     * 从 JSON Schema 中提取字段名、说明和枚举。
     */
    private List<String> readParameterDescriptions(
            String schemaJson) {

        if (!StringUtils.hasText(schemaJson)) {
            return List.of();
        }

        try {
            JsonNode root =objectMapper.readTree(schemaJson);

            JsonNode properties =root.path("properties");

            if (!properties.isObject()) {
                return List.of();
            }

            List<String> result = new ArrayList<>();

            properties.fields().forEachRemaining(entry -> {
                String fieldName = entry.getKey();
                JsonNode field = entry.getValue();

                StringBuilder description = new StringBuilder(fieldName);

                if (field.hasNonNull("description")) {
                    description.append("：")
                            .append(field.get("description").asText());
                }

                if (field.path("enum").isArray()) {
                    description.append("，可选值：").append(field.get("enum"));
                }
                result.add(description.toString());
            });

            return result;
        } catch (Exception exception) {
            /*
             * Schema 合法性已经由能力发布流程校验。
             * 这里构建索引时降级，不阻断整个重建任务。
             */
            return List.of();
        }
    }

    private String buildOperationMeaning(
            String sideEffect) {

        if ("WRITE".equalsIgnoreCase(sideEffect)) {
            return "写操作，用于新增、创建、修改、提交或审批；"
                    + "不用于普通查询";
        }

        if ("DANGEROUS".equalsIgnoreCase(sideEffect)) {
            return "危险操作，禁止自动执行";
        }

        return "只读查询，用于获取列表、详情、统计或状态；"
                + "不用于新增、修改和删除";
    }

    private void append(
            StringBuilder builder,
            String label,
            String value) {

        if (!StringUtils.hasText(value)) {
            return;
        }

        builder.append(label)
                .append("：")
                .append(value.trim())
                .append("\n");
    }
}