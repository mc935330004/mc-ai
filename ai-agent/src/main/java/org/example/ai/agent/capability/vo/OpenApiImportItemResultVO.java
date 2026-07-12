package org.example.ai.agent.capability.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 单个接口导入结果。
 */
@Data
@Builder
public class OpenApiImportItemResultVO {

    private String url;
    private String method;
    private String capabilityCode;

    /**
     * IMPORTED：成功导入。
     * SKIPPED：能力已经存在。
     * FAILED：生成或保存失败。
     */
    private String status;

    private String message;
}