package org.example.ai.agent.chat.memory.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.chat.memory.model.BusinessConversationState;
import org.example.ai.agent.chat.memory.model.ConversationRewriteDecision;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 识别当前问题与上一轮业务状态的关系。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationContextResolver {

    /**
     * 使用模型完成开放式会话关系分类。
     */
    private final ConversationContextRewriteService rewriteService;

    private final ConversationStateService  conversationStateService;

    /**
     * 只有确定性的系统控制命令使用本地规则。
     *
     * 不再通过关键词判断汇总、分析或者重新查询。
     */
    private static final List<String> RESET_MARKERS =
            List.of(
                    "不要参考之前的数据",
                    "不要参考上面的信息",
                    "忽略之前的内容",
                    "忽略上面的信息",
                    "忽略之前",
                    "忽略上面",
                    "不参考之前",
                    "不参考上面",
                    "清除上下文",
                    "清空上下文",
                    "重新开始",
                    "换个话题"
            );

    /**
     * 解析当前问题与上一轮业务状态的关系。
     */
    public String resolve(
            AgentRequest request,
            String runId) {

        if (request == null || !StringUtils.hasText( request.getUserQuestion())) {

            return null;
        }

        String question =
                request.getUserQuestion().trim();

        /*
         * 清除上下文属于确定性系统命令，
         * 不需要调用模型判断。
         */
        if (isContextReset(question)) {
            return resetContext(
                    request,
                    question
            );
        }

        try {
            BusinessConversationState state =conversationStateService
                            .loadState(request.getUserId(),
                            request.getConversationId()).orElse(null);
            if (state == null) {
                return null;
            }
            ConversationRewriteDecision decision =
                    rewriteService.decide(
                                    request,
                                    state,
                                    runId
                            )
                            .orElse(null);

            if (decision == null) {
                return null;
            }

            /*
             * 分析上一轮已经返回的数据。
             */
            if (rewriteService.isResultAnalysis(decision)) {

                /*
                 * 即使快照不存在，也要保留RESULT_ANALYSIS路由，
                 * 防止继续进入工作流或者能力选择。
                 *
                 * 编排器会返回“请先查询数据”的用户提示。
                 */
                request.setResultAnalysisRequest(
                        true
                );

                if (StringUtils.hasText(state.getResultArtifactId())) {
                    applyState(
                            request,
                            state
                    );
                }

                return StringUtils.hasText(
                        decision.rewrittenQuestion())
                        ? decision.rewrittenQuestion()
                        .trim()
                        : question;
            }

            /*
             * 用户补充或者修改查询条件，
             * 重新执行上一轮查询。
             */
            if (rewriteService.isFollowUpQuery(
                    decision)) {

                applyState(
                        request,
                        state
                );

                return decision
                        .rewrittenQuestion()
                        .trim();
            }

            /*
             * NEW_TOPIC和UNCERTAIN不继承旧状态。
             */
            return null;

        } catch (RuntimeException exception) {
            /*
             * 分类失败时关闭上下文继承，
             * 防止请求到错误业务对象。
             */
            log.warn(
                    "解析会话上下文失败，conversationId={}，runId={}",
                    request.getConversationId(),
                    runId,
                    exception
            );

            return null;
        }
    }

    /**
     * 将服务端可信会话状态写入当前请求。
     */
    private void applyState(
            AgentRequest request,
            BusinessConversationState state) {

        request.setPreviousWorkflowCode(
                state.getWorkflowCode()
        );

        request.setPreviousCapabilityCode(
                state.getCapabilityCode()
        );

        Map<String, Object> lastInput =state.getLastInput();

        request.setInheritedInput(
                lastInput == null
                        ? new LinkedHashMap<>()
                        : new LinkedHashMap<>(
                        lastInput
                )
        );

        /*
         * artifactId只能来自服务端会话状态。
         */
        request.setResultArtifactId(
                state.getResultArtifactId()
        );
    }

    private boolean isContextReset(
            String question) {
        return RESET_MARKERS.stream().anyMatch(question::contains);
    }

    /**
     * 清理当前会话业务状态。
     */
    private String resetContext(
            AgentRequest request,
            String question) {

        conversationStateService.clearState(
                request.getUserId(),
                request.getConversationId()
        );

        request.setContextReset(true);
        request.setPreviousWorkflowCode(null);
        request.setPreviousCapabilityCode(null);
        request.setResultArtifactId(null);
        request.setResultAnalysisRequest(false);

        request.setInheritedInput( new LinkedHashMap<>());

        return removeResetMarkers(question);
    }

    /**
     * 支持“换个话题，查询其他项目”清理后继续执行。
     */
    private String removeResetMarkers(
            String question) {
        String result = question;
        for (String marker :RESET_MARKERS) {
            result = result.replace(
                    marker,
                    ""
            );
        }
        return result.replaceFirst(
                        "^[\\s，,。；;：:]+",
                        "") .trim();
    }
}