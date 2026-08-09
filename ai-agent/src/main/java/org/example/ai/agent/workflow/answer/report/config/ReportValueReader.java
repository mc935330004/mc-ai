package org.example.ai.agent.workflow.answer.report.config;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 读取报告定义中的受限字段路径。
 *
 * 仅支持点路径和数组展开，不执行动态表达式。
 */
@Component
public class ReportValueReader {

    public List<JsonNode> readMany(JsonNode root,String path) {

        if (root == null  || root.isNull() || !StringUtils.hasText(path)) {
            return List.of();
        }

        List<PathSegment> segments =  parse(path);

        List<JsonNode> current = List.of(root);

        for (PathSegment segment : segments) {
            current = readSegment(
                    current,
                    segment
            );

            if (current.isEmpty()) {
                return List.of();
            }
        }

        return List.copyOf(current);
    }

    public JsonNode readScalar(
            JsonNode root,
            String path) {
        List<JsonNode> values =readMany(root, path);
        if (values.isEmpty()) {
            return null;
        }

        if (values.size() > 1) {
            throw new IllegalStateException(
                    "报告标量路径返回了多个值：" + path
            );
        }
        JsonNode value = values.get(0);
        if (value == null  || value.isNull() || value.isMissingNode()) {
            return null;
        }

        if (value.isContainerNode()) {
            throw new IllegalStateException(
                    "报告单元格不能展示对象或数组："
                            + path
            );
        }

        return value;
    }

    private List<JsonNode> readSegment(
            List<JsonNode> source,
            PathSegment segment) {

        List<JsonNode> result = new ArrayList<>();

        for (JsonNode parent : source) {
            if (parent == null || !parent.isObject()) {
                continue;
            }

            JsonNode value = parent.get(segment.name());

            if (value == null
                    || value.isNull()
                    || value.isMissingNode()) {
                continue;
            }

            if (!segment.array()) {
                result.add(value);
                continue;
            }

            if (!value.isArray()) {
                throw new IllegalStateException(
                        "报告数组路径对应的数据不是数组："
                                + segment.name()
                );
            }

            value.forEach(result::add);
        }

        return result;
    }

    private List<PathSegment> parse(String path) {
        String normalized = path.trim();

        if (normalized.startsWith("$.")) {
            normalized = normalized.substring(2);
        }

        String[] rawSegments =
                normalized.split("\\.");

        List<PathSegment> result =
                new ArrayList<>();

        for (String rawSegment : rawSegments) {
            boolean array =
                    rawSegment.endsWith("[]");

            String name = array
                    ? rawSegment.substring(
                    0,
                    rawSegment.length() - 2
            )
                    : rawSegment;

            if (!StringUtils.hasText(name)) {
                throw new IllegalStateException(
                        "报告路径包含空字段"
                );
            }

            result.add(
                    new PathSegment(name, array)
            );
        }

        return List.copyOf(result);
    }

    private record PathSegment(
            String name,
            boolean array) {
    }
}