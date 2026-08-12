package org.example.ai.agent.access.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 可执行资源访问配置保存参数。
 */
@Data
public class ResourceAccessSaveDTO {

    /**
     * 访问范围：PUBLIC、RESTRICTED。
     */
    @NotBlank(message = "访问范围不能为空")
    private String accessScope;

    /**
     * 指定人员的PM用户编码列表。
     *
     * PUBLIC状态会忽略并清空该列表，
     * RESTRICTED状态由服务层统一校验数量和内容。
     */
    private List<String> userIds =
            new ArrayList<>();
}
