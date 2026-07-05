package org.example.ai.agent.modules.knowledgebase.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class KnowledgeCategoryTreeVO {

    private Long id;

    private Long parentId;

    private String name;

    private String code;

    private Integer sortOrder;

    private Boolean enabled;

    private List<KnowledgeCategoryTreeVO> children = new ArrayList<>();
}
