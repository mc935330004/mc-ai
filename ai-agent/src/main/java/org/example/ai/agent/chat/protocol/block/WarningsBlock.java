package org.example.ai.agent.chat.protocol.block;

import org.example.ai.agent.common.enums.protocol.BlockSource;
import org.example.ai.agent.common.enums.protocol.BlockStatus;
import org.example.ai.agent.common.enums.protocol.BlockType;
import org.example.ai.agent.common.enums.protocol.Tone;

import java.util.List;

/**
 * 风险规则产生的风险提示区块。
 *
 * AI可以解释风险，
 * 但不能修改规则结果和风险证据。
 */
public record WarningsBlock(
        String id,
        String title,
        int order,
        BlockStatus status,
        BlockSource source,
        List<WarningItem> warnings)
        implements ResponseBlock {

    public WarningsBlock {
        id = BlockSupport.requireId(id);
        title = BlockSupport.normalizeTitle(title);
        order = BlockSupport.normalizeOrder(order);
        status = BlockSupport.normalizeStatus(status);
        source = BlockSupport.normalizeSource(source);

        warnings = warnings == null
                ? List.of()
                : List.copyOf(warnings);
    }

    @Override
    public BlockType type() {
        return BlockType.WARNINGS;
    }

    /**
     * 单条风险规则结果。
     */
    public record WarningItem(
            String ruleCode,
            String objectId,
            String title,
            String message,
            Tone severity,
            List<String> evidenceFactKeys) {

        public WarningItem {
            ruleCode = normalize(ruleCode);
            objectId = normalize(objectId);
            title = normalize(title);
            message = normalize(message);

            severity = severity == null
                    ? Tone.WARNING
                    : severity;

            evidenceFactKeys = evidenceFactKeys == null
                    ? List.of()
                    : List.copyOf(evidenceFactKeys);
        }

        private static String normalize(String value) {
            return value == null ? "" : value.trim();
        }
    }
}