package org.example.ai.agent.chat.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ChatModelVO {

    /**
     *  前端提交用的模型编码。
     */
    private String code;

    /**
     *  前端下拉框展示名称。
     */
    private String name;

    /**
     *  模型供应商，仅用于展示和排查。
     */
    private String provider;

    /**
     *  是否为默认模型。
     */
    private Boolean defaultModel;
}