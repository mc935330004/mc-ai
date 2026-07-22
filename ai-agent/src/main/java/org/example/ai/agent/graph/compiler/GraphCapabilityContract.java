package org.example.ai.agent.graph.compiler;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * GraphSpec编译阶段使用的能力输入契约。
 *
 * @param allowedInputPaths  能力允许工作流配置的输入路径
 * @param requiredInputPaths 能力调用必须配置的输入路径
 */
public record GraphCapabilityContract(
        Set<String> allowedInputPaths,
        Set<String> requiredInputPaths) {

    public GraphCapabilityContract {
        /*
         * 复制为不可变集合，
         * 防止编译期间被调用方意外修改。
         */
        allowedInputPaths =
                readOnly(allowedInputPaths);

        requiredInputPaths =
                readOnly(requiredInputPaths);

        /*
         * 必填字段必须同时属于允许字段。
         */
        if (!allowedInputPaths.containsAll(
                requiredInputPaths)) {

            throw new IllegalArgumentException(
                    "requiredInputPaths必须是"
                            + "allowedInputPaths的子集"
            );
        }
    }

    private static Set<String> readOnly(
            Set<String> source) {

        if (source == null || source.isEmpty()) {
            return Set.of();
        }

        return Collections.unmodifiableSet(
                new LinkedHashSet<>(source)
        );
    }
}