package org.example.ai.agent.common.enums;

/**
 * Token 数量的计量来源。
 */
public enum TokenMeasureType {

    /**
     * Token 数量由模型供应商响应元数据直接返回，准确度最高。
     */
    PROVIDER,

    /**
     * Token 数量由本地分词器或估算规则计算。
     */
    ESTIMATED,

    /**
     * 模型供应商没有返回 Token，本地也没有进行估算。
     */
    UNKNOWN
}