package org.example.ai.agent.capability.service;

import org.example.ai.agent.capability.dto.FieldSemanticConfirmDTO;
import org.example.ai.agent.capability.dto.FieldSemanticSuggestDTO;
import org.example.ai.agent.capability.vo.FieldSemanticSuggestionVO;

import java.util.List;

/**
 * AI字段语义生成服务。
 */
public interface FieldSemanticService {

    /**
     * 生成建议，不修改数据库。
     */
    List<FieldSemanticSuggestionVO> suggest( FieldSemanticSuggestDTO dto);

    /**
     * 保存用户确认后的建议。
     */
    Integer confirm(FieldSemanticConfirmDTO dto);
}