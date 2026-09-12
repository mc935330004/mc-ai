package org.example.ai.agent.business.report;

import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.report.BusinessReportPlanService.LogicalReportPlan;
import org.example.ai.agent.business.report.BusinessReportPlanService.LogicalReportSection;
import org.example.ai.agent.business.report.BusinessReportPlanService.PlanCommand;
import org.example.ai.agent.business.report.BusinessReportPlanService.PlannedReport;
import org.example.ai.agent.business.report.BusinessReportPlanService.ResolvedSection;
import org.example.ai.agent.business.report.BusinessReportPlanService.SectionResolutionStatus;
import org.example.ai.agent.business.report.BusinessReportPlanService.TemplateDefinition;
import org.example.ai.agent.business.report.BusinessReportPlanService.TemplateSection;
import org.example.ai.agent.business.report.entity.CompositeReportSection;
import org.example.ai.agent.business.report.entity.CompositeReportTask;
import org.example.ai.agent.business.report.mapper.CompositeReportSectionMapper;
import org.example.ai.agent.business.report.mapper.CompositeReportTaskMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompositeReportTaskServiceTest {

    private static final String SHA = "a".repeat(64);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-08T02:00:00Z"), ZoneOffset.UTC
    );

    @Test
    void shouldSelectSubjectDefaultAndProjectSpecificTemplate() {
        AtomicReference<String> selectedProjectType = new AtomicReference<>();
        BusinessReportPlanService service = new BusinessReportPlanService(
                (subjectType, projectType) -> {
                    selectedProjectType.set(projectType);
                    String code = projectType == null ? "PERSON_DEFAULT" : "PROJECT_DELIVERY";
                    return template(code, "summary");
                },
                command -> reused(command.datasetCode(), "snapshot-1")
        );

        PlannedReport person = service.plan(command(
                BusinessSubjectType.PERSON, null, "DOCX", List.of(), List.of()
        ));
        PlannedReport project = service.plan(command(
                BusinessSubjectType.PROJECT, "DELIVERY", "PDF", List.of(), List.of()
        ));

        assertThat(person.plan().templateCode()).isEqualTo("PERSON_DEFAULT");
        assertThat(person.templateChecksum()).isEqualTo(SHA);
        assertThat(project.plan().templateCode()).isEqualTo("PROJECT_DELIVERY");
        assertThat(selectedProjectType).hasValue("DELIVERY");
    }

    @Test
    void explicitIncludeAndExcludeShouldOverrideTemplateWithoutChangingOrder() {
        BusinessReportPlanService service = new BusinessReportPlanService(
                (type, projectType) -> template("PROJECT_DEFAULT", "summary", "risk", "cost"),
                command -> reused(command.datasetCode(), "snapshot-" + command.datasetCode())
        );

        LogicalReportPlan plan = service.plan(command(
                BusinessSubjectType.PROJECT,
                "DELIVERY",
                "XLSX",
                List.of("milestone"),
                List.of("risk")
        )).plan();

        assertThat(plan.sections())
                .extracting(LogicalReportSection::datasetCode)
                .containsExactly("summary", "cost", "milestone");
    }

    @Test
    void shouldReuseSnapshotAndQueryMissingSectionThroughResolver() {
        BusinessReportPlanService service = new BusinessReportPlanService(
                (type, projectType) -> template("PERSON_DEFAULT", "profile", "attendance"),
                command -> "profile".equals(command.datasetCode())
                        ? reused("profile", "snapshot-profile")
                        : new ResolvedSection(
                        "attendance", "snapshot-attendance", SHA,
                        SectionResolutionStatus.QUERIED, true, null
                )
        );

        LogicalReportPlan plan = service.plan(command(
                BusinessSubjectType.PERSON, null, "DOCX", List.of(), List.of()
        )).plan();

        assertThat(plan.sections()).extracting(LogicalReportSection::status)
                .containsExactly("REUSED", "QUERIED");
        assertThat(plan.sections().get(0).snapshotId()).isEqualTo("snapshot-profile");
        assertThat(plan.sections().get(1).snapshotId()).isEqualTo("snapshot-attendance");
        assertThat(plan.dataComplete()).isTrue();
    }

    @Test
    void deniedSectionShouldUseSingleNonDisclosingMessage() {
        BusinessReportPlanService service = new BusinessReportPlanService(
                (type, projectType) -> template("DEPARTMENT_DEFAULT", "salary"),
                command -> new ResolvedSection(
                        "salary", null, SHA, SectionResolutionStatus.DENIED,
                        false, "存在83条工资记录，总额100万元"
                )
        );

        LogicalReportSection section = service.plan(command(
                BusinessSubjectType.DEPARTMENT, null, "PDF", List.of(), List.of()
        )).plan().sections().get(0);

        assertThat(section.status()).isEqualTo("DENIED");
        assertThat(section.safeMessage()).isEqualTo("因权限不足未纳入");
        assertThat(section.toString())
                .doesNotContain("83")
                .doesNotContain("100万")
                .doesNotContain("工资记录");
    }

    @Test
    void failureShouldRemainDistinctFromDenialAndDiscloseIncompleteData() {
        BusinessReportPlanService service = new BusinessReportPlanService(
                (type, projectType) -> template("PROJECT_DEFAULT", "summary", "cost"),
                command -> "summary".equals(command.datasetCode())
                        ? reused("summary", "snapshot-summary")
                        : new ResolvedSection(
                        "cost", null, SHA, SectionResolutionStatus.FAILED,
                        false, "SQL timeout: project=secret-project"
                )
        );

        LogicalReportPlan plan = service.plan(command(
                BusinessSubjectType.PROJECT, "DELIVERY", "PDF", List.of(), List.of()
        )).plan();

        assertThat(plan.dataComplete()).isFalse();
        assertThat(plan.sections().get(1).status()).isEqualTo("FAILED");
        assertThat(plan.sections().get(1).safeMessage()).isEqualTo("数据查询失败，章节未纳入");
        assertThat(plan.sections().get(1).safeMessage()).doesNotContain("secret-project");
    }

    @Test
    void shouldPreserveRequestedFormatExactlyAndRejectUnsupportedVariants() {
        BusinessReportPlanService service = new BusinessReportPlanService(
                (type, projectType) -> template("PERSON_DEFAULT", "profile"),
                command -> reused(command.datasetCode(), "snapshot-1")
        );

        LogicalReportPlan plan = service.plan(command(
                BusinessSubjectType.PERSON, null, "DOCX", List.of(), List.of()
        )).plan();

        assertThat(plan.format()).isEqualTo("DOCX");
        assertThatThrownBy(() -> service.plan(command(
                BusinessSubjectType.PERSON, null, "docx", List.of(), List.of()
        ))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.plan(command(
                BusinessSubjectType.PERSON, null, " PDF ", List.of(), List.of()
        ))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createShouldInsertTaskAndSectionsInOneTransactionalMethod() throws Exception {
        CompositeReportTaskMapper taskMapper = mock(CompositeReportTaskMapper.class);
        CompositeReportSectionMapper sectionMapper = mock(CompositeReportSectionMapper.class);
        when(taskMapper.selectByRequestKey(any())).thenReturn(null);
        when(taskMapper.insertTask(any())).thenReturn(1);
        when(sectionMapper.insertSection(any())).thenReturn(1);
        CompositeReportTaskService service = taskService(taskMapper, sectionMapper);

        CompositeReportTask created = service.create(createCommand());

        assertThat(created.getRequestKey()).hasSize(64);
        assertThat(created.getRequestFingerprint()).hasSize(64);
        assertThat(created.getStatus()).isEqualTo("PENDING");
        verify(taskMapper).insertTask(any());
        verify(sectionMapper, times(2)).insertSection(any());
        Method create = CompositeReportTaskService.class.getMethod(
                "create", CompositeReportTaskService.CreateCommand.class
        );
        assertThat(create.getAnnotation(Transactional.class)).isNotNull();
    }

    @Test
    void repeatedCanonicalRequestShouldReturnExistingTask() {
        CompositeReportTaskMapper taskMapper = mock(CompositeReportTaskMapper.class);
        CompositeReportSectionMapper sectionMapper = mock(CompositeReportSectionMapper.class);
        AtomicReference<CompositeReportTask> persisted = new AtomicReference<>();
        when(taskMapper.selectByRequestKey(any())).thenAnswer(invocation -> persisted.get());
        when(taskMapper.insertTask(any())).thenAnswer(invocation -> {
            persisted.set(invocation.getArgument(0));
            return 1;
        });
        when(sectionMapper.insertSection(any())).thenReturn(1);
        CompositeReportTaskService service = taskService(taskMapper, sectionMapper);

        CompositeReportTask first = service.create(createCommand());
        CompositeReportTask second = service.create(createCommand());

        assertThat(second.getTaskId()).isEqualTo(first.getTaskId());
        assertThat(second.getRequestKey()).isEqualTo(first.getRequestKey());
        verify(taskMapper).insertTask(any());
        verify(sectionMapper, times(2)).insertSection(any());
    }

    @Test
    void concurrentUniqueKeyConflictShouldReadExistingAndVerifyFingerprint() {
        CompositeReportTaskMapper taskMapper = mock(CompositeReportTaskMapper.class);
        CompositeReportSectionMapper sectionMapper = mock(CompositeReportSectionMapper.class);
        AtomicReference<CompositeReportTask> attempted = new AtomicReference<>();
        when(taskMapper.selectByRequestKey(any())).thenAnswer(invocation -> attempted.get());
        when(taskMapper.insertTask(any())).thenAnswer(invocation -> {
            attempted.set(invocation.getArgument(0));
            throw new DuplicateKeyException("duplicate request key");
        });
        CompositeReportTaskService service = taskService(taskMapper, sectionMapper);

        CompositeReportTask existing = service.create(createCommand());

        assertThat(existing).isSameAs(attempted.get());
        verify(sectionMapper, never()).insertSection(any());

        attempted.get().setRequestFingerprint("b".repeat(64));
        assertThatThrownBy(() -> service.create(createCommand()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("幂等键冲突");
    }

    @Test
    void requestHashShouldIgnoreAuthenticationAndMapIterationOrder() {
        CompositeReportTaskMapper taskMapper = mock(CompositeReportTaskMapper.class);
        CompositeReportSectionMapper sectionMapper = mock(CompositeReportSectionMapper.class);
        ArgumentCaptor<CompositeReportTask> captor = ArgumentCaptor.forClass(CompositeReportTask.class);
        when(taskMapper.selectByRequestKey(any())).thenReturn(null);
        when(taskMapper.insertTask(any())).thenReturn(1);
        when(sectionMapper.insertSection(any())).thenReturn(1);
        CompositeReportTaskService service = taskService(taskMapper, sectionMapper);

        service.create(createCommand("Bearer secret-a", Map.of("year", 2026, "quarter", 2)));
        service.create(createCommand("Bearer secret-b", Map.of("quarter", 2, "year", 2026)));

        verify(taskMapper, times(2)).insertTask(captor.capture());
        assertThat(captor.getAllValues()).extracting(CompositeReportTask::getRequestKey)
                .containsExactly(captor.getAllValues().get(0).getRequestKey(),
                        captor.getAllValues().get(0).getRequestKey());
        assertThat(captor.getAllValues().get(0).toString()).doesNotContain("secret-a");
    }

    @Test
    void requestKeyShouldBindTemplateAndEveryFieldPolicyChecksum() {
        CompositeReportTaskMapper taskMapper = mock(CompositeReportTaskMapper.class);
        CompositeReportSectionMapper sectionMapper = mock(CompositeReportSectionMapper.class);
        ArgumentCaptor<CompositeReportTask> captor = ArgumentCaptor.forClass(CompositeReportTask.class);
        when(taskMapper.selectByRequestKey(any())).thenReturn(null);
        when(taskMapper.insertTask(any())).thenReturn(1);
        when(sectionMapper.insertSection(any())).thenReturn(1);
        CompositeReportTaskService service = taskService(taskMapper, sectionMapper);

        service.create(createCommand("Bearer secret", Map.of("year", 2026), SHA, SHA));
        service.create(createCommand(
                "Bearer secret", Map.of("year", 2026), "b".repeat(64), SHA
        ));
        service.create(createCommand(
                "Bearer secret", Map.of("year", 2026), SHA, "c".repeat(64)
        ));

        verify(taskMapper, times(3)).insertTask(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(CompositeReportTask::getRequestKey)
                .doesNotHaveDuplicates();
    }

    @Test
    void personUnavailableNoticeShouldBePersistedAndMarkTaskIncomplete() {
        CompositeReportTaskMapper taskMapper = mock(CompositeReportTaskMapper.class);
        CompositeReportSectionMapper sectionMapper = mock(CompositeReportSectionMapper.class);
        ArgumentCaptor<CompositeReportSection> section =
                ArgumentCaptor.forClass(CompositeReportSection.class);
        when(taskMapper.selectByRequestKey(any())).thenReturn(null);
        when(taskMapper.insertTask(any())).thenReturn(1);
        when(sectionMapper.insertSection(any())).thenReturn(1);
        CompositeReportTaskService service = taskService(taskMapper, sectionMapper);

        CompositeReportTask created = service.create(personDisclosureCommand(
                "报销数据源尚未配置，本次未纳入统计"
        ));

        assertThat(created.getDataComplete()).isFalse();
        verify(sectionMapper).insertSection(section.capture());
        assertThat(section.getValue().getSafeMessage())
                .isEqualTo("报销数据源尚未配置，本次未纳入统计");
    }

    @Test
    void personUnavailableNoticeShouldRejectCompletePlan() {
        CompositeReportTaskMapper taskMapper = mock(CompositeReportTaskMapper.class);
        CompositeReportSectionMapper sectionMapper = mock(CompositeReportSectionMapper.class);
        CompositeReportTaskService service = taskService(taskMapper, sectionMapper);

        assertThatThrownBy(() -> service.create(personDisclosureCommand(
                "报销数据源尚未配置，本次未纳入统计", true
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dataComplete");
        verify(taskMapper, never()).insertTask(any());
    }

    @Test
    void projectSectionShouldRejectPersonUnavailableNotice() {
        CompositeReportTaskMapper taskMapper = mock(CompositeReportTaskMapper.class);
        CompositeReportSectionMapper sectionMapper = mock(CompositeReportSectionMapper.class);
        CompositeReportTaskService service = taskService(taskMapper, sectionMapper);
        CompositeReportTaskService.CreateCommand command = disclosureCommand(
                BusinessSubjectType.PROJECT,
                List.of(new LogicalReportSection(
                        "travel", "snapshot-1", SHA, "REUSED",
                        "出差数据源尚未配置，本次未纳入统计"
                )),
                false
        );

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("安全说明");
        verify(taskMapper, never()).insertTask(any());
    }

    @Test
    void personUnavailableNoticeShouldRejectNonFirstResolvedSection() {
        CompositeReportTaskMapper taskMapper = mock(CompositeReportTaskMapper.class);
        CompositeReportSectionMapper sectionMapper = mock(CompositeReportSectionMapper.class);
        CompositeReportTaskService service = taskService(taskMapper, sectionMapper);
        CompositeReportTaskService.CreateCommand command = disclosureCommand(
                BusinessSubjectType.PERSON,
                List.of(
                        new LogicalReportSection(
                                "travel", "snapshot-1", SHA, "REUSED", null
                        ),
                        new LogicalReportSection(
                                "reimbursement", "snapshot-2", SHA, "REUSED",
                                "报销数据源尚未配置，本次未纳入统计"
                        )
                ),
                false
        );

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("安全说明");
        verify(taskMapper, never()).insertTask(any());
    }

    @Test
    void requestKeyShouldDistinguishUnavailablePersonSemantics() {
        CompositeReportTaskMapper taskMapper = mock(CompositeReportTaskMapper.class);
        CompositeReportSectionMapper sectionMapper = mock(CompositeReportSectionMapper.class);
        ArgumentCaptor<CompositeReportTask> task =
                ArgumentCaptor.forClass(CompositeReportTask.class);
        when(taskMapper.selectByRequestKey(any())).thenReturn(null);
        when(taskMapper.insertTask(any())).thenReturn(1);
        when(sectionMapper.insertSection(any())).thenReturn(1);
        CompositeReportTaskService service = taskService(taskMapper, sectionMapper);

        service.create(personDisclosureCommand("出差数据源尚未配置，本次未纳入统计"));
        service.create(personDisclosureCommand("报销数据源尚未配置，本次未纳入统计"));

        verify(taskMapper, times(2)).insertTask(task.capture());
        assertThat(task.getAllValues()).extracting(CompositeReportTask::getRequestKey)
                .doesNotHaveDuplicates();
    }

    @Test
    void createShouldRejectUnsafeSectionMessageEvenWhenPlanIsConstructedDirectly() {
        CompositeReportTaskMapper taskMapper = mock(CompositeReportTaskMapper.class);
        CompositeReportSectionMapper sectionMapper = mock(CompositeReportSectionMapper.class);
        CompositeReportTaskService service = taskService(taskMapper, sectionMapper);
        LogicalReportPlan unsafe = new LogicalReportPlan(
                "PROJECT_DEFAULT", BusinessSubjectType.PROJECT, "project-1", "PDF",
                List.of(new LogicalReportSection(
                        "cost", null, SHA, "FAILED", "SQL timeout: token=secret"
                )),
                true
        );
        CompositeReportTaskService.CreateCommand command =
                new CompositeReportTaskService.CreateCommand(
                        "user-1", "session-1", null, new PlannedReport(unsafe, SHA),
                        Map.of("year", 2026),
                        LocalDateTime.of(2026, 9, 9, 10, 0), 3
                );

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("安全说明");
        verify(taskMapper, never()).insertTask(any());
    }

    @Test
    void createShouldRejectArbitraryMessageOnResolvedSection() {
        CompositeReportTaskMapper taskMapper = mock(CompositeReportTaskMapper.class);
        CompositeReportSectionMapper sectionMapper = mock(CompositeReportSectionMapper.class);
        CompositeReportTaskService service = taskService(taskMapper, sectionMapper);
        LogicalReportPlan unsafe = new LogicalReportPlan(
                "PERSON_STANDARD", BusinessSubjectType.PERSON, "employee-1", "PDF",
                List.of(new LogicalReportSection(
                        "travel", "snapshot-1", SHA, "REUSED", "raw timeout: token=secret"
                )),
                false
        );
        CompositeReportTaskService.CreateCommand command =
                new CompositeReportTaskService.CreateCommand(
                        "user-1", "session-1", null, new PlannedReport(unsafe, SHA),
                        Map.of("year", 2026),
                        LocalDateTime.of(2026, 9, 9, 10, 0), 3
                );

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("安全说明");
        verify(taskMapper, never()).insertTask(any());
    }

    private CompositeReportTaskService taskService(
            CompositeReportTaskMapper taskMapper,
            CompositeReportSectionMapper sectionMapper) {
        return new CompositeReportTaskService(taskMapper, sectionMapper, CLOCK);
    }

    private CompositeReportTaskService.CreateCommand createCommand() {
        return createCommand("Bearer secret", Map.of("year", 2026, "quarter", 2));
    }

    private CompositeReportTaskService.CreateCommand createCommand(
            String authorization,
            Map<String, Object> query) {
        return createCommand(authorization, query, SHA, SHA);
    }

    private CompositeReportTaskService.CreateCommand createCommand(
            String authorization,
            Map<String, Object> query,
            String templateChecksum,
            String fieldPolicyChecksum) {
        LogicalReportPlan plan = new LogicalReportPlan(
                "PROJECT_DEFAULT",
                BusinessSubjectType.PROJECT,
                "project-1",
                "PDF",
                List.of(
                        new LogicalReportSection(
                                "summary", "snapshot-1", fieldPolicyChecksum, "REUSED", null
                        ),
                        new LogicalReportSection(
                                "cost", null, fieldPolicyChecksum, "FAILED",
                                BusinessReportPlanService.FAILED_MESSAGE
                        )
                ),
                false
        );
        return new CompositeReportTaskService.CreateCommand(
                "user-1", "session-1", authorization,
                new PlannedReport(plan, templateChecksum), query,
                LocalDateTime.of(2026, 9, 9, 10, 0), 3
        );
    }

    private CompositeReportTaskService.CreateCommand personDisclosureCommand(String safeMessage) {
        return personDisclosureCommand(safeMessage, false);
    }

    private CompositeReportTaskService.CreateCommand personDisclosureCommand(
            String safeMessage,
            boolean dataComplete) {
        return disclosureCommand(
                BusinessSubjectType.PERSON,
                List.of(new LogicalReportSection(
                        "travel", "snapshot-1", SHA, "REUSED", safeMessage
                )),
                dataComplete
        );
    }

    private CompositeReportTaskService.CreateCommand disclosureCommand(
            BusinessSubjectType subjectType,
            List<LogicalReportSection> sections,
            boolean dataComplete) {
        LogicalReportPlan plan = new LogicalReportPlan(
                subjectType == BusinessSubjectType.PERSON ? "PERSON_STANDARD" : "PROJECT_DEFAULT",
                subjectType,
                subjectType == BusinessSubjectType.PERSON ? "employee-1" : "project-1",
                "PDF",
                sections,
                dataComplete
        );
        return new CompositeReportTaskService.CreateCommand(
                "user-1", "session-1", "Bearer secret",
                new PlannedReport(plan, SHA), Map.of("year", 2026),
                LocalDateTime.of(2026, 9, 9, 10, 0), 3
        );
    }

    private PlanCommand command(
            BusinessSubjectType type,
            String projectType,
            String format,
            List<String> includes,
            List<String> excludes) {
        return new PlanCommand(
                "run-1", "user-1", "session-1", "Bearer secret", Map.of("role", "PM"),
                type, "subject-1", projectType, format, Map.of("year", 2026),
                includes, excludes, false
        );
    }

    private TemplateDefinition template(String code, String... datasets) {
        List<TemplateSection> sections = java.util.stream.IntStream.range(0, datasets.length)
                .mapToObj(index -> new TemplateSection(datasets[index], index + 1))
                .toList();
        return new TemplateDefinition(code, SHA, sections);
    }

    private ResolvedSection reused(String datasetCode, String snapshotId) {
        return new ResolvedSection(
                datasetCode, snapshotId, SHA, SectionResolutionStatus.REUSED, true, null
        );
    }
}
