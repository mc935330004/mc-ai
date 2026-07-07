package org.example.ai.agent.capability.dto;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 能力测试请求 DTO。
 *
 * 用于管理端测试某个 capabilityCode 能不能正常调用。
 */
@Data
public class CapabilityTestRequestDTO {

    /**
     * 测试入参。
     * 示例：
     * {
     *   "projectName": "智慧园区项目"
     * }
     */
    private Map<String, Object> input = new LinkedHashMap<>();
}