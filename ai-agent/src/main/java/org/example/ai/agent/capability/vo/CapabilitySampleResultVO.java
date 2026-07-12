package org.example.ai.agent.capability.vo;

import lombok.Builder;
import lombok.Data;
import org.example.ai.agent.capability.entity.FieldDictionary;

import java.util.List;
import java.util.Map;

/**
 * READ 能力真实响应测试结果。
 */
@Data
@Builder
public class CapabilitySampleResultVO {

    private Boolean success;
    private String capabilityCode;
    private Map<String, Object> input;

    /**
     * 真实业务系统原始响应。
     */
    private Object rawData;

    /**
     * 从真实响应发现但数据库尚未配置的字段。
     */
    private List<FieldDictionary> newFields;

    private String errorCode;
    private String errorMessage;
}