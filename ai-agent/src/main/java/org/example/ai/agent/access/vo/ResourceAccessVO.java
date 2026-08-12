package org.example.ai.agent.access.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 可执行资源访问配置返回对象。
 */
@Data
@Builder
public class ResourceAccessVO {

    /**
     * 资源类型。
     */
    private String resourceType;

    /**
     * 资源定义ID。
     */
    private Long resourceId;

    /**
     * 稳定资源编码。
     */
    private String resourceCode;

    /**
     * 资源展示名称。
     */
    private String resourceName;

    /**
     * 访问范围。
     */
    private String accessScope;

    /**
     * 指定人员编码列表。
     */
    private List<String> userIds;

    /**
     * 当前授权人数。
     */
    private Integer userCount;
}
