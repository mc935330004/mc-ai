package org.example.ai.agent.workflow.answer.text;

import java.util.List;

/**
 * 工作流文字回答的可信事实。
 *
 * deterministicMarkdown 由后端确定性生成，
 * 不依赖大模型是否调用成功。
 */
public record WorkflowTextFacts(String deterministicMarkdown, List<String> displayObjectIds, List<String> riskObjectIds, List<String> unknownObjectIds, Object safeModelInput, boolean dataComplete) {

    public WorkflowTextFacts {
        deterministicMarkdown = deterministicMarkdown == null
                        ? ""
                        : deterministicMarkdown.trim();
        displayObjectIds = displayObjectIds == null
                        ? List.of()
                        : List.copyOf(displayObjectIds);
        riskObjectIds = riskObjectIds == null
                        ? List.of()
                        : List.copyOf(riskObjectIds);
        unknownObjectIds = unknownObjectIds == null
                        ? List.of()
                        : List.copyOf(unknownObjectIds);
    }
}