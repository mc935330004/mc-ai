package org.example.ai.agent.capability.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 前端远程下拉统一选项。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CapabilityOptionVO {

    /**
     * 最终提交给WRITE接口的真实值，一般为ID。
     */
    private Object value;

    /**
     * 展示给用户的中文名称。
     */
    private String label;
}