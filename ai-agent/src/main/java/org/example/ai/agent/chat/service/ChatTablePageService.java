package org.example.ai.agent.chat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.answer.model.UnifiedFactSet;
import org.example.ai.agent.answer.planner.ResponsePlanner;
import org.example.ai.agent.chat.protocol.block.TableBlock;
import org.example.ai.agent.chat.vo.ChatResponseSnapshotVO;
import org.example.ai.agent.chat.vo.ChatTablePageVO;
import org.example.ai.agent.common.enums.protocol.BlockSource;
import org.example.ai.agent.common.enums.protocol.BlockStatus;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.exception.ErrorCode;
import org.example.ai.agent.workflow.answer.ResultArtifactDocumentAssembler;
import org.example.ai.agent.workflow.answer.WorkflowAnswerFieldContext;
import org.example.ai.agent.workflow.answer.WorkflowAnswerFieldPolicy;
import org.example.ai.agent.workflow.answer.WorkflowAnswerModelPayload;
import org.example.ai.agent.workflow.answer.artifact.ResultArtifactService;
import org.example.ai.agent.workflow.answer.artifact.ResultArtifactSnapshot;
import org.example.ai.agent.workflow.answer.text.WorkflowTextFactBuilder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 从当前回答引用的结果快照读取表格，不调用业务接口或大模型。
 */
@Service
@RequiredArgsConstructor
public class ChatTablePageService {

    private static final int PAGE_SIZE = 10;

    private final AiChatSessionService chatSessionService;
    private final ResultArtifactService artifactService;
    private final ResultArtifactDocumentAssembler documentAssembler;
    private final WorkflowTextFactBuilder factBuilder;
    private final ResponsePlanner responsePlanner;
    private final ObjectMapper objectMapper;

    /**
     * 精确定位回答、表格和数据来源，再返回指定页。
     */
    public ChatTablePageVO page(
            String userId,
            String sessionId,
            String runId,
            String responseId,
            String blockId,
            int current,
            int size) {

        if (!StringUtils.hasText(responseId)
                || !StringUtils.hasText(blockId)
                || !blockId.matches("table_[a-f0-9]{64}")
                || current < 1
                || size != PAGE_SIZE) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "分页参数不正确，每页固定10条，请使用新查询生成的表格"
            );
        }

        // 复用现有用户归属、会话归属和回答唯一性检查。
        ChatResponseSnapshotVO saved = chatSessionService.getResponseSnapshot(
                userId, sessionId, runId, responseId
        );

        if (!StringUtils.hasText(saved.documentJson())) {
            throw new BusinessException(409, "回答快照尚未保存，请稍后重试");
        }

        try {
            JsonNode document = objectMapper.readTree(saved.documentJson());

            if (document == null
                    || !"CHAT".equals(document.path("mode").asText())
                    || !sessionId.equals(document.path("conversationId").asText())
                    || !runId.equals(document.path("runId").asText())
                    || !responseId.equals(document.path("responseId").asText())
                    || !document.path("blocks").isArray()) {
                throw new BusinessException(409, "回答快照与当前查询不一致");
            }

            JsonNode tableNode = null;

            for (JsonNode block : document.path("blocks")) {
                if (!blockId.equals(block.path("id").asText())) {
                    continue;
                }

                if (tableNode != null) {
                    throw new BusinessException(409, "当前回答存在重复表格标识");
                }
                tableNode = block;
            }

            // 模型仍在输出文字时，已准备完成的表格也允许翻页。
            if (tableNode == null
                    || !"TABLE".equals(tableNode.path("type").asText())
                    || !"READY".equals(tableNode.path("status").asText())
                    || !tableNode.path("columns").isArray()
                    || tableNode.path("columns").isEmpty()
                    || !tableNode.path("rows").isArray()) {
                throw new BusinessException(409, "当前表格尚未准备完成或不可分页");
            }

            String artifactId = document.path("meta").path("artifactId").asText();

            if (!StringUtils.hasText(artifactId)) {
                throw new BusinessException(
                        ErrorCode.BAD_REQUEST,
                        "当前回答没有可读取的业务结果快照，无法继续翻页"
                );
            }

            List<TableBlock.Column> columns = objectMapper.convertValue(
                    tableNode.path("columns"),
                    new TypeReference<List<TableBlock.Column>>() {}
            );

            TableBlock preview = new TableBlock(
                    blockId,
                    tableNode.path("title").asText(),
                    tableNode.path("order").asInt(),
                    BlockStatus.READY,
                    BlockSource.valueOf(tableNode.path("source").asText()),
                    columns,
                    List.of(),
                    tableNode.path("total").asLong(),
                    tableNode.path("hasMore").asBoolean()
            );

            // 来源只能取已验证回答的meta，不能接收前端传入的快照ID。
            // 追问可以引用上一轮快照，因此不强制两个runId相同。
            ResultArtifactSnapshot snapshot = artifactService.loadComplete(
                    userId, sessionId, artifactId
            );

            String semanticsJson = StringUtils.hasText(snapshot.fieldSemanticsJson())
                    ? snapshot.fieldSemanticsJson()
                    : "[]";

            List<WorkflowAnswerFieldContext> fields = objectMapper.readValue(semanticsJson, new TypeReference<List<WorkflowAnswerFieldContext>>() {});
            WorkflowAnswerFieldPolicy policy = new WorkflowAnswerFieldPolicy(fields);
            WorkflowAnswerModelPayload payload = objectMapper.treeToValue(
                    documentAssembler.assemble(snapshot.chunkPlan()),
                    WorkflowAnswerModelPayload.class
            );

            // 使用快照保存时的安全字段，不扩大展示或模型权限。
            UnifiedFactSet factSet = factBuilder.buildSnapshot(
                    payload,
                    policy.modelFields(),
                    Boolean.TRUE.equals(snapshot.artifact().getDataComplete())
            );

            TableBlock restored = responsePlanner.restoreTable(preview, factSet);

            // 首屏必须与原回答一致，不能把另一种提取结果接到原表后面。
            List<TableBlock.Row> firstPage = restored.rows().stream()
                    .limit(PAGE_SIZE)
                    .toList();

            JsonNode restoredFirstPage = objectMapper.readTree(
                    objectMapper.writeValueAsString(firstPage)
            );

            if (!tableNode.path("rows").equals(restoredFirstPage)) {
                throw new BusinessException(
                        409,
                        "结果快照与原表格内容不一致，已保留原数据，请重新查询"
                );
            }

            long total = restored.rows().size();

            if (total > preview.total()) {
                throw new BusinessException(
                        409,
                        "结果快照与原表格数量不一致，无法安全翻页"
                );
            }

            // 使用long计算偏移，越界返回空页和真实总数，由前端保留有效页。
            long offset = ((long) current - 1) * PAGE_SIZE;
            List<TableBlock.Row> records = restored.rows().stream()
                    .skip(offset)
                    .limit(PAGE_SIZE)
                    .toList();

            boolean dataComplete = document.path("dataComplete").asBoolean(false)
                    && factSet.dataComplete()
                    && total == preview.total();

            return new ChatTablePageVO(
                    responseId,
                    blockId,
                    current,
                    PAGE_SIZE,
                    total,
                    preview.total(),
                    dataComplete,
                    records
            );
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "表格结果快照解析失败，请重新查询",
                    exception
            );
        }
    }
}