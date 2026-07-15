package org.example.ai.agent.capability.evaluation.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.evaluation.dto.CapabilityRouteEvalRequest;
import org.example.ai.agent.capability.evaluation.entity.CapabilityRouteCase;
import org.example.ai.agent.capability.evaluation.entity.CapabilityRouteEvalDetail;
import org.example.ai.agent.capability.evaluation.entity.CapabilityRouteEvalRun;
import org.example.ai.agent.capability.evaluation.mapper.CapabilityRouteCaseMapper;
import org.example.ai.agent.capability.evaluation.mapper.CapabilityRouteEvalDetailMapper;
import org.example.ai.agent.capability.evaluation.mapper.CapabilityRouteEvalRunMapper;
import org.example.ai.agent.capability.evaluation.service.CapabilityRouteEvaluationService;
import org.example.ai.agent.capability.evaluation.vo.CapabilityRouteEvalResultVO;
import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.plan.DynamicCapabilityPlan;
import org.example.ai.agent.router.IntentResult;
import org.example.ai.agent.router.IntentRouter;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CapabilityRouteEvaluationServiceImpl
        implements CapabilityRouteEvaluationService {

    private final CapabilityRouteCaseMapper routeCaseMapper;
    private final CapabilityRouteEvalRunMapper evalRunMapper;
    private final CapabilityRouteEvalDetailMapper evalDetailMapper;
    private final IntentRouter intentRouter;
    private final ObjectMapper objectMapper;

    @Override
    public CapabilityRouteEvalResultVO run( CapabilityRouteEvalRequest request) {

        int limit =
                request == null
                        || request.getLimit() == null
                        ? 200
                        : Math.min(
                                Math.max(request.getLimit(), 1),
                                1000
                        );

        List<CapabilityRouteCase> cases =
                routeCaseMapper.selectList(
                        new LambdaQueryWrapper<CapabilityRouteCase>()
                                .eq(
                                        CapabilityRouteCase::getEnabled,
                                        1
                                )
                                .orderByAsc(
                                        CapabilityRouteCase::getId
                                )
                                .last("LIMIT " + limit)
                );

        String evalRunId ="eval-"+ UUID.randomUUID().toString()
                        .replace("-", "");

        CapabilityRouteEvalRun evalRun = new CapabilityRouteEvalRun();

        evalRun.setEvalRunId(evalRunId);
        evalRun.setStatus("RUNNING");
        evalRun.setTotalCount(cases.size());
        evalRun.setPassedCount(0);
        evalRun.setFailedCount(0);
        evalRun.setAccuracy(BigDecimal.ZERO);
        evalRun.setStartedAt(LocalDateTime.now());
        evalRunMapper.insert(evalRun);
        int passedCount = 0;
        try {
            for (CapabilityRouteCase routeCase : cases) {
                boolean passed =evaluateCase(evalRunId,routeCase);
                if (passed) {
                    passedCount++;
                }
            }

            int failedCount =cases.size() - passedCount;

            BigDecimal accuracy = cases.isEmpty()
                            ? BigDecimal.ZERO
                            : BigDecimal.valueOf((double) passedCount/ cases.size() ).setScale(
                                    6,
                                    RoundingMode.HALF_UP);

            evalRun.setStatus("SUCCESS");
            evalRun.setPassedCount(passedCount);
            evalRun.setFailedCount(failedCount);
            evalRun.setAccuracy(accuracy);
            evalRun.setFinishedAt(LocalDateTime.now());

            evalRunMapper.updateById(evalRun);

            return CapabilityRouteEvalResultVO.builder()
                    .evalRunId(evalRunId)
                    .totalCount(cases.size())
                    .passedCount(passedCount)
                    .failedCount(failedCount)
                    .accuracy(accuracy.doubleValue())
                    .build();
        } catch (Exception exception) {
            evalRun.setStatus("FAILED");
            evalRun.setErrorMessage(exception.getMessage());
            evalRun.setFinishedAt(LocalDateTime.now());
            evalRunMapper.updateById(evalRun);

            throw exception;
        }
    }

    private boolean evaluateCase(String evalRunId,CapabilityRouteCase routeCase) {

        long startTime =System.currentTimeMillis();

        AgentRequest request =new AgentRequest();

        request.setConversationId(evalRunId);
        request.setUserId("ROUTE_EVALUATOR");
        request.setUserQuestion(routeCase.getUserQuestion());

        String caseRunId =evalRunId + "-case-" + routeCase.getId();

        IntentResult actual =intentRouter.route(request,caseRunId);

        DynamicCapabilityPlan actualPlan =actual.getDynamicCapabilityPlan();

        String actualCapabilityCode =actualPlan == null
                        ? null
                        : actualPlan.getCapabilityCode();

        String actualInputJson =
                toJson(
                        actualPlan == null
                                ? null
                                : actualPlan.getInput()
                );

        List<String> failures =
                new java.util.ArrayList<>();

        if (StringUtils.hasText(
                routeCase.getExpectedRouteType())) {

            String actualRouteType =
                    actual.getRouteType() == null
                            ? null
                            : actual.getRouteType().name();

            if (!routeCase
                    .getExpectedRouteType()
                    .equals(actualRouteType)) {
                failures.add(
                        "路由类型不一致，expected="
                                + routeCase.getExpectedRouteType()
                                + ", actual="
                                + actualRouteType
                );
            }
        }

        if (StringUtils.hasText(
                routeCase.getExpectedCapabilityCode())
                && !routeCase
                .getExpectedCapabilityCode()
                .equals(actualCapabilityCode)) {

            failures.add(
                    "能力编码不一致，expected="
                            + routeCase
                            .getExpectedCapabilityCode()
                            + ", actual="
                            + actualCapabilityCode
            );
        }

        boolean expectedClarify =
                Integer.valueOf(1).equals(
                        routeCase.getShouldClarify()
                );

        if (expectedClarify
                != actual.isNeedClarify()) {
            failures.add(
                    "追问状态不一致，expected="
                            + expectedClarify
                            + ", actual="
                            + actual.isNeedClarify()
            );
        }

        if (StringUtils.hasText(
                routeCase.getExpectedInputJson())
                && !jsonEquals(
                routeCase.getExpectedInputJson(),
                actualInputJson)) {

            failures.add("接口参数不一致");
        }

        boolean passed =
                failures.isEmpty();

        CapabilityRouteEvalDetail detail =
                new CapabilityRouteEvalDetail();

        detail.setEvalRunId(evalRunId);
        detail.setCaseId(routeCase.getId());
        detail.setUserQuestion(
                routeCase.getUserQuestion()
        );
        detail.setExpectedRouteType(
                routeCase.getExpectedRouteType()
        );
        detail.setActualRouteType(
                actual.getRouteType() == null
                        ? null
                        : actual.getRouteType().name()
        );
        detail.setExpectedCapabilityCode(
                routeCase.getExpectedCapabilityCode()
        );
        detail.setActualCapabilityCode(
                actualCapabilityCode
        );
        detail.setExpectedClarify(
                expectedClarify ? 1 : 0
        );
        detail.setActualClarify(
                actual.isNeedClarify() ? 1 : 0
        );
        detail.setExpectedInputJson(
                routeCase.getExpectedInputJson()
        );
        detail.setActualInputJson(actualInputJson);
        detail.setPassed(passed ? 1 : 0);
        detail.setFailureReason(
                passed
                        ? null
                        : String.join("；", failures)
        );
        detail.setDurationMs(
                System.currentTimeMillis()
                        - startTime
        );
        detail.setCreatedAt(LocalDateTime.now());

        evalDetailMapper.insert(detail);

        return passed;
    }

    private boolean jsonEquals(
            String expected,
            String actual) {

        try {
            JsonNode expectedNode =objectMapper.readTree(expected);
            JsonNode actualNode =objectMapper.readTree(actual);
            return expectedNode.equals(actualNode);
        } catch (Exception exception) {
            return false;
        }
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }

        try {
            return objectMapper
                    .writeValueAsString(value);
        } catch (Exception exception) {
            return null;
        }
    }
}