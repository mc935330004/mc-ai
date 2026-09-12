package org.example.ai.agent.business;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.example.ai.agent.business.dataset.BusinessFactSanitizer;
import org.example.ai.agent.business.dataset.DatasetAccessWorkflowExecutor;
import org.example.ai.agent.business.dataset.ReportDatasetValidator;
import org.example.ai.agent.business.dataset.entity.ReportDataset;
import org.example.ai.agent.business.dataset.mapper.ReportDatasetMapper;
import org.example.ai.agent.business.dataset.model.FieldPolicy;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.panorama.ProjectSubjectAuthorizationService;
import org.example.ai.agent.business.panorama.model.ProjectPanoramaCommand;
import org.example.ai.agent.business.report.ReportDownloadService;
import org.example.ai.agent.business.report.LogicalReportAssembler;
import org.example.ai.agent.business.report.XlsxReportRenderer;
import org.example.ai.agent.business.report.model.LogicalReportDocument.SectionState;
import org.example.ai.agent.business.report.model.LogicalReportDocument.SourceDisclosure;
import org.example.ai.agent.business.report.model.LogicalReportDocument.StatusSection;
import org.example.ai.agent.business.report.entity.CompositeReportSection;
import org.example.ai.agent.business.report.entity.CompositeReportTask;
import org.example.ai.agent.business.report.mapper.CompositeReportSectionMapper;
import org.example.ai.agent.business.report.mapper.CompositeReportTaskMapper;
import org.example.ai.agent.business.snapshot.BusinessSnapshotAccessService;
import org.example.ai.agent.business.snapshot.BusinessSnapshotReferenceValidationService;
import org.example.ai.agent.business.snapshot.entity.BusinessSnapshot;
import org.example.ai.agent.business.snapshot.mapper.BusinessSnapshotMapper;
import org.example.ai.agent.business.subject.AuthorizedPersonDirectoryService;
import org.example.ai.agent.business.subject.DepartmentDirectoryService;
import org.example.ai.agent.business.subject.ProjectDirectoryService;
import org.example.ai.agent.business.subject.SubjectDirectoryQuery;
import org.example.ai.agent.business.subject.SubjectResolutionService;
import org.example.ai.agent.business.subject.SubjectSelectionTokenService;
import org.example.ai.agent.business.subject.model.AuthorizedSubjectCandidate;
import org.example.ai.agent.business.subject.model.SubjectDirectoryPage;
import org.example.ai.agent.business.subject.model.SubjectResolutionRequest;
import org.example.ai.agent.business.subject.model.SubjectResolutionResult;
import org.example.ai.agent.business.subject.model.SubjectResolutionState;
import org.example.ai.agent.chat.support.ContentHashUtils;
import org.example.ai.agent.common.config.StorageProperties;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.file.SafeArtifactStorageService;
import org.example.ai.agent.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.example.ai.agent.business.BusinessAssistantAcceptanceFixture.EMPLOYEE_NO;
import static org.example.ai.agent.business.BusinessAssistantAcceptanceFixture.PROJECT_CODE;
import static org.example.ai.agent.business.BusinessAssistantAcceptanceFixture.SESSION_ID;
import static org.example.ai.agent.business.BusinessAssistantAcceptanceFixture.USER_ID;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 业务助手安全验收矩阵。
 *
 * <p>数据库、日志和模型通道的原始数据隔离由
 * {@code WorkflowPersistenceBoundaryTest} 覆盖，本类补齐跨业务权限矩阵和文件通道。</p>
 */
class BusinessAssistantSecurityIntegrationTest {

    private static final String AUTHORIZATION = "Bearer security-test";
    private static final String RAW_SENTINEL = "RAW_SENTINEL_MUST_NEVER_ESCAPE";
    private static final String RUN_ID = "run-security-matrix";
    private static final String OLD_POLICY = "a".repeat(64);
    private static final String NEW_POLICY = "b".repeat(64);
    private static final String CONFIG_CHECKSUM = "c".repeat(64);

    private final Fixture fixture = new Fixture();

    @TempDir
    Path temporaryDirectory;

    @Test
    void employeeCannotResolveUnauthorizedPerson() {
        Map<String, Object> trustedContext = Map.of("role", "EMPLOYEE", "tenant", "tenant-1");
        when(fixture.personDirectory.search(any())).thenReturn(SubjectDirectoryPage.denied());

        SubjectResolutionResult result = fixture.resolutionService.resolve(
                personRequest(trustedContext, null, "E99999")
        );

        assertThat(result.state()).isEqualTo(SubjectResolutionState.DENIED);
        assertThat(result.safeMessage()).isEqualTo("无法定位或无权访问该主体");
        assertThat(result.candidates()).isEmpty();
        ArgumentCaptor<SubjectDirectoryQuery> query =
                ArgumentCaptor.forClass(SubjectDirectoryQuery.class);
        verify(fixture.personDirectory).search(query.capture());
        assertThat(query.getValue().searchMode().name()).isEqualTo("EMPLOYEE_NO");
        assertThat(query.getValue().employeeNo()).isEqualTo("E99999");
        assertThat(query.getValue().authorization()).isEqualTo(AUTHORIZATION);
        assertThat(query.getValue().secureContext()).containsExactlyEntriesOf(trustedContext);
        verify(fixture.projectDirectory, never()).search(any());
        verify(fixture.departmentDirectory, never()).search(any());
    }

    @Test
    void adminReceivesOnlyAuthorizedMaskedPersonCandidates() {
        Map<String, Object> trustedContext = Map.of("role", "ADMIN", "tenant", "tenant-1");
        when(fixture.personDirectory.search(any())).thenReturn(page(List.of(
                person(EMPLOYEE_NO, "张三", "E1***86"),
                person("E20087", "张经理", "E2***87")
        )));

        SubjectResolutionResult result = fixture.resolutionService.resolve(
                personRequest(trustedContext, "张", null)
        );

        assertThat(result.state()).isEqualTo(SubjectResolutionState.CANDIDATES);
        assertThat(result.candidates())
                .extracting(candidate -> candidate.displayName())
                .containsExactly("张三", "张经理");
        assertThat(result.candidates())
                .extracting(candidate -> candidate.maskedEmployeeNo())
                .containsExactly("E1***86", "E2***87")
                .allSatisfy(masked -> assertThat(masked).contains("***"));
        ArgumentCaptor<SubjectDirectoryQuery> query =
                ArgumentCaptor.forClass(SubjectDirectoryQuery.class);
        verify(fixture.personDirectory).search(query.capture());
        assertThat(query.getValue().searchMode().name()).isEqualTo("PERSON_NAME");
        assertThat(query.getValue().authorization()).isEqualTo(AUTHORIZATION);
        assertThat(query.getValue().secureContext()).containsExactlyEntriesOf(trustedContext);
    }

    @Test
    void revokedProjectIsRejectedAfterTokenResolution() {
        String token = fixture.tokenService.issue(
                "project-internal-1", USER_ID, SESSION_ID, BusinessSubjectType.PROJECT
        );
        when(fixture.projectDirectory.search(any())).thenReturn(SubjectDirectoryPage.denied());

        IllegalArgumentException denied = catchThrowableOfType(
                () -> fixture.projectAuthorization.authorize(new ProjectPanoramaCommand(
                        RUN_ID, USER_ID, SESSION_ID, AUTHORIZATION, Map.of(), token, Map.of()
                )),
                IllegalArgumentException.class
        );

        assertThat(denied.getMessage())
                .isEqualTo("项目当前无权访问或无法唯一定位")
                .doesNotContain("project-internal-1", PROJECT_CODE);
        verify(fixture.projectDirectory).search(any());
    }

    @Test
    void datasetGrantIsIndependentAndDenialDoesNotRevealExistence() {
        when(fixture.datasetMapper.selectOne(any()))
                .thenReturn(dataset(1L, "PERSON_ATTENDANCE"))
                .thenReturn(dataset(2L, "PERSON_REIMBURSEMENT"))
                .thenReturn(null);
        when(fixture.accessExecutor.authorize(any(), any()))
                .thenReturn(DatasetAccessWorkflowExecutor.AccessDecision.ALLOWED)
                .thenReturn(DatasetAccessWorkflowExecutor.AccessDecision.DENIED);

        Optional<BusinessSnapshotAccessService.AccessGrant> allowed =
                fixture.datasetAccess.reauthorize(accessCommand("PERSON_ATTENDANCE"));
        Optional<BusinessSnapshotAccessService.AccessGrant> denied =
                fixture.datasetAccess.reauthorize(accessCommand("PERSON_REIMBURSEMENT"));
        Optional<BusinessSnapshotAccessService.AccessGrant> missing =
                fixture.datasetAccess.reauthorize(accessCommand("PERSON_UNKNOWN"));

        assertThat(allowed).isPresent();
        assertThat(allowed.orElseThrow().datasetId()).isEqualTo(1L);
        assertThat(denied).isEmpty();
        assertThat(missing).isEqualTo(denied);
        verify(fixture.accessExecutor, times(2)).authorize(any(), any());
    }

    @Test
    void tightenedFieldPolicyInvalidatesSnapshotAndDeniesWholeReport() throws Exception {
        BusinessSnapshotMapper snapshotMapper = mock(BusinessSnapshotMapper.class);
        BusinessSnapshotAccessService accessService = mock(BusinessSnapshotAccessService.class);
        BusinessSnapshotReferenceValidationService validationService =
                new BusinessSnapshotReferenceValidationService(snapshotMapper, accessService);
        when(snapshotMapper.selectById("snapshot-1")).thenReturn(snapshot());
        when(accessService.reauthorize(any()))
                .thenReturn(Optional.of(grant(OLD_POLICY)))
                .thenReturn(Optional.of(grant(NEW_POLICY)));

        BusinessSnapshotReferenceValidationService.ValidationCommand command =
                snapshotValidationCommand();
        assertThat(validationService.validate(command)).isTrue();
        assertThat(validationService.validate(command)).isFalse();

        CompositeReportTaskMapper taskMapper = mock(CompositeReportTaskMapper.class);
        CompositeReportSectionMapper sectionMapper = mock(CompositeReportSectionMapper.class);
        CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
        SafeArtifactStorageService storage = mock(SafeArtifactStorageService.class);
        ReportDownloadService downloadService = new ReportDownloadService(
                taskMapper, sectionMapper, accessService, currentUser, storage
        );
        when(taskMapper.selectByTaskId("missing-task")).thenReturn(null);
        when(taskMapper.selectByTaskId("task-1")).thenReturn(successfulTask());
        when(currentUser.getRequiredUserId()).thenReturn(USER_ID);
        when(currentUser.getRequiredAuthorization()).thenReturn(AUTHORIZATION);
        when(sectionMapper.selectByTaskId("task-1"))
                .thenReturn(List.of(section("PERSON_ATTENDANCE", OLD_POLICY)));
        when(accessService.reauthorize(any())).thenReturn(Optional.of(grant(NEW_POLICY)));

        BusinessException missing = catchThrowableOfType(
                () -> downloadService.download("missing-task"), BusinessException.class
        );
        BusinessException revoked = catchThrowableOfType(
                () -> downloadService.download("task-1"), BusinessException.class
        );

        assertThat(revoked.getCode()).isEqualTo(404);
        assertThat(revoked.getMessage()).isEqualTo("报告任务不存在或无权访问");
        assertThat(revoked.getCode()).isEqualTo(missing.getCode());
        assertThat(revoked.getMessage()).isEqualTo(missing.getMessage());
        verify(storage, never()).readVerified(any(), anyLong(), any());
    }

    @Test
    void rawSentinelCannotEnterRenderedReportFile() throws Exception {
        FieldPolicy amountPolicy = new FieldPolicy(
                "approvedAmount", "DECIMAL", true, true, true, true, "NONE", "SUMMARY"
        );
        FieldPolicy secretPolicy = new FieldPolicy(
                "secretToken", "STRING", false, false, false, false, "NONE", "SUMMARY"
        );
        LogicalReportAssembler assembler = new LogicalReportAssembler(
                new BusinessFactSanitizer(new ReportDatasetValidator())
        );
        byte[] rendered = new XlsxReportRenderer().render(assembler.assemble(
                new LogicalReportAssembler.ReportInput(
                        "安全验收报告", "security-report", OffsetDateTime.now(ZoneOffset.UTC),
                        List.of(new SourceDisclosure("PM业务系统", "安全快照")),
                        List.of("当前员工"),
                        Map.of("approvedAmount", 12_580, "secretToken", RAW_SENTINEL),
                        List.of(amountPolicy, secretPolicy),
                        List.of(
                                new LogicalReportAssembler.MetricDefinition(
                                        "approvedAmount", "审批金额", "元"
                                ),
                                new LogicalReportAssembler.MetricDefinition(
                                        "secretToken", "内部令牌", null
                                )
                        ),
                        List.of(),
                        List.of(new StatusSection("费用审批", SectionState.SUCCESS)),
                        true
                )
        ));
        StorageProperties properties = new StorageProperties();
        properties.setReportDir(temporaryDirectory.resolve("security-files"));
        SafeArtifactStorageService storage = new SafeArtifactStorageService(properties);

        SafeArtifactStorageService.StoredArtifact stored = storage.store(
                "reports/security-matrix/report.xlsx", rendered
        );
        byte[] persisted = storage.readVerified(
                stored.relativePath(), stored.fileSize(), stored.checksum()
        );

        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(persisted))) {
            assertThat(workbook.getSheet("summary")).isNotNull();
            assertThat(workbook.getSheet("detail")).isNotNull();
        }
        assertThat(unpackedText(persisted))
                .contains("审批金额", "12580")
                .doesNotContain("内部令牌", "secretToken", RAW_SENTINEL);
    }

    private SubjectResolutionRequest personRequest(
            Map<String, Object> context,
            String searchName,
            String employeeNo) {
        return new SubjectResolutionRequest(
                RUN_ID, USER_ID, SESSION_ID, AUTHORIZATION, context,
                BusinessSubjectType.PERSON, null, null, searchName, null,
                null, false, employeeNo, 1, 20
        );
    }

    private String unpackedText(byte[] report) throws Exception {
        StringBuilder content = new StringBuilder();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(report))) {
            while (zip.getNextEntry() != null) {
                content.append(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return content.toString();
    }

    private AuthorizedSubjectCandidate person(String id, String name, String maskedEmployeeNo) {
        return new AuthorizedSubjectCandidate(
                BusinessSubjectType.PERSON, id, name, maskedEmployeeNo,
                "研发中心", null, null
        );
    }

    private SubjectDirectoryPage page(List<AuthorizedSubjectCandidate> candidates) {
        return new SubjectDirectoryPage(true, candidates, 1, 20, candidates.size(), false);
    }

    private ReportDataset dataset(Long id, String code) {
        ReportDataset dataset = new ReportDataset();
        dataset.setId(id);
        dataset.setDatasetCode(code);
        dataset.setEnabled(true);
        dataset.setAccessWorkflowCode("access." + code.toLowerCase());
        dataset.setSubjectTypesJson("[\"PERSON\"]");
        dataset.setConfigChecksum(CONFIG_CHECKSUM);
        dataset.setFieldPolicyChecksum(OLD_POLICY);
        return dataset;
    }

    private BusinessSnapshotAccessService.AccessCommand accessCommand(String datasetCode) {
        return new BusinessSnapshotAccessService.AccessCommand(
                RUN_ID, USER_ID, SESSION_ID, AUTHORIZATION,
                Map.of("role", "EMPLOYEE"), datasetCode,
                BusinessSubjectType.PERSON, EMPLOYEE_NO, Map.of()
        );
    }

    private BusinessSnapshot snapshot() {
        BusinessSnapshot snapshot = new BusinessSnapshot();
        snapshot.setSnapshotId("snapshot-1");
        snapshot.setUserId(USER_ID);
        snapshot.setSessionId(SESSION_ID);
        snapshot.setSubjectType(BusinessSubjectType.PERSON.name());
        snapshot.setSubjectId(EMPLOYEE_NO);
        snapshot.setDatasetCode("PERSON_ATTENDANCE");
        snapshot.setQueryHash(ContentHashUtils.sha256(
                ReportDatasetValidator.canonicalSafeValue(Map.of())
        ));
        snapshot.setConfigChecksum(CONFIG_CHECKSUM);
        snapshot.setFieldPolicyChecksum(OLD_POLICY);
        snapshot.setStatus("COMPLETE");
        snapshot.setExpiresAt(LocalDateTime.now().plusHours(1));
        return snapshot;
    }

    private BusinessSnapshotReferenceValidationService.ValidationCommand snapshotValidationCommand() {
        return new BusinessSnapshotReferenceValidationService.ValidationCommand(
                RUN_ID, USER_ID, SESSION_ID, AUTHORIZATION,
                Map.of("role", "EMPLOYEE"), BusinessSubjectType.PERSON,
                EMPLOYEE_NO, "PERSON_ATTENDANCE", "snapshot-1",
                OLD_POLICY, Map.of()
        );
    }

    private BusinessSnapshotAccessService.AccessGrant grant(String policy) {
        return new BusinessSnapshotAccessService.AccessGrant(1L, CONFIG_CHECKSUM, policy);
    }

    private CompositeReportTask successfulTask() {
        CompositeReportTask task = new CompositeReportTask();
        task.setTaskId("task-1");
        task.setUserId(USER_ID);
        task.setSessionId(SESSION_ID);
        task.setSubjectType(BusinessSubjectType.PERSON.name());
        task.setSubjectId(EMPLOYEE_NO);
        task.setFormat("PDF");
        task.setStatus("SUCCESS");
        task.setStoragePath("reports/task-1/report.pdf");
        task.setFileName("个人报告.pdf");
        task.setMimeType("application/pdf");
        task.setFileSize(14L);
        task.setChecksum("d".repeat(64));
        task.setExpiresAt(LocalDateTime.now().plusHours(1));
        return task;
    }

    private CompositeReportSection section(String datasetCode, String policy) {
        CompositeReportSection section = new CompositeReportSection();
        section.setDatasetCode(datasetCode);
        section.setFieldPolicyChecksum(policy);
        section.setStatus("REUSED");
        section.setDisplayOrder(1);
        return section;
    }

    private static final class Fixture {
        private final ProjectDirectoryService projectDirectory = mock(ProjectDirectoryService.class);
        private final AuthorizedPersonDirectoryService personDirectory =
                mock(AuthorizedPersonDirectoryService.class);
        private final DepartmentDirectoryService departmentDirectory =
                mock(DepartmentDirectoryService.class);
        private final SubjectSelectionTokenService tokenService = new SubjectSelectionTokenService(10);
        private final SubjectResolutionService resolutionService = new SubjectResolutionService(
                projectDirectory, personDirectory, departmentDirectory, tokenService
        );
        private final ProjectSubjectAuthorizationService projectAuthorization =
                new ProjectSubjectAuthorizationService(tokenService, projectDirectory);
        private final ReportDatasetMapper datasetMapper = mock(ReportDatasetMapper.class);
        private final DatasetAccessWorkflowExecutor accessExecutor =
                mock(DatasetAccessWorkflowExecutor.class);
        private final BusinessSnapshotAccessService datasetAccess = new BusinessSnapshotAccessService(
                datasetMapper, accessExecutor, new ObjectMapper()
        );
    }
}
