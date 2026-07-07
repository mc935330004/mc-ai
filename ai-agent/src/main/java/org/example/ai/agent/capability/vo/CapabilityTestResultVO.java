package org.example.ai.agent.capability.vo;

import lombok.Builder;
import lombok.Data;
import org.example.ai.agent.tool.FieldMeta;

import java.util.List;
import java.util.Map;

/**
 * AI 能力测试结果 VO。
 *
 * 返回给管理端查看真实调用结果。
 */
@Data
@Builder
public class CapabilityTestResultVO {

    /**
     * 是否调用成功。
     */
    private Boolean success;

    /**
     * 能力编码。
     */
    private String capabilityCode;

    /**
     * 实际请求入参。
     */
    private Map<String, Object> input;

    /**
     * 真实业务接口返回结果。
     */
    private Object data;

    /**
     * 字段字典解释。
     */
    private List<FieldMeta> fields;

    /**
     * 简短摘要。
     */
    private String summary;

    /**
     * 错误编码。
     */
    private String errorCode;

    /**
     * 错误信息。
     */
    private String errorMessage;
}