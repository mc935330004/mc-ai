package org.example.ai.agent.modelusage.controller;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.result.Result;
import org.example.ai.agent.modelusage.entity.ModelUsageRecord;
import org.example.ai.agent.modelusage.service.ModelUsageService;
import org.example.ai.agent.modelusage.vo.RunModelUsageVO;
import org.example.ai.agent.security.CurrentUserProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 大模型 Token 使用查询接口。
 *
 * 第一阶段先提供按 runId 查询，
 * 后续统计报表再增加日期、用户、模型等聚合维度。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/agent/modelUsage")
public class ModelUsageController {

    private final ModelUsageService modelUsageService;
    private final CurrentUserProvider currentUserProvider;
    /**
     * 查询某次 Agent 运行的 Token 消耗。
     *
     * 请求示例：
     * GET /api/agent/model-usage/run/{runId}
     */
    @GetMapping("/run/{runId}")
    public Result<RunModelUsageVO> getByRunId( @PathVariable String runId ) {
        String userId = currentUserProvider.getRequiredUserId();

        List<ModelUsageRecord> records = modelUsageService.listByRunIdAndUserId(runId, userId );


        long promptTokens = records.stream()
                .map(ModelUsageRecord::getPromptTokens)
                .filter(value -> value != null)
                .mapToLong(Integer::longValue)
                .sum();

        long completionTokens = records.stream()
                .map(ModelUsageRecord::getCompletionTokens)
                .filter(value -> value != null)
                .mapToLong(Integer::longValue)
                .sum();

        long totalTokens = records.stream()
                .map(ModelUsageRecord::getTotalTokens)
                .filter(value -> value != null)
                .mapToLong(Integer::longValue)
                .sum();

        RunModelUsageVO result = RunModelUsageVO.builder()
                .runId(runId)
                .modelCallCount(records.size())
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .totalTokens(totalTokens)
                .calls(records)
                .build();

        return Result.success(result);
    }
}