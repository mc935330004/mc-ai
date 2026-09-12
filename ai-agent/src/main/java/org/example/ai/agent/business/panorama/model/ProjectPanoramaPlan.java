package org.example.ai.agent.business.panorama.model;

import java.util.List;

/**
 * 已从当前配置解析出的不可变项目全景执行方案。
 */
public record ProjectPanoramaPlan(
        Long profileId,
        String projectType,
        String profileName,
        String configChecksum,
        List<Module> modules) {

    public ProjectPanoramaPlan {
        modules = modules == null ? List.of() : List.copyOf(modules);
    }

    /**
     * 单个模块只引用已注册报告数据集，不直接引用工作流。
     */
    public record Module(
            String datasetCode,
            boolean required,
            int displayOrder,
            int timeoutMs) {
    }
}
