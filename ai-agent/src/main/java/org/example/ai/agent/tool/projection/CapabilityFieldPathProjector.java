package org.example.ai.agent.tool.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 受限字段路径投影器。
 *
 * 支持：
 * $.data.xxx
 * $.data.records[].id
 * $.data.settlementInfos[].settlements[].amount
 *
 * 不支持：
 * 1. JsonPath过滤表达式；
 * 2. 脚本；
 * 3. 函数调用；
 * 4. 动态执行表达式。
 *
 * 这样既支持任意层级数组，又不会引入动态执行风险。
 */
@Component
@RequiredArgsConstructor
public class CapabilityFieldPathProjector {

    private final ObjectMapper objectMapper;

    /**
     * 将一个字段路径投影到目标对象。
     */
    public void project(
            JsonNode sourceRoot,
            ObjectNode targetRoot,
            String path,
            String outputName) {

        if (sourceRoot == null || targetRoot == null || !StringUtils.hasText(path) || !StringUtils.hasText(outputName)) {
            return;
        }

        List<PathSegment> segments =
                parsePath(path);

        if (segments.isEmpty()) {
            return;
        }

        int firstArrayIndex =
                findNextArrayIndex(
                        segments,
                        0
                );

        /*
         * 没有数组通配符时，保持原来的扁平投影行为。
         *
         * 例如：
         * $.data.project.id
         * 最终输出：
         * {"id": "..."}
         */
        if (firstArrayIndex < 0) {
            JsonNode value =
                    readPath(
                            sourceRoot,
                            segments
                    );

            setValueIfPresent(
                    targetRoot,
                    outputName,
                    value
            );

            return;
        }

        /*
         * 读取第一个数组。
         *
         * $.data.settlementInfos[].xxx
         * sourceArray对应data.settlementInfos。
         */
        List<PathSegment> firstArrayPath =
                segments.subList(
                        0,
                        firstArrayIndex + 1
                );

        JsonNode sourceArray =
                readPath(
                        sourceRoot,
                        firstArrayPath
                );

        if (sourceArray == null
                || !sourceArray.isArray()) {
            return;
        }

        String firstArrayName =
                segments
                        .get(firstArrayIndex)
                        .name();

        ArrayNode targetArray =
                getOrCreateArray(
                        targetRoot,
                        firstArrayName
                );

        if (targetArray == null) {
            return;
        }

        projectArrayItems(
                sourceArray,
                targetArray,
                segments.subList(
                        firstArrayIndex + 1,
                        segments.size()
                ),
                outputName
        );
    }

    /**
     * 递归投影数组中的字段。
     */
    private void projectArrayItems(
            JsonNode sourceArray,
            ArrayNode targetArray,
            List<PathSegment> remainingSegments,
            String outputName) {

        for (int index = 0;
             index < sourceArray.size();
             index++) {

            JsonNode sourceItem =
                    sourceArray.get(index);

            if (sourceItem == null
                    || sourceItem.isNull()
                    || sourceItem.isMissingNode()) {
                continue;
            }

            /*
             * 路径结束在数组本身时：
             * 只允许复制标量数组元素。
             *
             * 如果数组元素是对象，则不能复制整个对象，
             * 防止绕过字段白名单。
             */
            if (remainingSegments.isEmpty()) {
                if (sourceItem.isValueNode()) {
                    setArrayValue(
                            targetArray,
                            index,
                            sourceItem
                    );
                }

                continue;
            }

            if (!sourceItem.isObject()) {
                continue;
            }

            ObjectNode targetItem =
                    getOrCreateObject(
                            targetArray,
                            index
                    );

            int nextArrayIndex =
                    findNextArrayIndex(
                            remainingSegments,
                            0
                    );

            /*
             * 后面没有更多数组，
             * 直接读取剩余叶子路径。
             */
            if (nextArrayIndex < 0) {
                JsonNode value =
                        readPath(
                                sourceItem,
                                remainingSegments
                        );

                setValueIfPresent(
                        targetItem,
                        outputName,
                        value
                );

                continue;
            }

            /*
             * 后面仍有嵌套数组。
             *
             * 例如：
             * settlements[].settlementAmount
             */
            List<PathSegment> nestedArrayPath =
                    remainingSegments.subList(
                            0,
                            nextArrayIndex + 1
                    );

            JsonNode nestedSourceArray =
                    readPath(
                            sourceItem,
                            nestedArrayPath
                    );

            if (nestedSourceArray == null
                    || !nestedSourceArray.isArray()) {
                continue;
            }

            String nestedArrayName =
                    remainingSegments
                            .get(nextArrayIndex)
                            .name();

            ArrayNode nestedTargetArray =
                    getOrCreateArray(
                            targetItem,
                            nestedArrayName
                    );

            if (nestedTargetArray == null) {
                continue;
            }

            projectArrayItems(
                    nestedSourceArray,
                    nestedTargetArray,
                    remainingSegments.subList(
                            nextArrayIndex + 1,
                            remainingSegments.size()
                    ),
                    outputName
            );
        }
    }

    /**
     * 读取不包含动态表达式的受限路径。
     */
    private JsonNode readPath(
            JsonNode root,
            List<PathSegment> segments) {

        JsonNode current = root;

        for (PathSegment segment : segments) {
            if (current == null
                    || current.isNull()
                    || current.isMissingNode()
                    || !current.isObject()) {
                return null;
            }

            current =
                    current.path(
                            segment.name()
                    );
        }

        return current;
    }

    private void setValueIfPresent(
            ObjectNode target,
            String outputName,
            JsonNode value) {

        if (value == null
                || value.isNull()
                || value.isMissingNode()) {
            return;
        }

        target.set(
                outputName,
                value.deepCopy()
        );
    }

    private ArrayNode getOrCreateArray(
            ObjectNode parent,
            String fieldName) {

        JsonNode existing =
                parent.get(fieldName);

        if (existing == null
                || existing.isNull()) {
            return parent.putArray(fieldName);
        }

        if (!existing.isArray()) {
            return null;
        }

        return (ArrayNode) existing;
    }

    private ObjectNode getOrCreateObject(
            ArrayNode array,
            int index) {

        while (array.size() <= index) {
            array.add(
                    objectMapper.createObjectNode()
            );
        }

        JsonNode existing =
                array.get(index);

        if (existing != null
                && existing.isObject()) {
            return (ObjectNode) existing;
        }

        ObjectNode replacement =
                objectMapper.createObjectNode();

        array.set(index, replacement);

        return replacement;
    }

    private void setArrayValue(
            ArrayNode array,
            int index,
            JsonNode value) {

        while (array.size() <= index) {
            array.addNull();
        }

        array.set(
                index,
                value.deepCopy()
        );
    }

    private int findNextArrayIndex(
            List<PathSegment> segments,
            int startIndex) {

        for (int index = startIndex;
             index < segments.size();
             index++) {

            if (segments.get(index).array()) {
                return index;
            }
        }

        return -1;
    }

    /**
     * 解析受限路径。
     *
     * 合法示例：
     * $.data.records[].id
     *
     * 非法的中括号表达式会直接拒绝。
     */
    private List<PathSegment> parsePath(
            String path) {

        if (!StringUtils.hasText(path)
                || !path.startsWith("$.")
                || path.length() <= 2) {
            return List.of();
        }

        String[] rawSegments =
                path.substring(2)
                        .split("\\.");

        List<PathSegment> result =
                new ArrayList<>();

        for (String rawSegment :
                rawSegments) {

            if (!StringUtils.hasText(rawSegment)) {
                return List.of();
            }

            boolean array =
                    rawSegment.endsWith("[]");

            String name =
                    array
                            ? rawSegment.substring(
                            0,
                            rawSegment.length() - 2
                    )
                            : rawSegment;

            /*
             * 除结尾的[]外，不允许出现其他中括号语法。
             */
            if (!StringUtils.hasText(name)
                    || name.contains("[")
                    || name.contains("]")) {
                return List.of();
            }

            result.add(
                    new PathSegment(
                            name,
                            array
                    )
            );
        }

        return List.copyOf(result);
    }

    private record PathSegment(
            String name,
            boolean array) {
    }
}