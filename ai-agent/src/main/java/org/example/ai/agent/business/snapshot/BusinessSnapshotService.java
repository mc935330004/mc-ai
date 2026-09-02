package org.example.ai.agent.business.snapshot;

import org.example.ai.agent.business.dataset.ReportDatasetValidator;
import org.example.ai.agent.business.dataset.entity.ReportDataset;
import org.example.ai.agent.business.dataset.model.DatasetExecutionResult;
import org.example.ai.agent.business.snapshot.entity.BusinessSnapshot;

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
            String subjectType,
            String subjectId,
            ReportDataset dataset,
            Map<String, Object> canonicalQuery,
            String sourceSnapshotId,
            List<ItemCommand> items) {

        @SuppressWarnings("unchecked")
        public CreateCommand {
            dataset = copyDataset(dataset);
            Object frozen = ReportDatasetValidator.freezeSafeValue(
                    canonicalQuery == null ? Map.of() : canonicalQuery
            );
            canonicalQuery = (Map<String, Object>) frozen;
            items = items == null ? List.of() : List.copyOf(items);
        }

        /**
         * ReportDataset 是可变持久化实体，进入异步或事务边界前只复制本任务需要的配置，
         * 防止调用方后续修改实体导致快照口径漂移。
         */
        private static ReportDataset copyDataset(ReportDataset source) {
            if (source == null) {
                return null;
            }
            ReportDataset copy = new ReportDataset();
            copy.setDatasetCode(source.getDatasetCode());
            copy.setQueryWorkflowCode(source.getQueryWorkflowCode());
            copy.setTtlMinutes(source.getTtlMinutes());
            copy.setEnabled(source.getEnabled());
            copy.setConfigChecksum(source.getConfigChecksum());
            copy.setFieldPolicyChecksum(source.getFieldPolicyChecksum());
            return copy;
        }
    }

    /**
     * 模块、员工或批次的安全执行项。
     */
    record ItemCommand(
            String itemKey,
            String associationType,
            DatasetExecutionResult result,
            Integer totalCount,
            Integer successCount,
            Integer failureCount) {
    }
}
