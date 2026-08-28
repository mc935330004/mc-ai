package org.example.ai.agent.workflow.answer.presentation;

import org.example.ai.agent.chat.support.ReportRequestDetector;
import org.example.ai.agent.common.enums.protocol.PresentationMode;

/**
 * 决定工作流结果使用CHAT还是REPORT展示。
 *
 * 默认始终使用CHAT。
 * 只有用户明确要求生成报告，并且工作流配置了报告模板，
 * 才允许进入REPORT。
 */
public record WorkflowAnswerPolicy(
        boolean reportConfigured) {

    /**
     * 判断本次工作流回答模式。
     */
    public PresentationMode decide(String question) {
        if (!reportConfigured) {
            return PresentationMode.CHAT;
        }

        if (!ReportRequestDetector.isExplicitRequest(question)) {
            return PresentationMode.CHAT;
        }

        return PresentationMode.REPORT;
    }
}