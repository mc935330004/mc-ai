package org.example.ai.agent.capability.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * OpenAPI 批量导入结果。
 */
@Data
@Builder
public class OpenApiImportResultVO {

    /**
     * 系统编码。
     */
    private String systemCode;
    /**
     * 总数。
     */
    private Integer total;
    /**
     * 导入数。
     */
    private Integer imported;
    /**
     * 跳过数。
     */
    private Integer skipped;
    /**
     * 失败数。
     */
    private Integer failed;
    /**
     * 结果列表。
     */
    private List<OpenApiImportItemResultVO> results;
}