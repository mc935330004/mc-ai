package org.example.ai.agent.workflow.answer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.exception.ErrorCode;
import org.example.ai.agent.workflow.answer.chunk.WorkflowAnswerChunk;
import org.example.ai.agent.workflow.answer.chunk.WorkflowAnswerChunkPlan;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 将 Artifact 中的全部 JSON 分块无损还原成原始安全载荷。
 *
 * 这是 WorkflowAnswerChunkPlanner 的逆过程：
 * 1. 对象分块按照 sourcePointer 合并；
 * 2. 数组分块按照 startIndex 恢复原始下标；
 * 3. 不允许覆盖冲突数据；
 * 4. 不截断、不抽样、不跳过任何分块。
 */
@Component
@RequiredArgsConstructor
public class ResultArtifactDocumentAssembler {

    private final ObjectMapper objectMapper;

    public JsonNode assemble(WorkflowAnswerChunkPlan chunkPlan) {

        if (chunkPlan == null || chunkPlan.chunks() == null
                || chunkPlan.chunks().isEmpty()) {

            throw invalidArtifact(
                    "上一轮结果没有可还原的数据分块"
            );
        }

        ObjectNode root = objectMapper.createObjectNode();

        for (WorkflowAnswerChunk chunk : chunkPlan.chunks()) {
            applyChunk(root, chunk);
        }

        /*
         * Artifact 根节点是 WorkflowAnswerModelPayload，
         * 必须包含 result 属性。
         */
        if (!root.has("result")) {
            throw invalidArtifact(
                    "上一轮结果快照缺少result数据"
            );
        }

        return root;
    }

    private void applyChunk(
            ObjectNode root,
            WorkflowAnswerChunk chunk) {

        try {
            JsonNode envelope = objectMapper.readTree(chunk.payloadJson());

            if (envelope == null
                    || !envelope.isObject()
                    || !envelope.has("data")) {

                throw invalidArtifact(
                        "上一轮结果分块信封结构异常"
                );
            }

            String sourcePointer =
                    envelope.path("sourcePointer")
                            .asText("");

            Integer startIndex =
                    envelope.has("startIndex")
                            ? envelope.get("startIndex")
                            .intValue()
                            : null;

            Integer endIndex =
                    envelope.has("endIndex")
                            ? envelope.get("endIndex")
                            .intValue()
                            : null;

            /*
             * 数据库列和JSON信封中的定位信息必须完全一致。
             */
            if (!Objects.equals(
                    sourcePointer,
                    chunk.sourcePointer())
                    || !Objects.equals(
                    startIndex,
                    chunk.startIndex())
                    || !Objects.equals(
                    endIndex,
                    chunk.endIndex())) {

                throw invalidArtifact(
                        "上一轮结果分块定位信息不一致"
                );
            }

            JsonNode data =envelope.get("data");

            apply(
                    root,
                    sourcePointer,
                    startIndex,
                    endIndex,
                    data
            );
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "上一轮结果分块还原失败",
                    exception
            );
        }
    }

    private void apply(
            ObjectNode root,
            String sourcePointer,
            Integer startIndex,
            Integer endIndex,
            JsonNode data) {

        List<String> segments =
                parsePointer(sourcePointer);

        /*
         * 空指针表示整个根对象。
         */
        if (segments.isEmpty()) {
            if (startIndex != null
                    || data == null
                    || !data.isObject()) {

                throw invalidArtifact(
                        "上一轮结果根分块类型异常"
                );
            }

            mergeObject(
                    root,
                    (ObjectNode) data
            );

            return;
        }

        JsonNode parent =
                navigateToParent(
                        root,
                        segments
                );

        String leaf =
                segments.get(
                        segments.size() - 1
                );

        if (parent.isObject()) {
            ObjectNode objectParent =
                    (ObjectNode) parent;

            JsonNode existing =
                    objectParent.get(leaf);

            objectParent.set(
                    leaf,
                    mergeValue(
                            existing,
                            data,
                            startIndex,
                            endIndex
                    )
            );

            return;
        }

        if (parent.isArray()) {
            ArrayNode arrayParent =
                    (ArrayNode) parent;

            int index =
                    parseArrayIndex(leaf);

            ensureArraySize(
                    arrayParent,
                    index + 1
            );

            JsonNode existing =
                    arrayParent.get(index);

            arrayParent.set(
                    index,
                    mergeValue(
                            existing,
                            data,
                            startIndex,
                            endIndex
                    )
            );

            return;
        }

        throw invalidArtifact(
                "上一轮结果分块父节点类型异常"
        );
    }

    /**
     * 导航到目标节点的父节点。
     *
     * JSON Pointer中下一段为数字时创建数组，
     * 否则创建对象。
     */
    private JsonNode navigateToParent(
            ObjectNode root,
            List<String> segments) {

        JsonNode current = root;

        for (int index = 0;
             index < segments.size() - 1;
             index++) {

            String segment =
                    segments.get(index);

            String nextSegment =
                    segments.get(index + 1);

            boolean nextIsArrayIndex =
                    isArrayIndex(nextSegment);

            if (current.isObject()) {
                ObjectNode objectNode =
                        (ObjectNode) current;

                JsonNode child =
                        objectNode.get(segment);

                if (child == null
                        || child.isNull()) {

                    child = nextIsArrayIndex
                            ? objectMapper
                            .createArrayNode()
                            : objectMapper
                            .createObjectNode();

                    objectNode.set(
                            segment,
                            child
                    );
                }

                if (!child.isContainerNode()) {
                    throw invalidArtifact(
                            "上一轮结果分块路径发生数据冲突"
                    );
                }

                current = child;
                continue;
            }

            if (current.isArray()) {
                ArrayNode arrayNode =
                        (ArrayNode) current;

                int arrayIndex =
                        parseArrayIndex(segment);

                ensureArraySize(
                        arrayNode,
                        arrayIndex + 1
                );

                JsonNode child =
                        arrayNode.get(arrayIndex);

                if (child == null
                        || child.isNull()) {

                    child = nextIsArrayIndex
                            ? objectMapper
                            .createArrayNode()
                            : objectMapper
                            .createObjectNode();

                    arrayNode.set(
                            arrayIndex,
                            child
                    );
                }

                if (!child.isContainerNode()) {
                    throw invalidArtifact(
                            "上一轮结果分块路径发生数据冲突"
                    );
                }

                current = child;
                continue;
            }

            throw invalidArtifact(
                    "上一轮结果分块路径无法还原"
            );
        }

        return current;
    }

    private JsonNode mergeValue(
            JsonNode existing,
            JsonNode data,
            Integer startIndex,
            Integer endIndex) {

        /*
         * 带startIndex的分块一定是数组片段。
         */
        if (startIndex != null) {
            if (endIndex == null
                    || startIndex < 0
                    || endIndex < startIndex
                    || data == null
                    || !data.isArray()) {

                throw invalidArtifact(
                        "上一轮结果数组分块范围异常"
                );
            }

            ArrayNode fragment =
                    (ArrayNode) data;

            if (fragment.size()
                    != endIndex - startIndex + 1) {

                throw invalidArtifact(
                        "上一轮结果数组分块长度异常"
                );
            }

            ArrayNode target;

            if (existing == null
                    || existing.isNull()) {

                target =
                        objectMapper.createArrayNode();

            } else if (existing.isArray()) {

                target =
                        (ArrayNode) existing;

            } else {
                throw invalidArtifact(
                        "上一轮结果数组分块类型冲突"
                );
            }

            ensureArraySize(
                    target,
                    endIndex + 1
            );

            for (int offset = 0;
                 offset < fragment.size();
                 offset++) {

                int targetIndex =
                        startIndex + offset;

                JsonNode oldValue =
                        target.get(targetIndex);

                JsonNode newValue =
                        fragment.get(offset);

                if (oldValue != null
                        && !oldValue.isNull()
                        && !oldValue.equals(newValue)) {

                    throw invalidArtifact(
                            "上一轮结果数组分块发生覆盖冲突"
                    );
                }

                target.set(
                        targetIndex,
                        newValue.deepCopy()
                );
            }

            return target;
        }

        /*
         * 同一个sourcePointer可能包含多个对象片段，
         * 因此对象必须合并，不能直接覆盖。
         */
        if (data != null && data.isObject()) {
            ObjectNode target;

            if (existing == null
                    || existing.isNull()) {

                target =
                        objectMapper.createObjectNode();

            } else if (existing.isObject()) {

                target =
                        (ObjectNode) existing;

            } else {
                throw invalidArtifact(
                        "上一轮结果对象分块类型冲突"
                );
            }

            mergeObject(
                    target,
                    (ObjectNode) data
            );

            return target;
        }

        if (existing != null
                && !existing.isNull()
                && !existing.equals(data)) {

            throw invalidArtifact(
                    "上一轮结果字段发生覆盖冲突"
            );
        }

        return data == null
                ? objectMapper.nullNode()
                : data.deepCopy();
    }

    private void mergeObject(
            ObjectNode target,
            ObjectNode source) {

        source.fields().forEachRemaining(
                field -> {

                    String name =
                            field.getKey();

                    JsonNode sourceValue =
                            field.getValue();

                    JsonNode targetValue =
                            target.get(name);

                    if (targetValue != null
                            && targetValue.isObject()
                            && sourceValue.isObject()) {

                        mergeObject(
                                (ObjectNode) targetValue,
                                (ObjectNode) sourceValue
                        );

                        return;
                    }

                    if (targetValue != null
                            && !targetValue.isNull()
                            && !targetValue.equals(
                            sourceValue)) {

                        throw invalidArtifact(
                                "上一轮结果对象字段发生覆盖冲突："
                                        + name
                        );
                    }

                    target.set(
                            name,
                            sourceValue.deepCopy()
                    );
                }
        );
    }

    private void ensureArraySize(
            ArrayNode arrayNode,
            int requiredSize) {

        while (arrayNode.size()
                < requiredSize) {

            arrayNode.addNull();
        }
    }

    private List<String> parsePointer(
            String sourcePointer) {

        if (sourcePointer == null
                || sourcePointer.isBlank()
                || "/".equals(sourcePointer)) {

            return List.of();
        }

        if (!sourcePointer.startsWith("/")) {
            throw invalidArtifact(
                    "上一轮结果sourcePointer格式异常"
            );
        }

        String[] values =
                sourcePointer.substring(1)
                        .split("/", -1);

        List<String> result =
                new ArrayList<>(values.length);

        for (String value : values) {
            result.add(
                    value.replace("~1", "/")
                            .replace("~0", "~")
            );
        }

        return result;
    }

    private boolean isArrayIndex(
            String value) {

        if (value == null
                || value.isBlank()) {
            return false;
        }

        for (int index = 0;
             index < value.length();
             index++) {

            if (!Character.isDigit(
                    value.charAt(index))) {

                return false;
            }
        }

        return true;
    }

    private int parseArrayIndex(
            String value) {

        if (!isArrayIndex(value)) {
            throw invalidArtifact(
                    "上一轮结果数组下标异常"
            );
        }

        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw invalidArtifact(
                    "上一轮结果数组下标超出范围"
            );
        }
    }

    private BusinessException invalidArtifact(
            String message) {

        return new BusinessException(
                ErrorCode.INTERNAL_ERROR,
                message
        );
    }
}