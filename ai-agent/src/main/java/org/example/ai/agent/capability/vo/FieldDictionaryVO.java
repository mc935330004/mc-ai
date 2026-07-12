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

}
