package org.example.ai.agent.graph.compiler;

import java.util.Optional;

/**
 * GraphSpec编译器使用的能力目录。
 *
 * 解耦GraphSpec编译器与MyBatis Plus，
 * 单元测试可以使用内存能力目录。
 */
@FunctionalInterface
public interface GraphCapabilityCatalog {

    /**
     * 判断能力是否允许被当前工作流调用。
     */
    boolean isCallable(String capabilityCode);

    /**
     * 返回能力副作用类型。
     *
     * 默认按照 READ 处理，兼容原有测试中的 Lambda 实现。
     */
    default String sideEffect(String capabilityCode) {
        return "READ";
    }

    /**
     * 查询能力公开输入契约。
     *
     * 使用default是为了保持本接口仍然是函数式接口，
     * 不破坏旧测试中的Lambda写法。
     *
     * 正式环境的DefaultGraphCapabilityCatalog
     * 必须覆盖该方法。
     */
    default Optional<GraphCapabilityContract>
    findContract(String capabilityCode) {

        return Optional.empty();
    }
}