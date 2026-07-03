// 文件：src/main/java/org/example/airag/modules/knowledgebase/dto/KnowledgeQueryStatsDTO.java

package org.example.airag.modules.KnowledgeLog.dto;

import lombok.Data;

/**
 * 企业知识问答统计。
 */
@Data
public class KnowledgeQueryStatsDTO {

    /**
     * 总问答次数。
     */
    private Long totalCount;

    /**
     * 成功回答次数。
     */
    private Long successCount;

    /**
     * 无检索结果次数。
     */
    private Long noResultCount;

    /**
     * 失败次数。
     */
    private Long failedCount;

    /**
     * 平均耗时，单位毫秒。
     */
    private Long avgDurationMs;
}