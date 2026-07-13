package org.example.ai.agent.chat.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 前端快速展示的核心事实。
 */
@Data
@Builder
public class FactPreviewVO {

    /**
     * 字段名称。
     */
    private String label;

    /**
     * 字段展示值。
     */
    private String value;

    /**
     * 字段分组。
     */
    private String group;

    /**
     * 是否为必答字段。
     */
    private boolean required;
}