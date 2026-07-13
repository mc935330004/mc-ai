package org.example.ai.agent.capability.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FieldDictionaryVO {

    private Long fieldId;

    private String fieldName;

    private String fieldCnName;

    private String fieldPath;

    private String fieldType;

    private String exampleValue;

    private String businessMeaning;

    private String description;

    /**
     * 是否为必答字段。
     */
    private Integer requiredOutput;

    /**
     * 是否允许展示。
     */
    private Integer visible;

    /**
     * 展示顺序。
     */
    private Integer displayOrder;

    /**
     * 展示分组。
     */
    private String displayGroup;

    /**
     * 空值展示文本。
     */
    private String nullDisplayText;

}
