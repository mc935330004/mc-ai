package org.example.airag.common.enums;

import lombok.Getter;

@Getter
public enum KnowledgeQueryStatus {

    /**
     * 查询成功
     */
    SUCCESS("SUCCESS", "查询成功"),

    /**
     * 没有召回结果
     */
    NO_RESULT("NO_RESULT", "没有召回结果"),

    /**
     * 查询失败
     */
    FAILED("FAILED", "查询失败");

    private final String code;
    private final String desc;

    KnowledgeQueryStatus(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}