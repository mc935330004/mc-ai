package org.example.ai.agent.workflow.answer.report.template;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * 固定报告模板注册器。
 *
 * 直接复用 Spring 注入的模板列表，
 * 不新增工厂、配置文件或数据库映射。
 */
@Component
@RequiredArgsConstructor
public class ReportTemplateRegistry {

    private final List<ReportTemplate> templates;

    /**
     * 根据工作流编码选择固定模板。
     */
    public Optional<ReportTemplate> find(String workflowCode) {
        if (!StringUtils.hasText(workflowCode)) {
            return Optional.empty();
        }

        return templates.stream()
                .filter(template -> template.supports(workflowCode))
                .findFirst();
    }
}