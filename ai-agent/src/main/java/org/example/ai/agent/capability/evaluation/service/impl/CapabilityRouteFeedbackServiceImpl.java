package org.example.ai.agent.capability.evaluation.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.evaluation.dto.CapabilityRouteFeedbackDTO;
import org.example.ai.agent.capability.evaluation.entity.CapabilityRouteCase;
import org.example.ai.agent.capability.evaluation.entity.CapabilityRouteFeedback;
import org.example.ai.agent.capability.evaluation.entity.CapabilityRouteLog;
import org.example.ai.agent.capability.evaluation.mapper.CapabilityRouteCaseMapper;
import org.example.ai.agent.capability.evaluation.mapper.CapabilityRouteFeedbackMapper;
import org.example.ai.agent.capability.evaluation.mapper.CapabilityRouteLogMapper;
import org.example.ai.agent.capability.evaluation.service.CapabilityRouteFeedbackService;
import org.example.ai.agent.capability.service.CapabilityDefinitionService;
import org.example.ai.agent.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class CapabilityRouteFeedbackServiceImpl
        implements CapabilityRouteFeedbackService {

    private final CapabilityRouteLogMapper routeLogMapper;
    private final CapabilityRouteFeedbackMapper feedbackMapper;
    private final CapabilityRouteCaseMapper routeCaseMapper;
    private final CapabilityDefinitionService capabilityDefinitionService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CapabilityRouteFeedback submit(
            CapabilityRouteFeedbackDTO dto,
            String submittedBy) {

        CapabilityRouteLog routeLog =
                routeLogMapper.selectOne(
                        new LambdaQueryWrapper<CapabilityRouteLog>()
                                .eq(
                                        CapabilityRouteLog::getRunId,
                                        dto.getRunId()
                                )
                );

        if (routeLog == null) {
            throw new BusinessException(
                    404,
                    "没有找到对应路由日志："
                            + dto.getRunId()
            );
        }

        if (!Boolean.TRUE.equals(dto.getCorrect())
                && !StringUtils.hasText(
                dto.getExpectedCapabilityCode())) {
            throw new BusinessException(
                    400,
                    "原路由错误时必须填写正确的能力编码"
            );
        }

        if (!Boolean.TRUE.equals(dto.getCorrect())) {
            validateExpectedCapability(
                    dto.getExpectedCapabilityCode()
            );
        }

        CapabilityRouteFeedback feedback =
                new CapabilityRouteFeedback();

        feedback.setRouteLogId(routeLog.getId());
        feedback.setRunId(routeLog.getRunId());
        feedback.setOriginalCapabilityCode(
                routeLog.getSelectedCapabilityCode()
        );
        feedback.setCorrectFlag(
                Boolean.TRUE.equals(dto.getCorrect())
                        ? 1
                        : 0
        );
        feedback.setExpectedCapabilityCode(
                dto.getExpectedCapabilityCode()
        );
        feedback.setFeedbackStatus("PENDING");
        feedback.setComment(dto.getComment());
        feedback.setSubmittedBy(submittedBy);
        feedback.setCreatedAt(LocalDateTime.now());

        feedbackMapper.insert(feedback);

        return feedback;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approve(
            Long feedbackId,
            String reviewedBy) {

        CapabilityRouteFeedback feedback =
                getPending(feedbackId);

        feedback.setFeedbackStatus("APPROVED");
        feedback.setReviewedBy(reviewedBy);
        feedback.setReviewedAt(LocalDateTime.now());

        feedbackMapper.updateById(feedback);

        /*
         * 只有确认错误的反馈才需要沉淀为纠错样本。
         */
        if (Integer.valueOf(0)
                .equals(feedback.getCorrectFlag())) {

            validateExpectedCapability(
                    feedback.getExpectedCapabilityCode()
            );

            CapabilityRouteLog routeLog =
                    routeLogMapper.selectById(
                            feedback.getRouteLogId()
                    );

            CapabilityRouteCase routeCase =
                    new CapabilityRouteCase();

            routeCase.setUserQuestion(
                    routeLog.getUserQuestion()
            );

            routeCase.setExpectedCapabilityCode(
                    feedback.getExpectedCapabilityCode()
            );

            routeCase.setShouldClarify(0);
            routeCase.setSourceType("FEEDBACK");
            routeCase.setSourceFeedbackId(feedback.getId());
            routeCase.setTags("人工纠错");
            routeCase.setEnabled(1);
            routeCase.setCreatedAt(LocalDateTime.now());
            routeCase.setUpdatedAt(LocalDateTime.now());

            routeCaseMapper.insert(routeCase);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reject(
            Long feedbackId,
            String reviewedBy) {

        CapabilityRouteFeedback feedback =
                getPending(feedbackId);

        feedback.setFeedbackStatus("REJECTED");
        feedback.setReviewedBy(reviewedBy);
        feedback.setReviewedAt(LocalDateTime.now());

        feedbackMapper.updateById(feedback);
    }

    private CapabilityRouteFeedback getPending(
            Long feedbackId) {

        CapabilityRouteFeedback feedback =
                feedbackMapper.selectById(feedbackId);

        if (feedback == null) {
            throw new BusinessException(
                    404,
                    "反馈不存在：" + feedbackId
            );
        }

        if (!"PENDING".equals(
                feedback.getFeedbackStatus())) {
            throw new BusinessException(
                    400,
                    "只有PENDING反馈可以审核"
            );
        }

        return feedback;
    }

    private void validateExpectedCapability(
            String capabilityCode) {

        CapabilityDefinition capability =
                capabilityDefinitionService
                        .getEnabledByCode(capabilityCode);

        if (capability == null) {
            throw new BusinessException(
                    400,
                    "正确能力不存在、未启用或未发布："
                            + capabilityCode
            );
        }
    }
}