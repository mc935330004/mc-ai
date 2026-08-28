package org.example.ai.agent.capability.dto;

import lombok.Data;

/**
 * 字段字典列表快捷配置 DTO。
 *
 * 只允许修改回答权限、展示权限和搜索开关，
 * 不允许覆盖字段路径和业务语义。
 */
@Data
public class FieldDictionaryDisplayOptionsDTO {

    /**
     * 是否允许发送给大模型：1是，0否。
     */
    private Integer modelVisible;

    /**
     * 是否允许展示给用户：1是，0否。
     */
    private Integer userVisible;

    /**
     * 是否为必答字段：1是，0否。
     */
    private Integer requiredOutput;

    /**
     * 是否可搜索：0是，1否。
     */
    private Integer searchable;
}