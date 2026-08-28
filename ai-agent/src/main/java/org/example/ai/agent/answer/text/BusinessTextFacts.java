package org.example.ai.agent.answer.text;

import org.example.ai.agent.answer.model.UnifiedFactSet;

import java.util.List;
import java.util.Map;

/**
 * 结构化业务问答使用的统一事实上下文。
 *
 * 普通Capability和工作流共用本对象，
 * 不保存Markdown、HTML或页面布局。
 */
public record BusinessTextFacts(UnifiedFactSet factSet, List<String> displayObjectIds,
                                List<String> riskObjectIds, List<String> unknownObjectIds, Object safeModelInput) {

    public BusinessTextFacts {
        factSet = factSet == null
                ? UnifiedFactSet.empty()
                : factSet;

        displayObjectIds = displayObjectIds == null
                ? List.of()
                : List.copyOf(displayObjectIds);

        riskObjectIds = riskObjectIds == null
                ? List.of()
                : List.copyOf(riskObjectIds);

        unknownObjectIds = unknownObjectIds == null
                ? List.of()
                : List.copyOf(unknownObjectIds);

        safeModelInput = safeModelInput == null
                ? Map.of()
                : safeModelInput;
    }

    /**
     * 创建没有业务数据的空事实上下文。
     */
    public static BusinessTextFacts empty() {
        return new BusinessTextFacts(
                UnifiedFactSet.empty(),
                List.of(),
                List.of(),
                List.of(),
                Map.of()
        );
    }
}