package org.example.ai.agent.capability.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * OpenAPI同步预览。
 */
@Data
@Builder
public class OpenApiSyncPreviewVO {

    private String systemCode;
    private Integer added;
    private Integer removed;
    private Integer changed;
    private Integer unchanged;
    private List<OpenApiSyncItemVO> changes;
}