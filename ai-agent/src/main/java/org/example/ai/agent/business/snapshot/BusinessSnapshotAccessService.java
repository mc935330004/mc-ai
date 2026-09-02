package org.example.ai.agent.business.snapshot;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.business.dataset.DatasetAccessWorkflowExecutor;
import org.example.ai.agent.business.dataset.ReportDatasetValidator;
import org.example.ai.agent.business.dataset.entity.ReportDataset;
import org.example.ai.agent.business.dataset.mapper.ReportDatasetMapper;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 使用当前登录认证上下文复核快照访问权。
 *
 * 不存在、配置异常、拒绝和下游失败统一返回空结果，避免泄露快照或主体是否存在。
 */
@Service
public class BusinessSnapshotAccessService {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");

    private final ReportDatasetMapper datasetMapper;
    private final DatasetAccessWorkflowExecutor accessExecutor;
    private final ObjectMapper objectMapper;

    public BusinessSnapshotAccessService(
            ReportDatasetMapper datasetMapper,
            DatasetAccessWorkflowExecutor accessExecutor,
            ObjectMapper objectMapper) {
        this.datasetMapper = Objects.requireNonNull(datasetMapper, "datasetMapper不能为空");
        this.accessExecutor = Objects.requireNonNull(accessExecutor, "accessExecutor不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper不能为空");
    }

    public Optional<AccessGrant> reauthorize(AccessCommand command) {
        if (!valid(command)) {
            return Optional.empty();
        }
        try {
            ReportDataset dataset = datasetMapper.selectOne(
                    Wrappers.<ReportDataset>lambdaQuery()
                            .eq(ReportDataset::getDatasetCode, command.datasetCode().trim())
                            .eq(ReportDataset::getEnabled, true)
                            .last("LIMIT 1")
            );
            if (!usable(dataset, command.subjectType())) {
                return Optional.empty();
            }
            DatasetAccessWorkflowExecutor.AccessDecision decision = accessExecutor.authorize(
                    new DatasetAccessWorkflowExecutor.AccessRequest(
                            command.agentRunId(), command.userId(), command.authorization(),
                            command.secureContext(), command.canonicalQuery()
                    ),
                    dataset
            );
            if (decision != DatasetAccessWorkflowExecutor.AccessDecision.ALLOWED) {
                return Optional.empty();
            }
            return Optional.of(new AccessGrant(
                    dataset.getId(), dataset.getConfigChecksum(), dataset.getFieldPolicyChecksum()
            ));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private boolean valid(AccessCommand command) {
        return command != null
                && StringUtils.hasText(command.agentRunId())
                && StringUtils.hasText(command.userId())
                && StringUtils.hasText(command.sessionId())
                && StringUtils.hasText(command.authorization())
                && StringUtils.hasText(command.datasetCode())
                && command.subjectType() != null
                && StringUtils.hasText(command.subjectId());
    }

    private boolean usable(ReportDataset dataset, BusinessSubjectType subjectType) {
        if (dataset == null || dataset.getId() == null
                || !Boolean.TRUE.equals(dataset.getEnabled())
                || !StringUtils.hasText(dataset.getAccessWorkflowCode())
                || !SHA256.matcher(Objects.toString(dataset.getConfigChecksum(), "")).matches()
                || !SHA256.matcher(Objects.toString(dataset.getFieldPolicyChecksum(), "")).matches()) {
            return false;
        }
        try {
            JsonNode types = objectMapper.readTree(dataset.getSubjectTypesJson());
            if (types == null || !types.isArray()) {
                return false;
            }
            for (JsonNode type : types) {
                if (type.isTextual() && subjectType.name().equals(type.textValue())) {
                    return true;
                }
            }
            return false;
        } catch (Exception exception) {
            return false;
        }
    }

    public record AccessGrant(
            Long datasetId,
            String configChecksum,
            String fieldPolicyChecksum) {
    }

    public record AccessCommand(
            String agentRunId,
            String userId,
            String sessionId,
            String authorization,
            Map<String, Object> secureContext,
            String datasetCode,
            BusinessSubjectType subjectType,
            String subjectId,
            Map<String, Object> canonicalQuery) {

        @SuppressWarnings("unchecked")
        public AccessCommand {
            secureContext = (Map<String, Object>) ReportDatasetValidator.freezeSafeValue(
                    secureContext == null ? Map.of() : secureContext
            );
            canonicalQuery = (Map<String, Object>) ReportDatasetValidator.freezeSafeValue(
                    canonicalQuery == null ? Map.of() : canonicalQuery
            );
        }

        /** 禁止认证、角色上下文和查询值进入自动生成日志。 */
        @Override
        public String toString() {
            return "AccessCommand[agentRunId=" + agentRunId
                    + ", userId=" + userId
                    + ", sessionId=" + sessionId
                    + ", datasetCode=" + datasetCode
                    + ", subjectType=" + subjectType
                    + ", authorizationPresent=" + StringUtils.hasText(authorization)
                    + ", secureContextSize=" + secureContext.size()
                    + ", canonicalQuerySize=" + canonicalQuery.size() + ']';
        }
    }
}
