package org.example.ai.agent.chat.protocol.block;

import org.example.ai.agent.common.enums.protocol.BlockSource;
import org.example.ai.agent.common.enums.protocol.BlockStatus;
import org.example.ai.agent.common.enums.protocol.BlockType;

import java.util.List;
import java.util.Set;

/**
 * 通用范围选择区块。
 */
public record SelectionBlock(
        String id,
        String title,
        int order,
        BlockStatus status,
        BlockSource source,
        String clarificationId,
        String selectionMode,
        String confirmLabel,
        List<Option> options) implements ResponseBlock {

    private static final Set<String> SELECTION_MODES = Set.of("SINGLE", "MULTIPLE");

    public SelectionBlock {
        id = BlockSupport.requireId(id);
        title = BlockSupport.normalizeTitle(title);
        order = BlockSupport.normalizeOrder(order);
        status = BlockSupport.normalizeStatus(status);
        source = BlockSupport.normalizeSource(source);

        if (clarificationId == null || clarificationId.isBlank() || clarificationId.length() > 128) {
            throw new IllegalArgumentException("clarificationId不合法");
        }

        clarificationId = clarificationId.trim();

        if (!SELECTION_MODES.contains(selectionMode)) {
            throw new IllegalArgumentException("选择模式不合法");
        }

        confirmLabel = confirmLabel == null || confirmLabel.isBlank()
                ? "确认"
                : confirmLabel.trim();

        options = options == null ? List.of() : List.copyOf(options);

        if (options.isEmpty()) {
            throw new IllegalArgumentException("选择项不能为空");
        }
    }

    @Override
    public BlockType type() {
        return BlockType.SELECTION;
    }

    /**
     * 通用选择项。
     *
     * exclusive=true表示选择该项后清除其他选项。
     */
    public record Option(
            String code,
            String label,
            String description,
            boolean exclusive,
            boolean disabled) {

        public Option {
            if (code == null || code.isBlank() || code.length() > 128) {
                throw new IllegalArgumentException("选择项编码不合法");
            }

            if (label == null || label.isBlank() || label.length() > 64) {
                throw new IllegalArgumentException("选择项名称不合法");
            }

            code = code.trim();
            label = label.trim();
            description = description == null ? "" : description.trim();
        }
    }
}