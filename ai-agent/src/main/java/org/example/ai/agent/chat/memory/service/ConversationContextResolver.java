package org.example.ai.agent.chat.memory.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.chat.memory.model.BusinessConversationState;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 中文注释：识别依赖上一轮信息的追问，并生成供路由器使用的完整问题。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationContextResolver {
    /**
     * 中文注释：使用模型完成开放式追问语义判断。
     */
    private final ConversationContextRewriteService rewriteService;

    /**
     * 中文注释：命中这些明确表达时立即删除当前会话状态。
     */
    private static final List<String> RESET_MARKERS = List.of(
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
    private final ConversationStateService conversationStateService;

    /**
     * 中文注释：显式重置走确定性规则，普通问题走语义上下文判断。
     */
    public String resolve( AgentRequest request,String runId) {
        if (request == null || !StringUtils.hasText(request.getUserQuestion())) {
            return null;
        }

        String question = request.getUserQuestion().trim();
        if (isContextReset(question)) {
            return resetContext(request, question);
        }
        try {
            return conversationStateService.loadState(request.getUserId(),request.getConversationId())
                    .flatMap(state ->
                            rewriteService.rewrite(
                                    request,
                                    state,
                                    runId
                            ).map(rewrittenQuestion -> {
                                /*
                                 * 中文注释：只有模型可靠判定为 FOLLOW_UP，
                                 * 才允许注入上一轮路由身份和参数。
                                 */
                                applyState(request, state);
                                return rewrittenQuestion;
                            })
                    )
                    .orElse(null);
        } catch (RuntimeException exception) {
            // 中文注释：读取或改写失败时使用原始问题，不携带历史业务参数。
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
     * 中文注释：将上一轮路由身份和参数写入当前服务端请求对象。
     */
    private void applyState(
            AgentRequest request,
            BusinessConversationState state
    ) {
        request.setPreviousWorkflowCode(
                state.getWorkflowCode()
        );
        request.setPreviousCapabilityCode(
                state.getCapabilityCode()
        );

        Map<String, Object> lastInput =
                state.getLastInput();

        request.setInheritedInput(
                lastInput == null ? new LinkedHashMap<>()
                        : new LinkedHashMap<>(lastInput)
        );
    }

    /**
     * 中文注释：判断用户是否明确要求放弃之前的上下文。
     */
    private boolean isContextReset(String question) {
        return RESET_MARKERS.stream()
                .anyMatch(question::contains);
    }

    /**
     * 中文注释：清理数据库状态，并返回去除重置命令后的新问题。
     */
    private String resetContext(AgentRequest request,String question) {
        /*
         * 中文注释：主动重置必须真实完成。
         * 删除失败时直接抛出异常，不能向用户假装已经清理。
         */
        conversationStateService.clearState(
                request.getUserId(),
                request.getConversationId()
        );

        request.setContextReset(true);
        request.setPreviousWorkflowCode(null);
        request.setPreviousCapabilityCode(null);
        request.setInheritedInput(
                new LinkedHashMap<>()
        );

        return removeResetMarkers(question);
    }

    /**
     * 中文注释：支持“换个话题，查询其他项目”在清理后继续执行新问题。
     */
    private String removeResetMarkers(String question) {
        String result = question;
        for (String marker : RESET_MARKERS) {
            result = result.replace(marker, "");
        }

        // 中文注释：移除重置短语后遗留的开头标点和空格。
        return result.replaceFirst(
                        "^[\\s，,。；;：:]+",
                        ""
                )
                .trim();
    }
}