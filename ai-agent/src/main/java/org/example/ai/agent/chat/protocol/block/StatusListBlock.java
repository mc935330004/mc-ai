package org.example.ai.agent.chat.protocol.block;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.example.ai.agent.common.enums.protocol.BlockSource;
import org.example.ai.agent.common.enums.protocol.BlockStatus;
import org.example.ai.agent.common.enums.protocol.BlockType;
import org.example.ai.agent.common.enums.protocol.Tone;

import java.util.List;

/**
 * 多个安全数据集或子任务的状态列表。
 */
@JsonIgnoreProperties(value = "type", allowGetters = true)
public record StatusListBlock(
        String id,
        String title,
        int order,
        BlockStatus status,
        BlockSource source,
        List<StatusItem> items) implements ResponseBlock {

    public StatusListBlock {
        id = BlockSupport.requireId(id);
        title = BlockSupport.normalizeTitle(title);
        order = BlockSupport.normalizeOrder(order);
        status = BlockSupport.normalizeStatus(status);
        source = BlockSupport.normalizeSource(source);
        items = items == null ? List.of() : List.copyOf(items);
    }

    @Override
    public BlockType type() {
        return BlockType.STATUS_LIST;
    }

    /**
     * 单个状态项只保存安全展示信息。
     */
    public record StatusItem(
            String code,
            String label,
            String state,
            Tone tone,
            String safeMessage) {

        public StatusItem {
            code = requireText(code, "状态项code不能为空");
            label = normalize(label);
            state = requireText(state, "状态项state不能为空");
            tone = tone == null ? Tone.DEFAULT : tone;
            safeMessage = requireText(safeMessage, "状态项safeMessage不能为空");
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
    }
}
