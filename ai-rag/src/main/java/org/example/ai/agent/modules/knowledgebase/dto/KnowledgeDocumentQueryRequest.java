// 文件：src/main/java/org/example/airag/modules/knowledgebase/dto/KnowledgeDocumentQueryRequest.java

package org.example.ai.agent.modules.knowledgebase.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 企业知识文档问答请求。
 *
 * 默认只检索已发布文档的 currentVersionId，避免草稿、废止版本进入正式回答。
 */
public record KnowledgeDocumentQueryRequest(

        /**
         * 分类ID列表；为空表示不按分类过滤。
         */
        List<Long> categoryIds,

        /**
         * 文档ID列表；为空表示不按指定文档过滤。
         */
        List<Long> documentIds,

        /**
         * 用户问题。
         */
        @NotBlank(message = "问题不能为空")
        String question,

        /**
         * 召回数量；为空时使用默认值。
         */
        Integer topK,

        /**
         * 最低相似度；为空时使用默认值。
         */
        Double minScore
) {
}