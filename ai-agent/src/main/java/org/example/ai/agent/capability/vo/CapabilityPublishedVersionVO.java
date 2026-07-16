package org.example.ai.agent.capability.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CapabilityPublishedVersionVO {

    private String capabilityCode;
    private Long versionId;
    private Integer versionNo;
    private Integer configRevision;
    private String configChecksum;

    /**
     * true：草稿内容与当前发布版本相同，复用了已有版本。
     */
    private Boolean reused;
}