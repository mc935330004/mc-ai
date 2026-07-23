package org.example.ai.agent.graph;

/**
 * GraphSpec统一安全限制。
 *
 * 编译器和运行时必须使用同一份限制，
 * 避免编译阶段允许、运行阶段拒绝，或者反过来。
 */
public final class GraphSpecLimits {

    /**
     * 最多允许两层FOREACH：
     *
     * 第一层：最多5个用户输入项目；
     * 第二层：业务接口返回的全部记录。
     */
    public static final int MAX_FOREACH_NESTING_DEPTH =2;

    private GraphSpecLimits() {
    }
}