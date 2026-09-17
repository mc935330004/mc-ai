package org.example.ai.agent.business.panorama.model;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 已从当前配置解析出的不可变项目全景执行方案。
 */
public record ProjectPanoramaPlan(
        Long profileId,
        String projectType,
        String profileName,
        String configChecksum,
        List<Module> modules) {

    private static final Pattern DATASET_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,127}$");
    private static final int MAX_SCOPE_MODULES = 32;

    public ProjectPanoramaPlan {
        modules = modules == null ? List.of() : List.copyOf(modules);
    }

    /**
     * 项目分析深度。
     */
    public enum AnalysisMode {

        /** 只查询项目基础信息。 */
        QUICK,

        /** 只查询用户明确选择的模块。 */
        FOCUSED,

        /** 用户明确确认后的完整分析。 */
        DEEP
    }
    /**
     * 根据用户确认范围生成实际执行或复用方案。
     */
    public ProjectPanoramaPlan select(Scope scope) {
        Objects.requireNonNull(scope, "项目分析范围不能为空");
        List<Module> selectedModules = switch (scope.mode()) {
            case QUICK -> modules.stream()
                    .filter(module -> "PROJECT_BASE".equals(module.datasetCode()))
                    .limit(1)
                    .toList();
            case FOCUSED -> modules.stream()
                    .filter(module -> scope.datasetCodes().contains(module.datasetCode()))
                    .toList();
            case DEEP -> modules;
        };

        if (scope.mode() == AnalysisMode.QUICK && selectedModules.isEmpty()) {
            throw new IllegalArgumentException("快速概览模块未配置或不可用");
        }
        if (scope.mode() == AnalysisMode.FOCUSED
                && selectedModules.size() != scope.datasetCodes().size()) {
            throw new IllegalArgumentException("所选分析模块未配置或不可用");
        }

        return new ProjectPanoramaPlan(
                profileId,
                projectType,
                profileName,
                configChecksum,
                selectedModules
        );
    }
    /**
     * 用户已经确认的项目分析范围。
     */
    public record Scope(AnalysisMode mode, List<String> datasetCodes) {

        public Scope {
            if (mode == null) {
                throw new IllegalArgumentException("项目分析模式不能为空");
            }

            datasetCodes = datasetCodes == null
                    ? List.of()
                    : datasetCodes.stream().map(Scope::validateDatasetCode).toList();

            if (datasetCodes.size() > MAX_SCOPE_MODULES) {
                throw new IllegalArgumentException("项目分析模块数量超过限制");
            }

            if (new HashSet<>(datasetCodes).size() != datasetCodes.size()) {
                throw new IllegalArgumentException("项目分析模块不能重复");
            }

            if (mode == AnalysisMode.FOCUSED && datasetCodes.isEmpty()) {
                throw new IllegalArgumentException("定向分析必须选择模块");
            }

            if (mode != AnalysisMode.FOCUSED && !datasetCodes.isEmpty()) {
                throw new IllegalArgumentException("快速概览或完整分析不能指定单独模块");
            }

            datasetCodes = List.copyOf(datasetCodes);
        }

        private static String validateDatasetCode(String datasetCode) {
            if (datasetCode == null || !DATASET_CODE.matcher(datasetCode.trim()).matches()) {
                throw new IllegalArgumentException("项目分析模块编码不合法");
            }
            return datasetCode.trim();
        }
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