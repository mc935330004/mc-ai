package org.example.ai.agent.capability.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 字段字典批量保存结果。
 */
@Data
@Builder
public class FieldDictionaryBatchSaveResultVO {

    private String capabilityCode;

    /**
     * 前端提交的字段数量。
     */
    private Integer submittedCount;

    /**
     * 实际新增数量。
     */
    private Integer createdCount;

    /**
     * 因为fieldPath已存在而跳过的数量。
     */
    private Integer skippedCount;
}