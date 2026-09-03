package org.example.ai.agent.business.panorama.model;

import com.fasterxml.jackson.annotation.JsonIgnoreType;

/**
 * 由当前授权目录重新确认的项目主体，只允许在后端编排链路内部使用。
 */
@JsonIgnoreType
public record AuthorizedProjectSubject(
        String projectId,
        String projectCode,
        String projectType,
        String displayName) {

    /**
     * 避免日志输出项目稳定标识、编码和名称。
     */
    @Override
    public String toString() {
        return "AuthorizedProjectSubject[authorized=true, projectTypePresent="
                + (projectType != null && !projectType.isBlank())
                + ']';
    }
}
