package org.example.ai.agent.business.subject;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.business.dataset.DatasetExecutionProofVerifier;
import org.example.ai.agent.business.dataset.ReportDatasetExecutionService;
import org.example.ai.agent.business.dataset.mapper.ReportDatasetFieldMapper;
import org.example.ai.agent.business.dataset.mapper.ReportDatasetMapper;
import org.example.ai.agent.business.dataset.model.DatasetExecutionRequest;
import org.example.ai.agent.business.dataset.model.DatasetExecutionResult;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.subject.model.SubjectDirectoryPage;
import org.example.ai.agent.chat.support.ContentHashUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 通过已注册 READ 数据集查询已复权部门下的当前登录人授权成员。
 *
 * <p>部门内部标识只作为数据集输入；数据集始终以当前登录人 PERSON 身份执行，
 * 避免把待查询部门误当成授权主体。</p>
 */
@Service
public class WorkflowBackedDepartmentMemberDirectoryService
        implements DepartmentMemberDirectoryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            WorkflowBackedDepartmentMemberDirectoryService.class
    );
    private static final Pattern DATASET_CODE =
            Pattern.compile("^[A-Z][A-Z0-9_]{1,63}$");

    private final ReportDatasetExecutionService executionService;
    private final DirectoryDatasetContractValidator contractValidator;
    private final DirectoryResultParser resultParser;
    private final String datasetCode;

    public WorkflowBackedDepartmentMemberDirectoryService(
            ReportDatasetExecutionService executionService,
            DatasetExecutionProofVerifier proofVerifier,
            ReportDatasetMapper datasetMapper,
            ReportDatasetFieldMapper datasetFieldMapper,
            ObjectMapper objectMapper,
            @Value("${ai.business.subject.directory.department-member-dataset-code:}")
            String datasetCode) {
        this.executionService = Objects.requireNonNull(
                executionService,
                "executionService不能为空"
        );
        this.contractValidator = new DirectoryDatasetContractValidator(
                datasetMapper,
                datasetFieldMapper,
                objectMapper
        );
        this.resultParser = new DirectoryResultParser(proofVerifier);
        this.datasetCode = normalizeDatasetCode(datasetCode);
    }

    /**
     * 配置、输入、执行或结果协议任一环节异常时均失败关闭。
     */
    @Override
    public SubjectDirectoryPage search(DepartmentMemberDirectoryQuery query) {
        Map<String, Object> canonicalInput;
        DirectoryDatasetContractValidator.DirectoryDatasetContract contract;
        try {
            validateQuery(query);
            if (!StringUtils.hasText(datasetCode)) {
                auditFailure(query, "CONFIG_OR_INPUT");
                return SubjectDirectoryPage.denied();
            }
            canonicalInput = canonicalInput(query);
            contract = contractValidator.validate(datasetCode, canonicalInput);
        } catch (RuntimeException ignored) {
            auditFailure(query, "CONFIG_OR_INPUT");
            return SubjectDirectoryPage.denied();
        }

        DatasetExecutionResult result;
        try {
            result = executionService.execute(new DatasetExecutionRequest(
                    query.agentRunId(),
                    query.userId(),
                    query.sessionId(),
                    query.authorization(),
                    query.secureContext(),
                    datasetCode,
                    BusinessSubjectType.PERSON,
                    query.userId(),
                    canonicalInput
            ));
        } catch (RuntimeException ignored) {
            auditFailure(query, "EXECUTION");
            return SubjectDirectoryPage.denied();
        }

        try {
            return resultParser.parse(query, contract, canonicalInput, result);
        } catch (RuntimeException ignored) {
            auditFailure(query, "RESULT_PROTOCOL");
            return SubjectDirectoryPage.denied();
        }
    }

    private Map<String, Object> canonicalInput(DepartmentMemberDirectoryQuery query) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("departmentSubjectId", query.departmentSubjectId());
        input.put("pageNumber", query.pageNumber());
        input.put("pageSize", query.pageSize());
        return Map.copyOf(input);
    }

    private void validateQuery(DepartmentMemberDirectoryQuery query) {
        if (query == null
                || !StringUtils.hasText(query.agentRunId())
                || !StringUtils.hasText(query.userId())
                || !StringUtils.hasText(query.sessionId())
                || !StringUtils.hasText(query.authorization())
                || !StringUtils.hasText(query.departmentSubjectId())
                || query.pageNumber() < 1
                || query.pageSize() < 1
                || query.pageSize() > 200) {
            throw new IllegalArgumentException("部门成员目录请求不完整");
        }
        SubjectRequestLimits.requireText(query.agentRunId(), "agentRunId", 128);
        SubjectRequestLimits.requireText(query.userId(), "userId", 256);
        SubjectRequestLimits.requireText(query.sessionId(), "sessionId", 256);
        SubjectRequestLimits.requireText(query.authorization(), "authorization", 32768);
        SubjectRequestLimits.requireText(
                query.departmentSubjectId(),
                "departmentSubjectId",
                256
        );
    }

    private String normalizeDatasetCode(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        return DATASET_CODE.matcher(normalized).matches() ? normalized : null;
    }

    private void auditFailure(DepartmentMemberDirectoryQuery query, String category) {
        String traceHash = query == null || !StringUtils.hasText(query.agentRunId())
                ? "missing"
                : ContentHashUtils.sha256(query.agentRunId()).substring(0, 12);
        LOGGER.warn(
                "部门成员目录查询失败关闭 traceHash={} category={} datasetCode={}",
                traceHash,
                category,
                StringUtils.hasText(datasetCode) ? datasetCode : "unconfigured"
        );
    }
}
