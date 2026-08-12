package org.example.ai.agent.modelconfig.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.modelconfig.dto.ModelConfigAuditQueryDTO;
import org.example.ai.agent.modelconfig.entity.ModelConfigAuditLog;
import org.example.ai.agent.modelconfig.mapper.ModelConfigAuditLogMapper;
import org.example.ai.agent.modelconfig.vo.ModelConfigAuditVO;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型配置审计日志查询服务。
 */
@Service
@RequiredArgsConstructor
public class ModelConfigAuditService {

    private static final long MAX_PAGE_SIZE = 100L;

    private final ModelConfigAuditLogMapper auditLogMapper;

    /**
     * 分页查询模型配置和授权变更审计日志。
     */
    public Page<ModelConfigAuditVO> page(
            Page<ModelConfigAuditLog> requestPage,
            ModelConfigAuditQueryDTO query) {

        if (query.getStartTime() != null
                && query.getEndTime() != null
                && query.getStartTime().isAfter(query.getEndTime())) {
            throw new BusinessException(
                    400,
                    "审计日志开始时间不能晚于结束时间"
            );
        }

        long current = Math.max(requestPage.getCurrent(), 1L);
        long size = Math.min(
                Math.max(requestPage.getSize(), 1L),
                MAX_PAGE_SIZE
        );

        LambdaQueryWrapper<ModelConfigAuditLog> wrapper =new LambdaQueryWrapper<>();
        if (StringUtils.hasText(query.getOperatorId())) {
            wrapper.eq(ModelConfigAuditLog::getOperatorId,query.getOperatorId().trim());
        }
        if (StringUtils.hasText(query.getActionType())) {
            wrapper.eq(ModelConfigAuditLog::getActionType,query.getActionType().trim());
        }
        if (StringUtils.hasText(query.getTargetType())) {
            wrapper.eq(
                    ModelConfigAuditLog::getTargetType,
                    query.getTargetType().trim()
            );
        }

        if (StringUtils.hasText(query.getTargetKey())) {
            wrapper.eq(
                    ModelConfigAuditLog::getTargetKey,
                    query.getTargetKey().trim()
            );
        }

        if (query.getStartTime() != null) {
            wrapper.ge(
                    ModelConfigAuditLog::getCreatedAt,
                    query.getStartTime()
            );
        }

        if (query.getEndTime() != null) {
            wrapper.le(
                    ModelConfigAuditLog::getCreatedAt,
                    query.getEndTime()
            );
        }

        // 时间相同时继续按照主键倒序，保证分页结果顺序稳定。
        wrapper.orderByDesc(ModelConfigAuditLog::getCreatedAt)
                .orderByDesc(ModelConfigAuditLog::getId);

        Page<ModelConfigAuditLog> entityPage =
                auditLogMapper.selectPage(
                        new Page<>(current, size),
                        wrapper
                );

        List<ModelConfigAuditVO> records =
                new ArrayList<>(entityPage.getRecords().size());

        for (ModelConfigAuditLog entity : entityPage.getRecords()) {
            ModelConfigAuditVO vo = new ModelConfigAuditVO();
            BeanUtils.copyProperties(entity, vo);
            records.add(vo);
        }

        Page<ModelConfigAuditVO> resultPage =
                new Page<>(
                        entityPage.getCurrent(),
                        entityPage.getSize(),
                        entityPage.getTotal()
                );

        resultPage.setRecords(records);
        return resultPage;
    }
}