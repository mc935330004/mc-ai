package org.example.ai.agent.business.snapshot;

import org.example.ai.agent.business.dataset.ReportDatasetValidator;
import org.example.ai.agent.business.dataset.model.DatasetExecutionResult;
import org.example.ai.agent.business.snapshot.entity.BusinessSnapshot;
import org.example.ai.agent.business.model.AssociationType;
import org.example.ai.agent.business.model.BusinessSubjectType;

import java.util.List;
import java.util.Map;

/**
 * 安全业务快照服务。
 *
 * 创建命令只接收 Task6 已完成字段策略处理的执行结果，不开放任意事实 JSON 入参。
 */
public interface BusinessSnapshotService {

    BusinessSnapshot create(CreateCommand command);

    /**
     * 一次快照创建所需的稳定上下文。
     */
    record CreateCommand(
            String userId,
            String sessionId,
            BusinessSubjectType subjectType,
            String subjectId,
            String datasetCode,
            Map<String, Object> canonicalQuery,
            String sourceSnapshotId,
            List<ItemCommand> items) {

        @SuppressWarnings("unchecked")
        public CreateCommand {
            Object frozen = ReportDatasetValidator.freezeSafeValue(
                    canonicalQuery == null ? Map.of() : canonicalQuery
            );
            canonicalQuery = (Map<String, Object>) frozen;
            items = items == null ? List.of() : List.copyOf(items);
        }

        /** 仅展示持久化路由摘要，隐藏主体标识和规范查询值。 */
        @Override
        public String toString() {
            return "CreateCommand[userId=" + userId
                    + ", sessionId=" + sessionId
                    + ", subjectType=" + subjectType
                    + ", subjectIdPresent=" + (subjectId != null && !subjectId.isBlank())
                    + ", datasetCode=" + datasetCode
                    + ", canonicalQuerySize=" + canonicalQuery.size()
                    + ", sourceSnapshotPresent="
                    + (sourceSnapshotId != null && !sourceSnapshotId.isBlank())
                    + ", itemCount=" + items.size() + ']';
        }

    }

    /**
     * 模块、员工或批次的安全执行项。
     */
    record ItemCommand(
            String itemKey,
            AssociationType associationType,
            DatasetExecutionResult result,
            Integer totalCount,
            Integer successCount,
            Integer failureCount) {
    }
}
