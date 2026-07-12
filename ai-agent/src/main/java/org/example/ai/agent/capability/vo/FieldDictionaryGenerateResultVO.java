package org.example.ai.agent.capability.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 单个能力的字段字典生成结果。
 */
@Data
@Builder
public class FieldDictionaryGenerateResultVO {

    private String capabilityCode;

    /**
     * Schema 中识别出的字段数。
     */
    private Integer detectedCount;

    /**
     * 实际新增字段数。
     */
    private Integer createdCount;

    /**
     * 已存在而跳过的字段数。
     */
    private Integer skippedCount;

    /**
     * SUCCESS、SKIPPED 或 FAILED。
     */
    private String status;

    private String message;
}