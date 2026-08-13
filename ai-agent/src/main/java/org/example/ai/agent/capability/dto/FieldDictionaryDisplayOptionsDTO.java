package org.example.ai.agent.capability.dto;

import lombok.Data;

/**
 * 字段字典列表快捷配置 DTO。
 *
 * 只允许修改展示、回答、搜索和聚合四个开关，
 * 防止列表快捷操作覆盖字段路径和业务语义。
 */
@Data
public class FieldDictionaryDisplayOptionsDTO {

    /**
     * 是否允许展示：1 是，0 否。
     */
    private Integer visible;

    /**
     * 是否为必答字段：1 是，0 否。
     */
    private Integer requiredOutput;

    /**
     * 是否可搜索：0 是，1 否。
     */
    private Integer searchable;

    /**
     * 是否可聚合统计：0 是，1 否。
     */
    private Integer aggregatable;
}
