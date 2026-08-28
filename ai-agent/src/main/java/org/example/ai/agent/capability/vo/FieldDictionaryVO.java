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
     * 是否允许发送给大模型。
     */
    private Integer modelVisible;

    /**
     * 是否允许展示给用户。
     */
    private Integer userVisible;
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
    private String fieldCode;

    private String importance;

    private String displayComponent;

    private Integer summaryFlag;

    private String unit;

    private Integer precisionScale;

    private String valueSource;

}
