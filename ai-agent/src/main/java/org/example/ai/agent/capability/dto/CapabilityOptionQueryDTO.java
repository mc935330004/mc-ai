package org.example.ai.agent.capability.dto;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 动态表单远程选项查询参数。
 */
@Data
public class CapabilityOptionQueryDTO {

    /**
     * 当前已经填写的表单值。
     *
     * 后台只读取Schema inputMapping明确引用的字段。
     */
    private Map<String, Object> form =new LinkedHashMap<>();
}