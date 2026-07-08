package org.example.ai.agent.capability.vo;

import lombok.Data;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.entity.FieldDictionary;

import java.util.List;

/**
 * AI 能力详情 VO。
 *
 * 用于管理端一次性查看能力定义和字段字典。
 */
@Data
public class CapabilityDetailVO {

    /**
     * 能力定义。
     */
    private CapabilityDefinition capability;

    /**
     * 当前能力下的字段字典。
     */
    private List<FieldDictionary> fields;
}