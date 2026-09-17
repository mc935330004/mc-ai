package org.example.ai.agent.chat.protocol.block;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.example.ai.agent.common.enums.protocol.BlockSource;
import org.example.ai.agent.common.enums.protocol.BlockStatus;
import org.example.ai.agent.common.enums.protocol.BlockType;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 导出文件等异步产物的安全元数据。
 *
 * 下载地址、存储路径和校验值不属于回答协议。
 */
@JsonIgnoreProperties(value = "type", allowGetters = true)
public record ArtifactBlock(
        String id,
        String title,
        int order,
        BlockStatus status,
        BlockSource source,
        String taskId,
        String format,
        String fileName,
        String taskStatus,
        LocalDateTime expiresAt,
        LocalDateTime frozenAt,
        String contentVersion,
        boolean dataComplete,
        String safeMessage) implements ResponseBlock {

    private static final Set<String> TASK_STATUSES = Set.of(
            "PENDING",
            "RETRY",
            "RUNNING",
            "COLLECTING",
            "RENDERING",
            "SUCCESS",
            "COMPLETED",
            "PARTIAL_SUCCESS",
            "FAILED",
            "CANCELLED",
            "EXPIRED"
    );
    /**
     * 内容版本使用冻结逻辑报告的 SHA-256，页面和下载文件据此关联同一版本。
     */
    private static final Pattern CONTENT_VERSION_PATTERN = Pattern.compile("[a-f0-9]{64}");

    public ArtifactBlock {
        id = BlockSupport.requireId(id);
        title = BlockSupport.normalizeTitle(title);
        order = BlockSupport.normalizeOrder(order);
        status = BlockSupport.normalizeStatus(status);
        source = BlockSupport.normalizeSource(source);
        taskId = requireText(taskId, "产物taskId不能为空");
        format = normalize(format);
        fileName = normalize(fileName);
        validateFileName(fileName);
        taskStatus = normalizeTaskStatus(taskStatus);
        if (frozenAt == null) {
            throw new IllegalArgumentException("产物frozenAt不能为空");
        }
        contentVersion = normalize(contentVersion).toLowerCase(Locale.ROOT);
        if (!CONTENT_VERSION_PATTERN.matcher(contentVersion).matches()) {
            throw new IllegalArgumentException("产物contentVersion必须是64位SHA-256");
        }
        if (dataComplete
                && !"SUCCESS".equals(taskStatus)
                && !"COMPLETED".equals(taskStatus)) {
            throw new IllegalArgumentException(
                    "产物dataComplete=true仅允许SUCCESS或COMPLETED状态"
            );
        }
        safeMessage = normalize(safeMessage);
    }

    @Override
    public BlockType type() {
        return BlockType.ARTIFACT;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeTaskStatus(String taskStatus) {
        String normalized = requireText(
                taskStatus,
                "产物taskStatus不能为空"
        ).toUpperCase(Locale.ROOT);
        if (!TASK_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException(
                    "不支持的产物taskStatus：" + normalized
            );
        }
        return normalized;
    }

    /**
     * 回答协议只保存安全文件名，不接受路径和URL。
     */
    private static void validateFileName(String fileName) {
        if (fileName.isEmpty()) {
            return;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (fileName.contains("/")
                || fileName.contains("\\")
                || fileName.contains("..")
                || fileName.contains(":")
                || lower.startsWith("file:")
                || lower.startsWith("http:")
                || lower.startsWith("https:")) {
            throw new IllegalArgumentException(
                    "产物fileName必须是安全basename"
            );
        }
    }
}
