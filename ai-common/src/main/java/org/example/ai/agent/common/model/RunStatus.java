package org.example.ai.agent.common.model;

/**
 * Agent 运行状态常量。
 *
 * 这里用常量类，不用枚举，是为了和数据库字符串字段直接对应。
 */

public final class RunStatus {

    /**
     * 运行中。
     */
    public static final String RUNNING = "RUNNING";

    /**
     * 执行成功。
     */
    public static final String SUCCESS = "SUCCESS";

    /**
     * 执行失败。
     */
    public static final String FAILED = "FAILED";

    /**
     * 跳过执行。
     */
    public static final String SKIPPED = "SKIPPED";

    private RunStatus() {
    }
}