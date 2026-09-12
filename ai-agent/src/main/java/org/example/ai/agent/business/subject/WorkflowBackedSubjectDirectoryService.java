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
 * 通过已注册报告数据集查询当前登录人有权访问的项目、人员或部门目录。
 *
 * <p>本服务只编排单次目录查询；目录契约和返回协议分别由独立校验器处理，
 * 不在候选阶段扇出合同、打卡等业务详情查询。</p>
 */
@Service
public class WorkflowBackedSubjectDirectoryService
        implements ProjectDirectoryService,
        AuthorizedPersonDirectoryService,
        DepartmentDirectoryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            WorkflowBackedSubjectDirectoryService.class
    );
    private static final Pattern DATASET_CODE =
            Pattern.compile("^[A-Z][A-Z0-9_]{1,63}$");

    private final ReportDatasetExecutionService executionService;
    private final DirectoryDatasetContractValidator contractValidator;
    private final DirectoryResultParser resultParser;
    private final String projectDatasetCode;
    private final String personDatasetCode;
    private final String departmentDatasetCode;

    public WorkflowBackedSubjectDirectoryService(
            ReportDatasetExecutionService executionService,
            DatasetExecutionProofVerifier proofVerifier,
            ReportDatasetMapper datasetMapper,
            ReportDatasetFieldMapper datasetFieldMapper,
            ObjectMapper objectMapper,
            @Value("${ai.business.subject.directory.project-dataset-code:}")
            String projectDatasetCode,
            @Value("${ai.business.subject.directory.person-dataset-code:}")
            String personDatasetCode,
            @Value("${ai.business.subject.directory.department-dataset-code:}")
            String departmentDatasetCode) {
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
        this.projectDatasetCode = normalizeDatasetCode(projectDatasetCode);
        this.personDatasetCode = normalizeDatasetCode(personDatasetCode);
        this.departmentDatasetCode = normalizeDatasetCode(departmentDatasetCode);
    }

    /**
     * 使用当前登录人的身份执行一个目录数据集，任何配置或协议异常都失败关闭。
     */
    @Override
    public SubjectDirectoryPage search(SubjectDirectoryQuery query) {
        String datasetCode = null;
        Map<String, Object> canonicalInput;
        DirectoryDatasetContractValidator.DirectoryDatasetContract contract;
        try {
            validateQuery(query);
            datasetCode = datasetCode(query.subjectType());
            if (!StringUtils.hasText(datasetCode)) {
                auditFailure(query, null, "CONFIG_OR_INPUT");
                return SubjectDirectoryPage.denied();
            }
            canonicalInput = canonicalInput(query);
            contract = contractValidator.validate(datasetCode, canonicalInput);
        } catch (RuntimeException ignored) {
            auditFailure(query, datasetCode, "CONFIG_OR_INPUT");
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
            auditFailure(query, datasetCode, "EXECUTION");
            return SubjectDirectoryPage.denied();
        }

        try {
            return resultParser.parse(query, contract, canonicalInput, result);
        } catch (RuntimeException ignored) {
            auditFailure(query, datasetCode, "RESULT_PROTOCOL");
            return SubjectDirectoryPage.denied();
        }
    }

    private Map<String, Object> canonicalInput(SubjectDirectoryQuery query) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("searchMode", query.searchMode().name());
        putIfPresent(input, "selectedSubjectId", query.selectedSubjectId());
        putIfPresent(input, "projectCode", query.projectCode());
        putIfPresent(input, "searchName", query.searchName());
        putIfPresent(input, "projectManager", query.projectManager());
        putIfPresent(input, "projectYear", query.projectYear());
        putIfPresent(input, "employeeNo", query.employeeNo());
        input.put("pageNumber", query.pageNumber());
        input.put("pageSize", query.pageSize());
        return Map.copyOf(input);
    }

    private void validateQuery(SubjectDirectoryQuery query) {
        if (query == null
                || !StringUtils.hasText(query.agentRunId())
                || !StringUtils.hasText(query.userId())
                || !StringUtils.hasText(query.sessionId())
                || !StringUtils.hasText(query.authorization())
                || query.subjectType() == null
                || query.searchMode() == null
                || query.pageNumber() < 1
                || query.pageSize() < 1
                || query.pageSize() > 200) {
            throw new IllegalArgumentException("主体目录请求不完整");
        }
        SubjectRequestLimits.requireText(query.agentRunId(), "agentRunId", 128);
        SubjectRequestLimits.requireText(query.userId(), "userId", 256);
        SubjectRequestLimits.requireText(query.sessionId(), "sessionId", 256);
        SubjectRequestLimits.requireText(query.authorization(), "authorization", 32768);
        SubjectRequestLimits.optionalText(query.selectedSubjectId(), "selectedSubjectId", 256);
        SubjectRequestLimits.optionalText(query.projectCode(), "projectCode", 128);
        SubjectRequestLimits.optionalText(query.searchName(), "searchName", 256);
        SubjectRequestLimits.optionalText(query.projectManager(), "projectManager", 256);
        SubjectRequestLimits.optionalText(query.employeeNo(), "employeeNo", 128);
    }

    private String datasetCode(BusinessSubjectType type) {
        return switch (type) {
            case PROJECT -> projectDatasetCode;
            case PERSON -> personDatasetCode;
            case DEPARTMENT -> departmentDatasetCode;
        };
    }

    private String normalizeDatasetCode(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        return DATASET_CODE.matcher(normalized).matches() ? normalized : null;
    }

    private void auditFailure(
            SubjectDirectoryQuery query,
            String datasetCode,
            String category) {
        String traceHash = query == null || !StringUtils.hasText(query.agentRunId())
                ? "missing"
                : ContentHashUtils.sha256(query.agentRunId()).substring(0, 12);
        String candidateType = query == null || query.subjectType() == null
                ? "UNKNOWN"
                : query.subjectType().name();
        LOGGER.warn(
                "主体目录查询失败关闭 traceHash={} category={} datasetCode={} candidateType={}",
                traceHash,
                category,
                StringUtils.hasText(datasetCode) ? datasetCode : "unconfigured",
                candidateType
        );
    }

    private void putIfPresent(Map<String, Object> input, String key, Object value) {
        if (value != null) {
            input.put(key, value);
        }
    }
}
