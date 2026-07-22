package org.example.ai.agent.tool.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.tool.FieldMeta;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.function.Function;

/**
 * 能力输出投影器。
 *
 * 核心职责：
 * 1. 根据已发布字段字典提取允许进入工作流的数据；
 * 2. 生成稳定英文机器字段；
 * 3. 生成中文展示字段；
 * 4. 防止完整原始业务响应进入普通工作流变量。
 *
 * 本类不负责：
 * 1. 调用业务接口；
 * 2. 判断业务调用是否成功；
 * 3. 执行 GraphSpec；
 * 4. 保存运行记录。
 */
@Component
@RequiredArgsConstructor
public class CapabilityOutputProjector {

    private final ObjectMapper objectMapper;

    /**
     * 同时生成工作流机器视图和中文展示视图。
     *
     * @param raw             业务接口原始响应，仅用于字段路径投影
     * @param interpretedData responseBindingJson 已提取的数据
     * @param fields          已发布字段字典
     * @return 双通道安全数据
     */
    public CapabilityOutputProjection project(
            Object raw,
            Object interpretedData,
            List<FieldMeta> fields) {

        /*
         * 没有字段字典或者没有原始响应时，
         * 只能使用 responseBinding 已提取的数据。
         *
         * 这里不能直接返回 raw，
         * 防止敏感业务字段和认证信息进入工作流。
         */
        if (fields == null
                || fields.isEmpty()
                || raw == null) {

            return new CapabilityOutputProjection(
                    interpretedData,
                    interpretedData
            );
        }

        JsonNode root =
                objectMapper.valueToTree(raw);

        /*
         * workflowData 使用所有已发布字段的机器名称。
         *
         * visible=0 的字段可以参与工作流内部计算，
         * 但不能进入 displayData。
         */
        Object workflowData =
                projectFields(
                        root,
                        fields,
                        this::machineName
                );

        /*
         * displayData 只保留允许展示的字段。
         */
        List<FieldMeta> visibleFields =
                fields.stream()
                        .filter(this::isDisplayVisible)
                        .toList();

        Object displayData =
                projectFields(
                        root,
                        visibleFields,
                        this::displayName
                );

        return new CapabilityOutputProjection(
                workflowData,
                displayData
        );
    }

    /**
     * 根据字段名称解析策略生成投影结果。
     */
    private Object projectFields(
            JsonNode root,
            List<FieldMeta> fields,
            Function<FieldMeta, String> nameResolver) {

        ObjectNode result =
                objectMapper.createObjectNode();

        for (FieldMeta field : fields) {
            if (field == null
                    || !StringUtils.hasText(
                    field.getPath())) {
                continue;
            }

            String outputName =
                    nameResolver.apply(field);

            if (!StringUtils.hasText(outputName)) {
                continue;
            }

            /*
             * 数组字段示例：
             * $.data.records[].projectCode
             */
            if (field.getPath().contains("[]")) {
                projectArrayField(
                        root,
                        result,
                        field,
                        outputName
                );
                continue;
            }

            /*
             * 普通字段示例：
             * $.data.current
             */
            JsonNode value =
                    readBySimplePath(
                            root,
                            field.getPath()
                    );

            if (value == null
                    || value.isMissingNode()
                    || value.isNull()) {
                continue;
            }

            result.set(outputName, value);
        }

        return objectMapper.convertValue(
                result,
                Object.class
        );
    }

    /**
     * 读取受限 JSON 路径。
     *
     * 当前支持：
     * $.data.xxx
     * $.data.records
     * $.data.project.id
     *
     * 不使用 JsonPath、SpEL 或脚本，
     * 避免引入动态执行能力。
     */
    private JsonNode readBySimplePath(
            JsonNode root,
            String path) {

        if (root == null
                || !StringUtils.hasText(path)
                || !path.startsWith("$.")) {
            return null;
        }

        JsonNode current = root;

        String[] parts =
                path.substring(2)
                        .split("\\.");

        for (String part : parts) {
            if (current == null
                    || current.isMissingNode()
                    || current.isNull()) {
                return null;
            }

            current = current.path(part);
        }

        return current;
    }

    /**
     * 投影数组字段。
     *
     * 例如：
     * $.data.records[].id
     * $.data.records[].projectCode
     */
    private void projectArrayField(
            JsonNode root,
            ObjectNode result,
            FieldMeta field,
            String outputName) {

        String path = field.getPath();

        int arrayIndex =
                path.indexOf("[]");

        if (arrayIndex < 0) {
            return;
        }

        /*
         * $.data.records[].id
         * 转换为：
         * arrayPath = $.data.records
         * leafPath  = id
         */
        String arrayPath =
                path.substring(0, arrayIndex);

        String leafPath =
                path.length() > arrayIndex + 3
                        ? path.substring(arrayIndex + 3)
                        : "";

        if (!StringUtils.hasText(leafPath)) {
            return;
        }

        JsonNode sourceArray =
                readBySimplePath(
                        root,
                        arrayPath
                );

        if (sourceArray == null
                || !sourceArray.isArray()) {
            return;
        }

        /*
         * 数组名称保持机器字段结构。
         *
         * $.data.records 对应 records。
         */
        String arrayName =
                arrayPath.substring(
                        arrayPath.lastIndexOf('.') + 1
                );

        ArrayNode targetArray =
                result.withArray(arrayName);

        for (int index = 0;
             index < sourceArray.size();
             index++) {

            /*
             * 先补齐目标数组位置，
             * 保证目标记录顺序与业务接口返回顺序一致。
             */
            while (targetArray.size() <= index) {
                targetArray.add(
                        objectMapper.createObjectNode()
                );
            }

            ObjectNode targetRow =
                    (ObjectNode) targetArray.get(index);

            JsonNode sourceRow =
                    sourceArray.get(index);

            if (sourceRow == null
                    || !sourceRow.isObject()) {
                continue;
            }

            JsonNode value =
                    readBySimplePath(
                            sourceRow,
                            "$." + leafPath
                    );

            if (value == null
                    || value.isMissingNode()
                    || value.isNull()) {
                continue;
            }

            /*
             * 同一条列表记录的多个字段会写入同一个对象。
             */
            targetRow.set(outputName, value);
        }
    }

    /**
     * 获取稳定机器字段名称。
     *
     * 优先使用字段字典 fieldName。
     * 兼容历史数据：fieldName 为空时从字段路径末段提取。
     */
    private String machineName(
            FieldMeta field) {

        if (StringUtils.hasText(
                field.getName())) {

            return field.getName().trim();
        }

        String path = field.getPath();

        if (!StringUtils.hasText(path)) {
            return "";
        }

        String normalizedPath =
                path.replace("[]", "");

        return normalizedPath.substring(
                normalizedPath.lastIndexOf('.') + 1
        );
    }

    /**
     * 获取用户展示字段名称。
     *
     * 优先使用中文名称，
     * 没有中文名称时回退到机器字段名称。
     */
    private String displayName(
            FieldMeta field) {

        if (StringUtils.hasText(
                field.getCnName())) {

            return field.getCnName().trim();
        }

        return machineName(field);
    }

    /**
     * visible=0 的字段只能供工作流内部使用，
     * 不能进入前端和大模型展示数据。
     */
    private boolean isDisplayVisible(
            FieldMeta field) {

        return field != null
                && !Integer.valueOf(0)
                .equals(field.getVisible());
    }
}