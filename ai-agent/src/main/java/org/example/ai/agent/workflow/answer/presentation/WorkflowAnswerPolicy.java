package org.example.ai.agent.workflow.answer.presentation;

import org.example.ai.agent.common.enums.WorkflowPresentationMode;

import java.util.List;

/**
 * 判断当前工作流结果应该使用文字回答还是完整报表。
 *
 * 只使用明确规则，不调用大模型决定展示方式。
 */
public record WorkflowAnswerPolicy( WorkflowPresentationMode configuredMode) {

    /**
     * 明确表示不需要报表的表达。
     */
    private static final List<String> ANSWER_OVERRIDE_MARKERS = List.of(
            "不要报表",
            "不用报表",
            "不需要报表",
            "不要生成报表",
            "不要完整报表",
            "不用完整报表",
            "不需要完整报表",
            "不要生成完整报表",
            "不要以报表形式",
            "不用报表形式",
            "文字回答"
    );

    /**
     * 明确要求完整报表的表达。
     */
    private static final List<String> REPORT_MARKERS = List.of(
            "完整报表",
            "生成报表",
            "导出报表",
            "报表形式",
            "完整报告"
    );

    /**
     * 明确要求文字总结的表达。
     */
    private static final List<String> ANSWER_MARKERS = List.of(
            "简单说明",
            "总结一下",
            "汇总一下",
            "有哪些风险",
            "是否有风险",
            "是否异常"
    );

    /**
     * 根据用户本轮问题和工作流配置决定展示方式。
     */
    public WorkflowPresentationMode decide(String question) {
        String normalizedQuestion = normalize(question);
        /*
         * “不要报表”必须优先判断，
         * 避免被“报表”两个字错误识别成 REPORT。
         */
        if (containsAny(normalizedQuestion, ANSWER_OVERRIDE_MARKERS)) {
            return WorkflowPresentationMode.ANSWER;
        }

        if (containsAny(normalizedQuestion, REPORT_MARKERS)) {
            return WorkflowPresentationMode.REPORT;
        }

        if (containsAny(normalizedQuestion, ANSWER_MARKERS)) {
            return WorkflowPresentationMode.ANSWER;
        }

        /*
         * 旧发布版本缺少 presentationMode 时，
         * 保持原有报表行为。
         */
        if (configuredMode == null) {
            return WorkflowPresentationMode.REPORT;
        }

        /*
         * AUTO 第一版不调用模型猜测。
         * 没有明确报表要求时稳定使用文字回答。
         */
        return configuredMode == WorkflowPresentationMode.AUTO ? WorkflowPresentationMode.ANSWER : configuredMode;
    }

    private boolean containsAny(String question, List<String> markers) {
        if (question.isEmpty()) {
            return false;
        }
        return markers.stream().anyMatch(question::contains);
    }

    /**
     * 去掉空白，避免用户输入空格影响明确意图判断。
     */
    private String normalize(String question) {
        return question == null ? "" : question.replaceAll("\\s+", "").trim();
    }
}