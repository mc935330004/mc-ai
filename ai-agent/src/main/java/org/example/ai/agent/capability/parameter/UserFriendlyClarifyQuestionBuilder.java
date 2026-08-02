package org.example.ai.agent.capability.parameter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

/**
 * 中文注释：将内部 JSON Path 校验结果转换成用户可读的补充提示。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserFriendlyClarifyQuestionBuilder {

    private final ObjectMapper objectMapper;

    /**
     * 中文注释：只展示 Schema 配置的用户名称，绝不展示字段名和 JSON Path。
     */
    public String build(String businessName, String schemaJson,CapabilityInputValidationResult validation) {
        List<String> missingLabels =
                resolveMissingLabels(schemaJson, validation.getMissingParameters());

        String target = StringUtils.hasText(businessName)
                ? "“" + businessName.trim() + "”"
                : "当前查询";

        if (!missingLabels.isEmpty()) {
            return "为了准确完成"
                    + target
                    + "，请提供："
                    + String.join("、", missingLabels)
                    + "。";
        }

        if (!validation.getValidationErrors().isEmpty()) {
            /*
             * 中文注释：格式错误可能包含内部路径，
             * 这里只返回统一的用户提示。
             */
            return "为了准确完成"
                    + target
                    + "，请检查已输入的信息并重新提供。";
        }

        return "为了准确完成"
                + target
                + "，请补充必要的查询信息。";
    }

    /**
     * 中文注释：根据缺失路径查找 Schema 中配置的用户显示名称。
     */
    private List<String> resolveMissingLabels(
            String schemaJson,
            List<String> missingPaths ) {
        if (!StringUtils.hasText(schemaJson)
                || missingPaths == null
                || missingPaths.isEmpty()) {
            return List.of();
        }

        try {
            JsonNode root =
                    objectMapper.readTree(schemaJson);

            return missingPaths.stream()
                    .map(path ->
                            resolveFieldSchema(root, path)
                    )
                    .filter(Objects::nonNull)
                    .map(this::readUserLabel)
                    .filter(StringUtils::hasText)
                    .distinct()
                    .toList();
        } catch (Exception exception) {
            // 中文注释：Schema 解析失败时返回通用提示，不泄露内部错误。
            log.warn(
                    "生成用户参数提示时解析Schema失败",
                    exception
            );
            return List.of();
        }
    }

    /**
     * 中文注释：将 $.projectKeys 等路径定位到对应字段 Schema。
     */
    private JsonNode resolveFieldSchema(
            JsonNode root,
            String jsonPath ) {
        if (root == null
                || !StringUtils.hasText(jsonPath)) {
            return null;
        }

        String normalizedPath = jsonPath
                .replaceFirst("^\\$\\.?", "")
                .replaceAll("\\[\\d+]", "");

        if (!StringUtils.hasText(normalizedPath)) {
            return null;
        }

        JsonNode current = root;

        for (String segment :
                normalizedPath.split("\\.")) {
            if (segment.matches("\\d+")) {
                current = current.path("items");
                continue;
            }

            if ("array".equals(
                    current.path("type").asText()
            )) {
                current = current.path("items");
            }

            current = current
                    .path("properties")
                    .path(segment);

            if (current.isMissingNode()) {
                return null;
            }
        }

        return current;
    }

    /**
     * 中文注释：优先读取自定义用户名称，其次读取标准 JSON Schema title。
     */
    private String readUserLabel(JsonNode fieldSchema) {
        String userLabel =
                fieldSchema.path("x-user-label").asText();

        if (StringUtils.hasText(userLabel)) {
            return userLabel.trim();
        }

        String title =
                fieldSchema.path("title").asText();

        return StringUtils.hasText(title)
                ? title.trim()
                : null;
    }
}