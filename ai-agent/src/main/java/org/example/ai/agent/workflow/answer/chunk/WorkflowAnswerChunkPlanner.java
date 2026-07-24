package org.example.ai.agent.workflow.answer.chunk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 工作流安全结果分块器。
 *
 * 设计原则：
 * 1. 只处理已经完成字段隐藏和安全投影的数据；
 * 2. 每个分块都是合法JSON；
 * 3. 不允许通过substring粗暴截断JSON；
 * 4. 不允许静默丢弃任何记录；
 * 5. 超出系统安全上限时明确失败。
 */
@Component
public class WorkflowAnswerChunkPlanner {

    private final ObjectMapper objectMapper;
    private final int maxChunkChars;
    private final int maxChunks;

    public WorkflowAnswerChunkPlanner(
            ObjectMapper objectMapper,
            @Value("${ai.workflow.answer.chunk.max-chars:12000}")
            int maxChunkChars,
            @Value("${ai.workflow.answer.chunk.max-chunks:2000}")
            int maxChunks) {

        if (maxChunkChars <= 0) {
            throw new IllegalArgumentException(
                    "maxChunkChars必须大于0"
            );
        }

        if (maxChunks <= 0) {
            throw new IllegalArgumentException(
                    "maxChunks必须大于0"
            );
        }

        this.objectMapper = objectMapper;
        this.maxChunkChars = maxChunkChars;
        this.maxChunks = maxChunks;
    }

    /**
     * 创建完整分块计划。
     *
     * @param safePayload 已完成隐藏字段过滤的安全载荷
     */
    public WorkflowAnswerChunkPlan plan(
            Object safePayload) {

        try {
            JsonNode root =objectMapper.valueToTree(safePayload );

            String sourceJson =objectMapper.writeValueAsString(root );

            List<RawFragment> fragments =new ArrayList<>();

            splitNode(root,"",fragments);

            List<WorkflowAnswerChunk> chunks =new ArrayList<>(fragments.size());

            int chunkCharCount = 0;

            for (int index = 0;index < fragments.size();index++) {

                RawFragment fragment =fragments.get(index);

                String payloadJson =writeFragment(fragment);

                chunkCharCount +=payloadJson.length();

                chunks.add(
                        new WorkflowAnswerChunk(
                                index + 1,
                                fragment.sourcePointer(),
                                fragment.startIndex(),
                                fragment.endIndex(),
                                payloadJson,
                                sha256(payloadJson),
                                payloadJson.length()
                        )
                );
            }

            return new WorkflowAnswerChunkPlan(
                    chunks.size(),
                    sourceJson.length(),
                    chunkCharCount,
                    chunks
            );
        } catch (JsonProcessingException exception) {
            throw new WorkflowAnswerChunkException(
                    "WORKFLOW_ANSWER_CHUNK_SERIALIZE_FAILED",
                    "工作流结果分块序列化失败",
                    exception
            );
        }
    }

    /**
     * 根据节点类型递归分块。
     */
    private void splitNode(
            JsonNode node,
            String sourcePointer,
            List<RawFragment> fragments)
            throws JsonProcessingException {

        if (fits(
                sourcePointer,
                null,
                null,
                node)) {

            addFragment(
                    fragments,
                    new RawFragment(
                            sourcePointer,
                            null,
                            null,
                            node
                    )
            );
            return;
        }

        if (node.isArray()) {
            splitArray(
                    (ArrayNode) node,
                    sourcePointer,
                    fragments
            );
            return;
        }

        if (node.isObject()) {
            splitObject(
                    (ObjectNode) node,
                    sourcePointer,
                    fragments
            );
            return;
        }

        /*
         * 标量字段不能被截断。
         * 如果一个字符串自身已经超过上限，只能明确报错，
         * 由管理员调整配置或者在字段字典中禁止其发送给模型。
         */
        throw new WorkflowAnswerChunkException(
                "WORKFLOW_ANSWER_SINGLE_FIELD_TOO_LARGE",
                "单个字段超过模型分块大小，字段位置："
                        + displayPointer(sourcePointer)
        );
    }

    /**
     * 数组按照连续下标进行贪心分组。
     */
    private void splitArray(
            ArrayNode arrayNode,
            String sourcePointer,
            List<RawFragment> fragments)
            throws JsonProcessingException {

        ArrayNode current =
                objectMapper.createArrayNode();

        int currentStartIndex = -1;

        for (int index = 0;
             index < arrayNode.size();
             index++) {

            JsonNode child =
                    arrayNode.get(index);

            ArrayNode candidate =
                    current.deepCopy();

            candidate.add(child);

            int candidateStartIndex =
                    currentStartIndex < 0
                            ? index
                            : currentStartIndex;

            if (fits(
                    sourcePointer,
                    candidateStartIndex,
                    index,
                    candidate)) {

                current = candidate;
                currentStartIndex =
                        candidateStartIndex;
                continue;
            }

            flushArrayFragment(
                    sourcePointer,
                    currentStartIndex,
                    index - 1,
                    current,
                    fragments
            );

            ArrayNode single =
                    objectMapper.createArrayNode();

            single.add(child);

            if (fits(
                    sourcePointer,
                    index,
                    index,
                    single)) {

                current = single;
                currentStartIndex = index;
                continue;
            }

            /*
             * 单个数组元素仍然过大时继续递归，
             * 但必须保留它原来所在的数组下标。
             */
            splitNode(
                    child,
                    appendArrayPointer(
                            sourcePointer,
                            index
                    ),
                    fragments
            );

            current =
                    objectMapper.createArrayNode();

            currentStartIndex = -1;
        }

        flushArrayFragment(
                sourcePointer,
                currentStartIndex,
                arrayNode.size() - 1,
                current,
                fragments
        );
    }

    /**
     * 对象按照字段分组。
     *
     * 普通对象优先作为整体进入同一个数据块；
     * 只有整体超过上限时，才按照字段继续拆分。
     */
    private void splitObject(
            ObjectNode objectNode,
            String sourcePointer,
            List<RawFragment> fragments)
            throws JsonProcessingException {

        ObjectNode current =
                objectMapper.createObjectNode();

        Iterator<Map.Entry<String, JsonNode>>
                fields = objectNode.fields();

        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field =
                    fields.next();

            ObjectNode candidate =
                    current.deepCopy();

            candidate.set(
                    field.getKey(),
                    field.getValue()
            );

            if (fits(
                    sourcePointer,
                    null,
                    null,
                    candidate)) {

                current = candidate;
                continue;
            }

            flushObjectFragment(
                    sourcePointer,
                    current,
                    fragments
            );

            ObjectNode single =
                    objectMapper.createObjectNode();

            single.set(
                    field.getKey(),
                    field.getValue()
            );

            if (fits(
                    sourcePointer,
                    null,
                    null,
                    single)) {

                current = single;
                continue;
            }

            /*
             * 单个属性仍然太大时，递归处理属性值。
             * sourcePointer可以让后续模型知道该片段来自哪里。
             */
            splitNode(
                    field.getValue(),
                    appendObjectPointer(
                            sourcePointer,
                            field.getKey()
                    ),
                    fragments
            );

            current =
                    objectMapper.createObjectNode();
        }

        flushObjectFragment(
                sourcePointer,
                current,
                fragments
        );
    }

    private void flushArrayFragment(
            String sourcePointer,
            int startIndex,
            int endIndex,
            ArrayNode data,
            List<RawFragment> fragments)
            throws JsonProcessingException {

        if (data == null || data.isEmpty()) {
            return;
        }

        addFragment(
                fragments,
                new RawFragment(
                        sourcePointer,
                        startIndex,
                        endIndex,
                        data
                )
        );
    }

    private void flushObjectFragment(
            String sourcePointer,
            ObjectNode data,
            List<RawFragment> fragments)
            throws JsonProcessingException {

        if (data == null || data.isEmpty()) {
            return;
        }

        addFragment(
                fragments,
                new RawFragment(
                        sourcePointer,
                        null,
                        null,
                        data
                )
        );
    }

    /**
     * 添加分块时立即检查数量，防止先占用大量内存后才报错。
     */
    private void addFragment(
            List<RawFragment> fragments,
            RawFragment fragment)
            throws JsonProcessingException {

        if (fragments.size() >= maxChunks) {
            throw new WorkflowAnswerChunkException(
                    "WORKFLOW_ANSWER_TOO_MANY_CHUNKS",
                    "工作流结果分块数量超过安全上限："
                            + maxChunks
            );
        }

        String payloadJson =
                writeFragment(fragment);

        if (payloadJson.length()
                > maxChunkChars) {

            throw new WorkflowAnswerChunkException(
                    "WORKFLOW_ANSWER_CHUNK_TOO_LARGE",
                    "工作流结果分块超过字符上限："
                            + maxChunkChars
            );
        }

        fragments.add(fragment);
    }

    private boolean fits(
            String sourcePointer,
            Integer startIndex,
            Integer endIndex,
            JsonNode data)
            throws JsonProcessingException {

        RawFragment fragment =
                new RawFragment(
                        sourcePointer,
                        startIndex,
                        endIndex,
                        data
                );

        return writeFragment(fragment).length()
                <= maxChunkChars;
    }

    /**
     * 每个分块使用统一信封结构，方便模型和覆盖率组件识别。
     */
    private String writeFragment(
            RawFragment fragment)
            throws JsonProcessingException {

        ObjectNode envelope =
                objectMapper.createObjectNode();

        envelope.put(
                "sourcePointer",
                fragment.sourcePointer()
        );

        if (fragment.startIndex() != null) {
            envelope.put(
                    "startIndex",
                    fragment.startIndex()
            );
        }

        if (fragment.endIndex() != null) {
            envelope.put(
                    "endIndex",
                    fragment.endIndex()
            );
        }

        envelope.set(
                "data",
                fragment.data()
        );

        return objectMapper.writeValueAsString(
                envelope
        );
    }

    private String appendObjectPointer(
            String sourcePointer,
            String fieldName) {

        String escapedFieldName =
                fieldName
                        .replace("~", "~0")
                        .replace("/", "~1");

        return sourcePointer
                + "/"
                + escapedFieldName;
    }

    private String appendArrayPointer(
            String sourcePointer,
            int index) {

        return sourcePointer
                + "/"
                + index;
    }

    private String displayPointer(
            String sourcePointer) {

        return sourcePointer == null
                || sourcePointer.isBlank()
                ? "/"
                : sourcePointer;
    }

    private String sha256(
            String value) {

        try {
            MessageDigest messageDigest =
                    MessageDigest.getInstance(
                            "SHA-256"
                    );

            byte[] digest =
                    messageDigest.digest(
                            value.getBytes(
                                    StandardCharsets.UTF_8
                            )
                    );

            return HexFormat.of()
                    .formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new WorkflowAnswerChunkException(
                    "WORKFLOW_ANSWER_DIGEST_FAILED",
                    "工作流结果摘要计算失败",
                    exception
            );
        }
    }

    /**
     * 仅在分块器内部使用的原始片段。
     */
    private record RawFragment(
            String sourcePointer,
            Integer startIndex,
            Integer endIndex,
            JsonNode data) {
    }
}