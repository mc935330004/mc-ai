package org.example.ai.agent.answer.model;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.ai.agent.common.enums.FactSourceType;
import org.example.ai.agent.tool.FieldMeta;

/**
 * 字段值解析完成后的临时候选事实。
 *
 * 该对象只负责把路径解析结果交给统一事实提取器，
 * 不参与回答展示，也不保存到数据库。
 */
public record FactValueCandidate(
        String capabilityCode,
        FieldMeta field,
        JsonNode value,
        String recordPath,
        String collectionPath,
        boolean missing,
        String missingReason,
        FactSourceType sourceType) {

    public FactValueCandidate {
        sourceType = sourceType == null
                ? FactSourceType.RAW
                : sourceType;
    }

    /**
     * 创建正常业务字段候选值。
     */
    public static FactValueCandidate value(
            String capabilityCode,
            FieldMeta field,
            JsonNode value,
            String recordPath,
            String collectionPath) {

        return new FactValueCandidate(
                capabilityCode,
                field,
                value,
                recordPath,
                collectionPath,
                false,
                null,
                FactSourceType.RAW
        );
    }
}