package org.example.ai.agent.capability.evaluation.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.capability.evaluation.entity.CapabilityRouteLog;
import org.example.ai.agent.capability.evaluation.mapper.CapabilityRouteLogMapper;
import org.example.ai.agent.capability.evaluation.service.CapabilityRouteAuditService;
import org.example.ai.agent.capability.evaluation.vo.CapabilityCandidateSnapshotVO;
import org.example.ai.agent.capability.routing.CapabilityCandidate;
import org.example.ai.agent.common.modelusage.ModelCallContext;
import org.example.ai.agent.plan.DynamicCapabilityPlan;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CapabilityRouteAuditServiceImpl implements CapabilityRouteAuditService {

    private final CapabilityRouteLogMapper routeLogMapper;
    private final ObjectMapper objectMapper;

    @Override
    public void recordDecision(
            ModelCallContext context,
            String userQuestion,
            List<CapabilityCandidate> candidates,
            DynamicCapabilityPlan plan,
            long durationMs) {

        try {
            CapabilityRouteLog logEntity =
                    buildBase(
                            context,
                            userQuestion,
                            candidates,
                            durationMs
                    );

            if (plan != null && plan.isMatched()) {
                logEntity.setDecisionStatus("SELECTED");
                logEntity.setSelectedCapabilityCode(
                        plan.getCapabilityCode()
                );
                logEntity.setConfidence(
                        plan.getConfidence()
                );
            } else if (candidates == null
                    || candidates.isEmpty()) {
                logEntity.setDecisionStatus(
                        "NO_CANDIDATE"
                );
            } else {
                logEntity.setDecisionStatus("CLARIFY");
            }

            if (plan != null) {
                logEntity.setReason(plan.getReason());
                logEntity.setClarifyQuestion(
                        plan.getClarifyQuestion()
                );
            }

            routeLogMapper.insert(logEntity);
        } catch (Exception exception) {
            log.error(
                    "保存能力路由审计日志失败，runId={}，error={}",
                    context == null ? null : context.getRunId(),
                    exception.getMessage(),
                    exception
            );
        }
    }

    @Override
    public void recordFailure(
            ModelCallContext context,
            String userQuestion,
            List<CapabilityCandidate> candidates,
            String errorMessage,
            long durationMs) {

        try {
            CapabilityRouteLog logEntity =
                    buildBase(
                            context,
                            userQuestion,
                            candidates,
                            durationMs
                    );

            logEntity.setDecisionStatus("FAILED");
            logEntity.setReason(errorMessage);

            routeLogMapper.insert(logEntity);
        } catch (Exception exception) {
            log.error(
                    "保存能力路由失败日志异常，runId={}，error={}",
                    context == null ? null : context.getRunId(),
                    exception.getMessage(),
                    exception
            );
        }
    }

    private CapabilityRouteLog buildBase(
            ModelCallContext context,
            String userQuestion,
            List<CapabilityCandidate> candidates,
            long durationMs) throws Exception {

        CapabilityRouteLog logEntity =
                new CapabilityRouteLog();

        logEntity.setRunId(
                context == null
                        ? null
                        : context.getRunId()
        );

        logEntity.setUserQuestion(userQuestion);
        logEntity.setDurationMs(durationMs);
        logEntity.setCreatedAt(LocalDateTime.now());

        List<CapabilityCandidateSnapshotVO> snapshots =
                candidates == null
                        ? List.of()
                        : candidates.stream()
                        .map(candidate ->
                                CapabilityCandidateSnapshotVO
                                        .builder()
                                        .capabilityCode(
                                                candidate
                                                        .getCapability()
                                                        .getCapabilityCode()
                                        )
                                        .capabilityName(
                                                candidate
                                                        .getCapability()
                                                        .getCapabilityName()
                                        )
                                        .keywordScore(
                                                candidate
                                                        .getKeywordScore()
                                        )
                                        .vectorScore(
                                                candidate
                                                        .getVectorScore()
                                        )
                                        .recallScore(
                                                candidate
                                                        .getRecallScore()
                                        )
                                        .sources(
                                                candidate.getSources()
                                        )
                                        .matchedTerms(
                                                candidate
                                                        .getMatchedTerms()
                                        )
                                        .build()
                        )
                        .toList();

        logEntity.setCandidatesJson(
                objectMapper.writeValueAsString(snapshots)
        );

        return logEntity;
    }
}