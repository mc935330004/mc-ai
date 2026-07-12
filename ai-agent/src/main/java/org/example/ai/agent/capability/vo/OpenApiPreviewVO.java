package org.example.ai.agent.capability.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * OpenAPI 扫描结果。
 */
@Data
@Builder
public class OpenApiPreviewVO {

    private String systemCode;
    private String systemName;
    private String openapiTitle;
    private String openapiVersion;

    /**
     * 符合查询条件的接口总数。
     */
    private Integer total;

    private Integer current;
    private Integer size;

    /**
     * 当前页的接口摘要。
     */
    private List<OpenApiOperationPreviewVO> interfaces;
}