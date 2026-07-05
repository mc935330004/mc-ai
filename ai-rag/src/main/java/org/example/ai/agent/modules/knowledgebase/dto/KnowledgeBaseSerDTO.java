package org.example.ai.agent.modules.knowledgebase.dto;

import lombok.Data;

@Data
public class KnowledgeBaseSerDTO {
    /**
     * 向量化状态
     */
    String vectorStatus;

    /**
     * 关键字，支持模糊匹配
     */
    String keyword;

    /**
     * 分类
     */
    String category;

}
