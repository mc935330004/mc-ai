package org.example.ai.agent.business.subject;

import org.example.ai.agent.business.dataset.DatasetExecutionProofVerifier;
import org.example.ai.agent.business.dataset.ReportDatasetValidator;
import org.example.ai.agent.business.dataset.model.DatasetExecutionResult;
import org.example.ai.agent.business.dataset.model.DatasetExecutionSource;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.model.DatasetExecutionStatus;
import org.example.ai.agent.business.subject.model.AuthorizedSubjectCandidate;
import org.example.ai.agent.business.subject.model.SubjectDirectoryPage;
import org.example.ai.agent.business.subject.model.SubjectSearchMode;
import org.example.ai.agent.chat.support.ContentHashUtils;
import org.example.ai.agent.common.model.ProjectRelationship;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 严格解析目录数据集的安全事实协议，任何额外字段、分页矛盾或来源错配都关闭结果。
 */
final class DirectoryResultParser {

    private static final String DISPLAY_CHANNEL = "display";
    private static final String CANDIDATES_FACT = "subjectCandidates";
    private static final String TOTAL_COUNT_FACT = "totalCount";
    private static final String HAS_NEXT_FACT = "hasNext";
    private static final Set<String> SAFE_FACT_CHANNELS = Set.of(
            "calculation", DISPLAY_CHANNEL, "export", "model"
    );
    private static final Set<String> DIRECTORY_FACTS = Set.of(
            CANDIDATES_FACT, TOTAL_COUNT_FACT, HAS_NEXT_FACT
    );
    private static final Pattern MASKED_EMPLOYEE_NO = Pattern.compile(
            "^[\\p{L}\\p{N}]{1,4}\\*{3,8}[\\p{L}\\p{N}]{1,4}$"
    );
    private static final int MAX_TEXT_BYTES = 512;

    private final DatasetExecutionProofVerifier proofVerifier;
    private static final Set<String> PROJECT_CANDIDATE_FIELDS = Set.of(
            "subjectRef",
            "displayName",
            "projectCode",
            "projectType",
            "projectRelationship",
            "projectStatus"
    );
    DirectoryResultParser(DatasetExecutionProofVerifier proofVerifier) {
        this.proofVerifier = Objects.requireNonNull(proofVerifier, "proofVerifier不能为空");
    }

    /**
     * 解析普通主体目录结果。
     */
    SubjectDirectoryPage parse(
            SubjectDirectoryQuery query,
            DirectoryDatasetContractValidator
                    .DirectoryDatasetContract contract,
            Map<String, Object> canonicalInput,
            DatasetExecutionResult result
    ) {
        return parse(
                query.userId(),
                query.sessionId(),
                query.subjectType(),
                query.pageNumber(),
                query.pageSize(),
                candidate ->
                        validateExactLocator(
                                query,
                                candidate
                        ),
                candidate ->
                        validateProjectCandidate(
                                query,
                                candidate
                        ),
                contract,
                canonicalInput,
                result
        );
    }

    /**
     * 解析部门成员目录结果。
     */
    SubjectDirectoryPage parse(
            DepartmentMemberDirectoryQuery query,
            DirectoryDatasetContractValidator
                    .DirectoryDatasetContract contract,
            Map<String, Object> canonicalInput,
            DatasetExecutionResult result
    ) {
        return parse(
                query.userId(),
                query.sessionId(),
                BusinessSubjectType.PERSON,
                query.pageNumber(),
                query.pageSize(),
                candidate -> {
                },
                candidate -> {
                },
                contract,
                canonicalInput,
                result
        );
    }

    /**
     * 解析并校验目录安全事实。
     */
    private SubjectDirectoryPage parse(
            String userId,
            String sessionId,
            BusinessSubjectType candidateType,
            int pageNumber,
            int pageSize,
            Consumer<AuthorizedSubjectCandidate>
                    exactLocatorValidator,
            Consumer<AuthorizedSubjectCandidate>
                    candidateValidator,
            DirectoryDatasetContractValidator
                    .DirectoryDatasetContract contract,
            Map<String, Object> canonicalInput,
            DatasetExecutionResult result
    ) {
        if (result == null
                || !proofVerifier.verify(result)
                || !sourceMatches(
                userId,
                sessionId,
                contract,
                canonicalInput,
                result.source()
        )) {
            return SubjectDirectoryPage.denied();
        }

        validateSafeFactChannels(result.safeFacts());

        if (result.status()
                == DatasetExecutionStatus.EMPTY) {
            requireAllChannelsEmpty(result.safeFacts());
            return SubjectDirectoryPage.empty(
                    pageNumber,
                    pageSize
            );
        }

        if (result.status()
                != DatasetExecutionStatus.SUCCESS
                || !result.dataComplete()) {
            return SubjectDirectoryPage.denied();
        }

        Map<?, ?> display = requireMap(
                result.safeFacts().get(DISPLAY_CHANNEL)
        );

        requireExactKeys(
                display,
                DIRECTORY_FACTS,
                "display"
        );

        List<?> rawCandidates = requireList(
                display.get(CANDIDATES_FACT)
        );

        if (rawCandidates.size() > pageSize) {
            throw new IllegalArgumentException(
                    "主体候选数量超过当前页上限"
            );
        }

        List<AuthorizedSubjectCandidate> candidates =
                parseCandidates(
                        candidateType,
                        exactLocatorValidator,
                        candidateValidator,
                        rawCandidates
                );

        Object rawTotalCount =
                display.get(TOTAL_COUNT_FACT);

        boolean totalKnown =
                rawTotalCount != null;

        long totalCount = totalKnown
                ? requireNonNegativeLong(rawTotalCount)
                : 0L;

        boolean hasNext = requireBoolean(
                display.get(HAS_NEXT_FACT)
        );

        validatePageMetadata(
                pageNumber,
                pageSize,
                candidates.size(),
                totalCount,
                totalKnown,
                hasNext
        );

        return new SubjectDirectoryPage(
                true,
                candidates,
                pageNumber,
                pageSize,
                totalCount,
                totalKnown,
                hasNext
        );
    }

    private boolean sourceMatches(
            String userId,
            String sessionId,
            DirectoryDatasetContractValidator.DirectoryDatasetContract contract,
            Map<String, Object> canonicalInput,
            DatasetExecutionSource source) {
        String expectedInputHash = ContentHashUtils.sha256(
                ReportDatasetValidator.canonicalSafeValue(canonicalInput)
        );
        return source != null
                && userId.equals(source.userId())
                && sessionId.equals(source.sessionId())
                && BusinessSubjectType.PERSON == source.subjectType()
                && userId.equals(source.subjectId())
                && contract.datasetCode().equals(source.datasetCode())
                && contract.configChecksum().equals(source.datasetConfigChecksum())
                && contract.fieldPolicyChecksum().equals(source.fieldPolicyChecksum())
                && expectedInputHash.equals(source.canonicalInputHash());
    }

    private List<AuthorizedSubjectCandidate> parseCandidates(
            BusinessSubjectType candidateType,
            Consumer<AuthorizedSubjectCandidate>
                    exactLocatorValidator,
            Consumer<AuthorizedSubjectCandidate>
                    candidateValidator,
            List<?> rawCandidates
    ) {
        List<AuthorizedSubjectCandidate> candidates = new ArrayList<>(rawCandidates.size());

        Set<String> subjectRefs = new HashSet<>();
        Set<String> projectCodes = new HashSet<>();

        for (Object rawCandidate : rawCandidates) {
            AuthorizedSubjectCandidate candidate =
                    parseCandidate(
                            candidateType,
                            requireMap(rawCandidate)
                    );

            if (!subjectRefs.add(
                    candidate.rawSubjectId()
            )) {
                throw new IllegalArgumentException(
                        "主体目录内部标识重复"
                );
            }

            if (candidate.projectCode() != null
                    && !projectCodes.add(
                    candidate.projectCode()
                            .toUpperCase(Locale.ROOT)
            )) {
                throw new IllegalArgumentException(
                        "主体目录项目编码重复"
                );
            }

            exactLocatorValidator.accept(candidate);
            candidateValidator.accept(candidate);
            candidates.add(candidate);
        }

        return List.copyOf(candidates);
    }

    /**
     * 按主体类型解析候选项。
     */
    private AuthorizedSubjectCandidate parseCandidate(
            BusinessSubjectType type,
            Map<?, ?> values
    ) {
        validateCandidateKeys(type, values);

        String subjectId = requireText(
                values.get("subjectRef"),
                "subjectRef"
        );

        String displayName = requireText(
                values.get("displayName"),
                "displayName"
        );

        return switch (type) {
            case PROJECT ->
                    new AuthorizedSubjectCandidate(
                            type,
                            subjectId,
                            displayName,
                            null,
                            null,
                            requireText(
                                    values.get("projectCode"),
                                    "projectCode"
                            ),
                            optionalText(
                                    values.get("projectType")
                            ),
                            optionalProjectRelationship(
                                    values.get(
                                            "projectRelationship"
                                    )
                            ),
                            optionalText(
                                    values.get("projectStatus")
                            )
                    );

            case PERSON ->
                    new AuthorizedSubjectCandidate(
                            type,
                            subjectId,
                            displayName,
                            requireMaskedEmployeeNo(
                                    values.get(
                                            "maskedEmployeeNo"
                                    ),
                                    subjectId
                            ),
                            optionalText(
                                    values.get("departmentPath")
                            ),
                            null,
                            null,
                            null,
                            null
                    );

            case DEPARTMENT ->
                    new AuthorizedSubjectCandidate(
                            type,
                            subjectId,
                            displayName,
                            null,
                            optionalText(
                                    values.get("departmentPath")
                            ),
                            null,
                            null,
                            null,
                            null
                    );
        };
    }

    /**
     * 严格限制不同主体类型可以返回的字段。
     */
    private void validateCandidateKeys(BusinessSubjectType type, Map<?, ?> values) {
        Set<String> allowed = switch (type) {
            case PROJECT -> PROJECT_CANDIDATE_FIELDS;
            case PERSON -> Set.of(
                    "subjectRef",
                    "displayName",
                    "maskedEmployeeNo",
                    "departmentPath"
            );

            case DEPARTMENT -> Set.of(
                    "subjectRef",
                    "displayName",
                    "departmentPath"
            );
        };

        Set<String> required = switch (type) {
            case PROJECT -> Set.of(
                    "subjectRef",
                    "displayName",
                    "projectCode"
            );

            case PERSON -> Set.of(
                    "subjectRef",
                    "displayName",
                    "maskedEmployeeNo"
            );

            case DEPARTMENT -> Set.of(
                    "subjectRef",
                    "displayName"
            );
        };
        Set<String> actual = stringKeys(values, "candidate");
        if (!allowed.containsAll(actual) || !actual.containsAll(required)) {
            throw new IllegalArgumentException(
                    "主体候选字段不符合协议"
            );
        }
    }

    private void validateExactLocator(
            SubjectDirectoryQuery query,
            AuthorizedSubjectCandidate candidate) {
        if (query.searchMode() == SubjectSearchMode.EMPLOYEE_NO
                && !candidate.rawSubjectId().equals(query.employeeNo())) {
            throw new IllegalArgumentException("员工工号精确定位结果不一致");
        }
        if (query.searchMode() == SubjectSearchMode.SELECTED_SUBJECT
                && !candidate.rawSubjectId().equals(query.selectedSubjectId())) {
            throw new IllegalArgumentException("已选主体复核结果不一致");
        }
        if (query.searchMode() == SubjectSearchMode.PROJECT_CODE
                && !candidate.projectCode().equalsIgnoreCase(query.projectCode())) {
            throw new IllegalArgumentException("项目编码精确定位结果不一致");
        }
    }

    private void validateSafeFactChannels(Map<String, Object> safeFacts) {
        requireExactKeys(safeFacts, SAFE_FACT_CHANNELS, "safeFacts");
        for (String channel : SAFE_FACT_CHANNELS) {
            requireMap(safeFacts.get(channel));
        }
        for (String channel : Set.of("calculation", "export", "model")) {
            if (!requireMap(safeFacts.get(channel)).isEmpty()) {
                throw new IllegalArgumentException("目录数据集非展示通道必须为空");
            }
        }
    }

    private void requireAllChannelsEmpty(Map<String, Object> safeFacts) {
        for (String channel : SAFE_FACT_CHANNELS) {
            if (!requireMap(safeFacts.get(channel)).isEmpty()) {
                throw new IllegalArgumentException("空目录结果不能携带事实");
            }
        }
    }

    /**
     * 校验目录分页信息。
     */
    private void validatePageMetadata(int pageNumber, int pageSize, int candidateCount,
                                      long totalCount, boolean totalKnown, boolean hasNext) {
        if (candidateCount > pageSize) {
            throw new IllegalArgumentException(
                    "目录返回候选数量超过 pageSize"
            );
        }
        // 来源未提供可靠总数时，只依赖 hasNext。
        if (!totalKnown) {
            if (candidateCount == 0 && hasNext) {
                throw new IllegalArgumentException(
                        "候选列表为空时 hasNext 不能为 true"
                );
            }
            return;
        }
        long offset = Math.multiplyExact((long) pageNumber - 1, pageSize);
        long remaining = Math.max(0, totalCount - Math.min(offset, totalCount));
        long expectedCount = Math.min(pageSize, remaining);
        if (candidateCount != expectedCount) {
            throw new IllegalArgumentException(
                    "主体目录当前页数量与总数不一致"
            );
        }
        boolean expectedHasNext = Math.addExact(offset, candidateCount) < totalCount;
        if (hasNext != expectedHasNext) {
            throw new IllegalArgumentException(
                    "主体目录翻页标志与总数不一致"
            );
        }
    }

    private void requireExactKeys(Map<?, ?> values, Set<String> expected, String label) {
        if (!stringKeys(values, label).equals(expected)) {
            throw new IllegalArgumentException(label + "字段不符合协议");
        }
    }

    private Set<String> stringKeys(Map<?, ?> values, String label) {
        Set<String> keys = new LinkedHashSet<>();
        for (Object key : values.keySet()) {
            if (!(key instanceof String text) || !keys.add(text)) {
                throw new IllegalArgumentException(label + "字段名不合法");
            }
        }
        return Set.copyOf(keys);
    }

    private String requireMaskedEmployeeNo(Object value, String rawSubjectId) {
        String masked = requireText(value, "maskedEmployeeNo");
        if (!MASKED_EMPLOYEE_NO.matcher(masked).matches() || masked.equals(rawSubjectId)) {
            throw new IllegalArgumentException("员工工号掩码不合法");
        }
        return masked;
    }

    private Map<?, ?> requireMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("主体目录事实不是对象");
        }
        return map;
    }

    private List<?> requireList(Object value) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("主体目录候选不是数组");
        }
        return list;
    }

    private long requireNonNegativeLong(Object value) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("主体目录总数不是数字");
        }
        long result = number.longValue();
        if (result < 0 || number.doubleValue() != result) {
            throw new IllegalArgumentException("主体目录总数不合法");
        }
        return result;
    }

    private boolean requireBoolean(Object value) {
        if (!(value instanceof Boolean bool)) {
            throw new IllegalArgumentException("主体目录翻页标志不合法");
        }
        return bool;
    }

    private String requireText(Object value, String field) {
        String text = optionalText(value);
        if (text == null) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return text;
    }

    private String optionalText(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || !StringUtils.hasText(text)) {
            throw new IllegalArgumentException("主体目录文本字段不合法");
        }
        String normalized = text.trim();
        if (normalized.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("主体目录文本字段超过容量上限");
        }
        return normalized;
    }
    /**
     * 解析项目与当前用户的关系。
     */
    private ProjectRelationship optionalProjectRelationship(Object value) {
        if (value == null) {
            return null;
        }
        String relationship = requireText(value, "projectRelationship");
        try {
            return ProjectRelationship.valueOf(relationship.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "projectRelationship 不是受支持的项目关系",
                    exception
            );
        }
    }
    /**
     * 校验项目列表候选与请求范围一致。
     */
    private void validateProjectCandidate(
            SubjectDirectoryQuery query,
            AuthorizedSubjectCandidate candidate) {
        if (!isProjectListMode(query.searchMode())) {
            return;
        }
        if (candidate.projectRelationship() == null) {
            throw new IllegalArgumentException(
                    "项目列表候选缺少 projectRelationship"
            );
        }
        if (!StringUtils.hasText(candidate.projectStatus())) {
            throw new IllegalArgumentException(
                    "项目列表候选缺少 projectStatus"
            );
        }
        if (query.searchMode()
                == SubjectSearchMode.MY_PROJECTS
                && candidate.projectRelationship()
                != ProjectRelationship.RESPONSIBLE
                && candidate.projectRelationship()
                != ProjectRelationship.PARTICIPATING) {
            throw new IllegalArgumentException(
                    "MY_PROJECTS 只允许负责或参与的项目"
            );
        }
    }

    /**
     * 判断当前查询是否为项目列表查询。
     */
    private boolean isProjectListMode(SubjectSearchMode searchMode) {
        return searchMode == SubjectSearchMode.MY_PROJECTS
                || searchMode == SubjectSearchMode.VIEWABLE_PROJECTS;
    }
}
