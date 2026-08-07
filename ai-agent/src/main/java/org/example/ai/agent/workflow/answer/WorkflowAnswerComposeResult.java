package org.example.ai.agent.workflow.answer;

/**
 * 工作流回答生成结果。
 *
 * @param answer          最终用户可见内容
 * @param artifactId      完整结果快照ID
 * @param reportGenerated 是否成功生成AI报告
 */
public record WorkflowAnswerComposeResult(
        String answer,
        String artifactId,
        boolean reportGenerated) {

    /**
     * 普通提示文本，不包含可复用Artifact。
     */
    public static WorkflowAnswerComposeResult text(String answer) {

        return new WorkflowAnswerComposeResult(
                answer,
                null,
                false
        );
    }

    /**
     * 正常生成AI报告。
     */
    public static WorkflowAnswerComposeResult report(
            String answer,
            String artifactId) {

        return new WorkflowAnswerComposeResult(
                answer,
                artifactId,
                true
        );
    }

    /**
     * 查询数据已保存，但AI报告生成失败。
     */
    public static WorkflowAnswerComposeResult recoverable(
            String answer,
            String artifactId) {

        return new WorkflowAnswerComposeResult(
                answer,
                artifactId,
                false
        );
    }
}