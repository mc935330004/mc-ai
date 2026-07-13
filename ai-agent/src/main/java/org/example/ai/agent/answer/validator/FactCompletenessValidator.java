package org.example.ai.agent.answer.validator;

import org.example.ai.agent.answer.model.AnswerFact;
import org.example.ai.agent.answer.model.FactValidationResult;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 必答事实完整性校验器。
 */
@Component
public class FactCompletenessValidator {

    /**
     * 校验所有必答事实是否成功提取。
     */
    public FactValidationResult validate( List<AnswerFact> facts ) {
        if (facts == null || facts.isEmpty()) {
            return FactValidationResult.builder()
                    .requiredCount(0)
                    .coveredCount(0)
                    .missingFacts(List.of())
                    .build();
        }

        List<AnswerFact> requiredFacts = facts.stream()
                .filter(AnswerFact::isRequired)
                .toList();

        List<AnswerFact> missingFacts = requiredFacts.stream()
                        .filter(AnswerFact::isMissing)
                        .toList();

        return FactValidationResult.builder()
                .requiredCount(requiredFacts.size())
                .coveredCount(requiredFacts.size()- missingFacts.size() )
                .missingFacts(missingFacts)
                .build();
    }
}