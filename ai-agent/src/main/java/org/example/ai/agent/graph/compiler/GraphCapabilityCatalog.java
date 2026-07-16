package org.example.ai.agent.graph.compiler;

/**
 * GraphSpec编译器使用的能力目录。
 *
 * 解耦编译器和MyBatis Plus，
 * 方便单元测试使用内存能力目录。
 */
@FunctionalInterface
public interface GraphCapabilityCatalog {

    /**
     * 判断能力是否允许被Agent调用。
     */
    boolean isCallable(String capabilityCode);
}