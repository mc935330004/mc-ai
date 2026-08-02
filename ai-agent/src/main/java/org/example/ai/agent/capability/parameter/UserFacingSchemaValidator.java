package org.example.ai.agent.capability.parameter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.exception.BusinessException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 中文注释：发布时校验所有必填字段都配置了用户可读名称。
 */
@Component
@RequiredArgsConstructor
public class UserFacingSchemaValidator {

    private final ObjectMapper objectMapper;

    /**
     * 中文注释：校验普通能力的输入 Schema。
     */
    public void validateCapabilitySchema(String capabilityCode,String schemaJson) {
        JsonNode schema = readJson(
                schemaJson,
                "能力 " + capabilityCode);

        validateRequiredLabels(
                "能力 " + capabilityCode,
                schema
        );
    }

    /**
     * 中文注释：从工作流 GraphSpec 中读取并校验 inputSchema。
     */
    public void validateWorkflowGraph(String workflowCode,
            String graphSpecJson) {
        JsonNode graph = readJson(
                graphSpecJson,
                "工作流 " + workflowCode
        );

        JsonNode inputSchema =
                graph.path("inputSchema");

        if (inputSchema.isMissingNode()
                || inputSchema.isNull()) {
            return;
        }

        validateRequiredLabels(
                "工作流 " + workflowCode,
                inputSchema
        );
    }

    /**
     * 中文注释：收集缺少 title 或 x-user-label 的必填字段。
     */
    private void validateRequiredLabels(
            String owner,
            JsonNode schema) {
        List<String> unlabeledPaths =
                new ArrayList<>();

        collectUnlabeledRequiredFields(
                schema,
                "$",
                unlabeledPaths
        );

        if (!unlabeledPaths.isEmpty()) {
            throw new BusinessException(
                    400,
                    owner
                            + " 的以下必填字段未配置用户名称："
                            + String.join(
                                    "、",
                                    unlabeledPaths
                            )
                            + "。请配置 title 或 x-user-label。"
            );
        }
    }

    /**
     * 中文注释：递归检查对象及数组中的嵌套必填字段。
     */
    private void collectUnlabeledRequiredFields(
            JsonNode schema,
            String path,
            List<String> result) {
        if (schema == null
                || schema.isMissingNode()
                || schema.isNull()) {
            return;
        }

        JsonNode properties =
                schema.path("properties");

        JsonNode required =
                schema.path("required");

        if (required.isArray()) {
            for (JsonNode requiredName : required) {
                String fieldName =
                        requiredName.asText();

                JsonNode fieldSchema =
                        properties.path(fieldName);

                if (!hasUserLabel(fieldSchema)) {
                    result.add(
                            path + "." + fieldName
                    );
                }
            }
        }

        if (properties.isObject()) {
            Iterator<Map.Entry<String, JsonNode>>
                    fields = properties.fields();

            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field =
                        fields.next();

                collectUnlabeledRequiredFields(
                        field.getValue(),
                        path + "." + field.getKey(),
                        result
                );
            }
        }
        if ("array".equals(schema.path("type").asText())) {
            collectUnlabeledRequiredFields(
                    schema.path("items"),
                    path + "[]",
                    result
            );
        }
    }

    /**
     * 中文注释：自定义用户名称优先，标准 title 作为后备。
     */
    private boolean hasUserLabel(JsonNode fieldSchema) {
        if (fieldSchema == null
                || fieldSchema.isMissingNode()) {
            return false;
        }

        return StringUtils.hasText(
                fieldSchema.path(
                        "x-user-label"
                ).asText()
        ) || StringUtils.hasText(
                fieldSchema.path(
                        "title"
                ).asText()
        );
    }

    /**
     * 中文注释：发布校验失败时返回配置错误，不进入运行阶段。
     */
    private JsonNode readJson(
            String json,
            String owner ) {
        if (!StringUtils.hasText(json)) {
            throw new BusinessException(
                    400,
                    owner + " 的 Schema 不能为空"
            );
        }

        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            throw new BusinessException(
                    400,
                    owner + " 的 Schema 不是合法JSON"
            );
        }
    }
}