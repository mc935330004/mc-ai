package org.example.ai.agent.pending.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.result.Result;
import org.example.ai.agent.pending.entity.PendingAction;
import org.example.ai.agent.pending.service.PendingActionService;
import org.example.ai.agent.security.CurrentUserProvider;
import org.example.ai.agent.vo.PendingActionVO;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 待确认操作接口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/agent/actions")
public class PendingActionController {

    private final PendingActionService pendingActionService;
    private final ObjectMapper objectMapper;
    private final CurrentUserProvider currentUserProvider;
    /**
     * 查询操作状态。
     */
    @GetMapping("/{runId}")
    public Result<PendingActionVO> detail( @PathVariable String runId) {
        String userId = currentUserProvider.getRequiredUserId();
        return Result.success(toVO(pendingActionService.getAction(runId, userId)));
    }

    /**
     * 取消尚未确认的操作。
     */
    @PostMapping("/{runId}/cancel")
    public Result<PendingActionVO> cancel( @PathVariable String runId) {
        return Result.success(toVO(pendingActionService.cancelAction(runId,
                currentUserProvider.getRequiredUserId())));
    }
    /**
     * 确认写操作。
     *
     * 当前只记录用户确认状态，尚不调用真实业务接口。
     */
    @PostMapping("/{runId}/confirm")
    public Result<PendingActionVO> confirm( @PathVariable String runId) {
        return Result.success(toVO(pendingActionService.confirmAction(runId,
                currentUserProvider.getRequiredUserId())));
    }
    /**
     * 执行已经确认的写操作。
     */
    @PostMapping("/{runId}/execute")
    public Result<PendingActionVO> execute( @PathVariable String runId) {
        String userId = currentUserProvider.getRequiredUserId();
        String authorization = currentUserProvider.getRequiredAuthorization();
        return Result.success(toVO(pendingActionService.executeConfirmedAction(runId,
                userId, authorization)));
    }
    /**
     * 用户确认后立即执行真实业务操作。
     *
     * 前端只提交 runId 和当前用户身份，
     * 操作参数必须从 confirmAndExecute 读取。
     */
    @PostMapping("/{runId}/confirmAndExecute")
    public Result<PendingActionVO> confirmAndExecute( @PathVariable String runId) {
        String userId = currentUserProvider.getRequiredUserId();
        String authorization = currentUserProvider.getRequiredAuthorization();
        return Result.success(toVO(pendingActionService.confirmAndExecuteAction(runId,
                userId, authorization)));
    }

    /**
     * 转换为前端安全返回对象。
     */
    private PendingActionVO toVO(PendingAction action) {
        try {
            Map<String, Object> input = objectMapper.readValue(action.getInputJson(), new TypeReference<>() {});
            // 尚未执行时 outputJson 为空
            Object output = StringUtils.hasText(action.getOutputJson())
                    ? objectMapper.readValue(action.getOutputJson(), Object.class)
                    : null;
            String markdown = buildMarkdown(action, output);
            return PendingActionVO.builder()
                    .runId(action.getRunId())
                    .capabilityCode(action.getCapabilityCode())
                    .capabilityName(action.getCapabilityName())
                    .input(input)
                    .actionSummary(action.getActionSummary())
                    .status(action.getStatus())
                    .expireAt(action.getExpireAt())
                    .confirmedAt(action.getConfirmedAt())
                    .executedAt(action.getExecutedAt())
                    .errorMessage(action.getErrorMessage())
                    .output(output)
                    .markdown(markdown)
                    .build();
        } catch (Exception e) {
            throw new BusinessException(500, "待确认操作参数解析失败");
        }
    }

    /**
     * 根据真实执行状态和结果生成 Markdown。
     */
    private String buildMarkdown(PendingAction action, Object output) throws Exception {
        StringBuilder markdown = new StringBuilder();
        if ("SUCCESS".equals(action.getStatus())) {
            markdown.append("## 操作成功\n\n")
                    .append("**")
                    .append(escapeMarkdown(action.getCapabilityName()))
                    .append("** 已执行完成。\n\n");
            appendOutput(markdown, output);
        } else if ("FAILED".equals(action.getStatus())) {
            markdown.append("## 操作失败\n\n")
                    .append("- 操作：")
                    .append(escapeMarkdown(action.getCapabilityName()))
                    .append("\n- 原因：")
                    .append(escapeMarkdown(action.getErrorMessage()));
        } else {
            markdown.append("## 操作状态\n\n")
                    .append("- 操作：")
                    .append(escapeMarkdown(action.getCapabilityName()))
                    .append("\n- 当前状态：`")
                    .append(action.getStatus())
                    .append("`");
        }
        markdown.append("\n\n操作编号：`")
                .append(action.getRunId())
                .append("`");

        return markdown.toString();
    }

    /**
     * 将字段字典压缩后的业务结果转换为 Markdown。
     */
    private void appendOutput(StringBuilder markdown, Object output)
            throws Exception {
        if (output == null) {
            markdown.append("业务系统未返回详细结果。\n");
            return;
        }
        if (output instanceof Map<?, ?> map) {
            markdown.append("| 字段 | 结果 |\n")
                    .append("|---|---|\n");
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                markdown.append("| ")
                        .append(escapeMarkdown(entry.getKey()))
                        .append(" | ")
                        .append(escapeMarkdown(formatValue(entry.getValue())))
                        .append(" |\n");
            }
            return;
        }
        // 数组或复杂对象使用 JSON 代码块，避免丢失业务字段
        markdown.append("```json\n")
                .append(objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(output))
                .append("\n```\n");
    }

    /**
     * 格式化表格中的复杂字段。
     */
    private String formatValue(Object value) throws Exception {
        if (value instanceof Map<?, ?> || value instanceof Iterable<?>) {
            return objectMapper.writeValueAsString(value);
        }
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 转义 Markdown 表格特殊字符。
     */
    private String escapeMarkdown(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value)
                .replace("|", "\\|")
                .replace("\r", " ")
                .replace("\n", " ");
    }
}