package org.example.ai.agent.graph.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 业务能力节点自动分页配置。
 *
 * 该配置只描述分页协议，不直接调用业务接口。
 *
 * 设计约束：
 * 1. 分页字段必须来自能力请求参数 Schema；
 * 2. recordsPath、totalPath 读取安全的 workflowData；
 * 3. 不读取包含敏感字段的原始 HTTP 响应；
 * 4. enabled=false 或 pagination=null 时保持原有单次调用逻辑。
 *
 * @param enabled             是否启用自动分页
 * @param pageNumberInputPath 页码请求参数路径，例如 current
 * @param pageSizeInputPath   每页数量请求参数路径，例如 size
 * @param recordsPath         记录数组路径，例如 $.records
 * @param totalPath           总记录数路径，例如 $.total；允许为空
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CapabilityPaginationConfig(

        Boolean enabled,

        String pageNumberInputPath,

        String pageSizeInputPath,

        String recordsPath,

        String totalPath) {

    /**
     * 统一判断是否启用自动分页。
     *
     * 使用 Boolean.TRUE.equals 避免 enabled 为 null 时出现空指针。
     */
    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }
}