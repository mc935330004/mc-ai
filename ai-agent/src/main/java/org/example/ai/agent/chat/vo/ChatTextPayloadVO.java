package org.example.ai.agent.chat.vo;

import lombok.Builder;
import lombok.Data;
import org.example.ai.agent.modules.knowledgebase.dto.KnowledgeDocumentQueryResponse;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionOutcome;

import java.util.List;

/**
 * 中文注释：TEXT 类型助手消息的结构化展示快照。
 * Markdown 正文仍保存在 AiChatMessage.content 中。
 */
@Data
@Builder
public class ChatTextPayloadVO {

    /** 中文注释：用于展示核心业务指标卡片。 */
    private List<FactPreviewVO> facts;

    /** 中文注释：用于历史会话恢复 RAG 引用来源。 */
    private List<KnowledgeDocumentQueryResponse.Reference> references;

    /** 中文注释：用于历史会话恢复工作流执行结果。 */
    private WorkflowExecutionOutcome workflow;

    /**
     * 消息展示类型。
     *
     * REPORT：使用AI报告组件展示；
     * MARKDOWN：使用普通Markdown组件展示。
     */
    private String presentationType;

    /**
     * 报告标题。
     */
    private String presentationTitle;
}