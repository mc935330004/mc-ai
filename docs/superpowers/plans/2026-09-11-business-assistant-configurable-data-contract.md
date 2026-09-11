# Business Assistant Configurable Data Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不依赖正式出差、考勤、请假和报销数据的前提下，完成“人员 + 项目 + 项目期间”的可配置查询、确定性统计、友好缺失提示和有效报告导出链路。

**Architecture:** 保留 `BusinessAssistantServiceImpl` 作为薄编排入口，新增一个项目期间上下文服务和一个人员数据集计划服务；业务接口仍由已注册的 READ 工作流执行，外部字段通过现有字段字典映射为稳定事实。项目关联由纯 Java 规则分类，金额和考勤由确定性服务计算，报告只读取安全快照及安全查询元数据，不新增表字段。

**Tech Stack:** Java 17、Spring Boot 4、Spring AI、MyBatis Plus、MySQL、Flyway、JUnit 5、AssertJ、Mockito、Maven

---

## 实施边界

- 只在后端隔离工作树 `D:\codex-home\.config\superpowers\worktrees\mc-ai\business-assistant` 实施。
- 不修改主工作区 `D:\IdeaProjects\mc-ai`。
- 不修改 `pom.xml`，不引入新依赖。
- 不新增或修改 Flyway 迁移。
- 本计划没有复杂数据库查询，因此不新增 Mapper SQL；后续如实际出现复杂 SQL，必须写入对应 `Mapper.xml`。
- 不实现部门报告导出。
- 不伪造外部业务数据；测试使用内存桩和安全事实夹具。
- 新增 Java 类必须添加中文职责注释，并显式执行 `git add`。
- 被新实现完全替代且无调用方的旧逻辑直接删除，不保留兼容分支。

## 文件结构

### 新增文件

- `ai-agent/src/main/java/org/example/ai/agent/business/person/PersonDatasetPlanService.java`：根据用户语义和当前配置生成可执行计划及未配置模块说明。
- `ai-agent/src/main/java/org/example/ai/agent/business/person/ProjectPeriodContextService.java`：独立复核人员与项目权限，执行 `PROJECT_BASE`、`PROJECT_MEMBER` 数据集并返回项目期间上下文。
- `ai-agent/src/main/java/org/example/ai/agent/business/report/BusinessReportFileHandler.java`：把安全快照组装为逻辑报告，调用已有 XLSX、DOCX、PDF 渲染器并安全落盘。
- `ai-agent/src/test/java/org/example/ai/agent/business/person/PersonDatasetPlanServiceTest.java`
- `ai-agent/src/test/java/org/example/ai/agent/business/person/ProjectPeriodContextServiceTest.java`
- `ai-agent/src/test/java/org/example/ai/agent/business/report/BusinessReportFileHandlerTest.java`

### 修改文件

- `ai-agent/src/main/java/org/example/ai/agent/business/intent/BusinessQueryIntent.java`
- `ai-agent/src/main/java/org/example/ai/agent/business/intent/BusinessQueryIntentResolver.java`
- `ai-agent/src/main/java/org/example/ai/agent/business/intent/BusinessIntentValidator.java`
- `ai-agent/src/main/java/org/example/ai/agent/business/model/AssociationType.java`
- `ai-agent/src/main/java/org/example/ai/agent/business/person/ProjectRecordAssociationService.java`
- `ai-agent/src/main/java/org/example/ai/agent/business/person/PersonBusinessQueryService.java`
- `ai-agent/src/main/java/org/example/ai/agent/business/person/PersonBusinessFactAggregator.java`
- `ai-agent/src/main/java/org/example/ai/agent/business/snapshot/BusinessSnapshotDerivationService.java`
- `ai-agent/src/main/java/org/example/ai/agent/business/impl/BusinessAssistantServiceImpl.java`
- `ai-agent/src/main/java/org/example/ai/agent/business/report/BusinessAssistantReportService.java`
- `ai-agent/src/main/java/org/example/ai/agent/business/report/BusinessReportPlanService.java`
- `ai-agent/src/main/java/org/example/ai/agent/business/report/CompositeReportTaskService.java`
- `ai-agent/src/main/java/org/example/ai/agent/chat/memory/model/BusinessConversationState.java`
- `ai-agent/src/main/resources/application.yml`
- `ai-agent/src/main/resources/application-dev.yml`
- `ai-agent/src/main/resources/application-prod.yml`
- 对应的现有单元测试、集成测试和验收测试。
- `docs/runbooks/business-assistant.md`
- `docs/prompts/business-assistant-system-prompt.md`
- `CONTEXT.md`

## 开源参考原则

- 参考 [spring-projects/spring-ai-examples](https://github.com/spring-projects/spring-ai-examples) 的 agentic-patterns 与 integration-testing 组织方式：模型负责语义，确定性代码负责执行和验证。
- 参考 [spring-projects/spring-ai](https://github.com/spring-projects/spring-ai) 的可组合边界思想，但不新增 Advisor 或 Agent 框架。
- 参考 [spring-projects/spring-modulith](https://github.com/spring-projects/spring-modulith) 的模块内聚原则：新代码继续留在 `ai-agent` 的 `business.person`、`business.report` 包内，不把业务编排倒灌到 `ai-common`。
- 只借鉴职责划分和测试方法，不复制项目代码，不增加 Spring Modulith 依赖。

### Task 1: 增加“按项目期间”意图契约

**Files:**
- Modify: `ai-agent/src/main/java/org/example/ai/agent/business/intent/BusinessQueryIntent.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/business/intent/BusinessQueryIntentResolver.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/business/intent/BusinessIntentValidator.java`
- Modify: `ai-agent/src/test/java/org/example/ai/agent/business/intent/BusinessIntentValidatorTest.java`
- Modify: all tests constructing `BusinessQueryIntent`

- [ ] **Step 1: 写失败测试**

在 `BusinessIntentValidatorTest` 增加：

```java
@Test
void projectPeriodFlagDoesNotInventDates() {
    BusinessQueryIntent intent = validator.validate(new BusinessQueryIntent(
            BusinessSubjectType.PERSON, "XXXT2674040", "张三", null,
            null, null, null, List.of("TRAVEL"), false, null, false, true
    ));

    assertThat(intent.projectPeriodRequested()).isTrue();
    assertThat(intent.periodStart()).isNull();
    assertThat(intent.periodEnd()).isNull();
}

@Test
void explicitPeriodMustProvideBothDates() {
    BusinessQueryIntent intent = new BusinessQueryIntent(
            BusinessSubjectType.PERSON, null, "张三", null,
            null, LocalDate.of(2026, 1, 1), null, List.of("TRAVEL"),
            false, null, false, false
    );

    assertThatThrownBy(() -> validator.validate(intent))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("时间范围");
}
```

同时扩展 resolver 测试，断言“查询张三在 XXXT2674040 项目期间的出差”解析为 `projectPeriodRequested=true`，且两个日期均为 `null`。

- [ ] **Step 2: 运行测试并确认失败**

```powershell
mvn -pl ai-agent -am -Dtest=BusinessIntentValidatorTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL，原因是 `BusinessQueryIntent` 尚无 `projectPeriodRequested` 字段或校验尚未实现。

- [ ] **Step 3: 实现最小意图变更**

在 record 最后加入字段，避免模型漏字段时影响已有 Jackson 默认行为：

```java
public record BusinessQueryIntent(
        BusinessSubjectType subjectType,
        String projectCode,
        String personName,
        String employeeNo,
        Integer projectYear,
        LocalDate periodStart,
        LocalDate periodEnd,
        List<String> datasetCodes,
        boolean refresh,
        String exportFormat,
        boolean anomalyPeopleRequested,
        boolean projectPeriodRequested
) {
    public BusinessQueryIntent {
        datasetCodes = datasetCodes == null ? List.of() : List.copyOf(datasetCodes);
    }
}
```

在 resolver 的允许语义和完整 JSON 中增加：

```text
8. 是否要求按项目起止日期查询：projectPeriodRequested。
12. projectPeriodRequested 只表示用户明确说“项目期间”等语义；不得因此生成 periodStart 或 periodEnd。
```

在 validator 中加入显式日期成对校验：

```java
if ((intent.periodStart() == null) != (intent.periodEnd() == null)) {
    throw new BusinessException(400, "业务数据时间范围必须同时提供开始和结束日期");
}
```

更新所有 `new BusinessQueryIntent(...)` 调用，明确补充最后一个布尔值，不增加旧构造器兼容代码。

- [ ] **Step 4: 运行测试并确认通过**

```powershell
mvn -pl ai-agent -am -Dtest=BusinessIntentValidatorTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS。

- [ ] **Step 5: 提交**

```powershell
git add ai-agent/src/main/java/org/example/ai/agent/business/intent ai-agent/src/test/java/org/example/ai/agent
git commit -m "feat(agent): recognize project period intent"
```

### Task 2: 让缺失数据集配置变成可展示状态

**Files:**
- Create: `ai-agent/src/main/java/org/example/ai/agent/business/person/PersonDatasetPlanService.java`
- Create: `ai-agent/src/test/java/org/example/ai/agent/business/person/PersonDatasetPlanServiceTest.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/business/impl/BusinessAssistantServiceImpl.java`
- Modify: `ai-agent/src/test/java/org/example/ai/agent/business/BusinessAssistantServiceTest.java`

- [ ] **Step 1: 写失败测试**

覆盖三项规则：报销单模块未配置时不抛异常、出差可用但报销缺失时只执行出差、考勤任一依赖缺失时整项考勤不可执行。

```java
@Test
void missingAttendanceDependencyKeepsIndependentTravelExecutable() {
    Selection selection = new PersonDatasetSelectionService()
            .select(List.of("TRAVEL", "ATTENDANCE"));

    PlanResult result = service.plan(selection, configured(
            DatasetType.TRAVEL, DatasetType.CALENDAR, DatasetType.PUNCH, DatasetType.LEAVE
    ), Map.of("startDate", "2026-01-01", "endDate", "2026-01-31"));

    assertThat(result.plans()).extracting(DatasetPlan::type)
            .containsExactly(DatasetType.TRAVEL);
    assertThat(result.unavailableSemanticCodes()).containsExactly("ATTENDANCE");
}
```

- [ ] **Step 2: 运行测试并确认失败**

```powershell
mvn -pl ai-agent -am -Dtest=PersonDatasetPlanServiceTest,BusinessAssistantServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL，现有 `personPlans` 在配置不完整时直接抛出 `IllegalStateException`。

- [ ] **Step 3: 实现计划服务**

使用一个职责单一的服务返回可执行计划和不可用语义：

```java
/** 根据当前数据集配置生成最小人员查询计划，不执行工作流。 */
@Service
public class PersonDatasetPlanService {

    private static final Set<DatasetType> ATTENDANCE_REQUIRED = Set.of(
            DatasetType.CALENDAR, DatasetType.SCHEDULE, DatasetType.PUNCH,
            DatasetType.LEAVE, DatasetType.TRAVEL
    );

    public PlanResult plan(
            Selection selection,
            Map<DatasetType, ReportDataset> configured,
            Map<String, Object> canonicalQuery) {
        List<DatasetPlan> plans = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        if (selection.requested(DatasetType.TRAVEL)) {
            addRoot(plans, unavailable, DatasetType.TRAVEL, "TRAVEL", configured, canonicalQuery);
        }
        if (selection.requested(DatasetType.REIMBURSEMENT)) {
            addRoot(plans, unavailable, DatasetType.REIMBURSEMENT,
                    "REIMBURSEMENT", configured, canonicalQuery);
        }
        if (selection.requested(DatasetType.PUNCH)) {
            if (configured.keySet().containsAll(ATTENDANCE_REQUIRED)) {
                for (DatasetType type : selection.executionTypes()) {
                    if (ATTENDANCE_REQUIRED.contains(type)
                            && plans.stream().noneMatch(plan -> plan.type() == type)) {
                        plans.add(datasetPlan(type, configured.get(type), canonicalQuery,
                                type == DatasetType.PUNCH));
                    }
                }
            } else {
                unavailable.add("ATTENDANCE");
            }
        }
        return new PlanResult(plans, unavailable);
    }

    public record PlanResult(
            List<DatasetPlan> plans,
            List<String> unavailableSemanticCodes) {
        public PlanResult {
            plans = List.copyOf(plans);
            unavailableSemanticCodes = List.copyOf(unavailableSemanticCodes);
        }
        public boolean hasExecutablePlan() {
            return !plans.isEmpty();
        }
    }
}
```

`datasetPlan` 继续使用现有稳定事实编码；不要把工作流编码写死。把 `BusinessAssistantServiceImpl.personPlans` 的配置扫描逻辑迁入该服务并删除旧方法。

- [ ] **Step 4: 把不可用语义转换为友好回答输入**

在 `BusinessAssistantServiceImpl` 增加固定映射：

```java
private DatasetAnswerInput unavailableDataset(String semanticCode) {
    return new DatasetAnswerInput(
            semanticCode,
            personDatasetName(semanticCode),
            DatasetExecutionStatus.FAILED,
            false,
            Map.of(),
            Map.of(),
            "数据源尚未配置，本次未纳入统计"
    );
}
```

当 `PlanResult.hasExecutablePlan()` 为 `false` 时不调用 `PersonBusinessQueryService.query`；仍正常完成响应流。

- [ ] **Step 5: 验证并提交**

```powershell
mvn -pl ai-agent -am -Dtest=PersonDatasetPlanServiceTest,BusinessAssistantServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
git add ai-agent/src/main/java/org/example/ai/agent/business/person/PersonDatasetPlanService.java ai-agent/src/main/java/org/example/ai/agent/business/impl/BusinessAssistantServiceImpl.java ai-agent/src/test/java/org/example/ai/agent/business/person/PersonDatasetPlanServiceTest.java ai-agent/src/test/java/org/example/ai/agent/business/BusinessAssistantServiceTest.java
git commit -m "feat(agent): disclose unavailable person datasets"
```

Expected: tests PASS；新增 Java 文件处于 tracked 状态。

### Task 3: 实现独立授权的项目期间上下文

**Files:**
- Create: `ai-agent/src/main/java/org/example/ai/agent/business/person/ProjectPeriodContextService.java`
- Create: `ai-agent/src/test/java/org/example/ai/agent/business/person/ProjectPeriodContextServiceTest.java`

- [ ] **Step 1: 写失败测试**

测试必须验证：人员与项目各自复权；`PROJECT_BASE` 缺失时返回未配置；项目日期缺失时返回期间不可用；`PROJECT_MEMBER` 缺失时仍允许直接关联；执行请求携带当前登录人的 `authorization`。

```java
@Test
void membershipDatasetMayBeMissingWhileDirectAssociationRemainsAvailable() {
    prepareAuthorizedPersonAndProject();
    prepareProjectBase("2026-01-01", "2026-12-31");

    Result result = service.resolve(command());

    assertThat(result.status()).isEqualTo(Status.READY);
    assertThat(result.context().periodStart()).isEqualTo(LocalDate.of(2026, 1, 1));
    assertThat(result.context().membershipPeriods()).isEmpty();
    assertThat(result.context().membershipAvailable()).isFalse();
    verify(executionService).execute(argThat(request ->
            "Bearer login-user".equals(request.authorization())));
}
```

- [ ] **Step 2: 运行测试并确认失败**

```powershell
mvn -pl ai-agent -am -Dtest=ProjectPeriodContextServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL，服务尚不存在。

- [ ] **Step 3: 定义稳定契约**

```java
/** 独立复核人员和项目权限，并从可配置数据集取得项目期间。 */
@Service
public class ProjectPeriodContextService {

    public static final String PROJECT_BASE_DOMAIN = "PROJECT_BASE";
    public static final String PROJECT_MEMBER_DOMAIN = "PROJECT_MEMBER";
    public static final String PROJECT_BASE_FACT = "project_base";
    public static final String PROJECT_MEMBER_FACT = "project_membership_records";

    public enum Status {
        READY,
        PROJECT_DATASET_NOT_CONFIGURED,
        PERIOD_UNAVAILABLE,
        DENIED,
        FAILED
    }

    public record Command(
            String agentRunId,
            String userId,
            String sessionId,
            String authorization,
            Map<String, Object> secureContext,
            String personSelectionToken,
            String projectSelectionToken,
            Integer projectYear) {
    }

    public record MembershipPeriod(LocalDate start, LocalDate end) {
        public MembershipPeriod {
            if (start == null || end != null && start.isAfter(end)) {
                throw new IllegalArgumentException("项目成员有效期不合法");
            }
        }
    }

    public record Context(
            String projectCode,
            String projectId,
            LocalDate periodStart,
            LocalDate periodEnd,
            List<MembershipPeriod> membershipPeriods,
            boolean membershipAvailable) {
        public Context {
            membershipPeriods = List.copyOf(membershipPeriods);
        }
    }

    public record Result(Status status, Context context, String safeMessage) {
        public boolean ready() {
            return status == Status.READY && context != null;
        }
    }
}
```

- [ ] **Step 4: 实现授权和数据集执行**

实现顺序必须固定：

```java
public Result resolve(Command command) {
    ValidatedCommand validated = validate(command);
    AuthorizedSubjectCandidate person = reauthorizePerson(validated);
    AuthorizedSubjectCandidate project = reauthorizeProject(validated);
    ReportDataset projectBase = uniqueDataset(PROJECT_BASE_DOMAIN);
    if (projectBase == null) {
        return unavailable(Status.PROJECT_DATASET_NOT_CONFIGURED,
                "项目基础数据源尚未配置，暂时无法取得项目期间");
    }
    DatasetExecutionResult base = executeProjectBase(validated, project, projectBase);
    Context baseContext = readProjectPeriod(base, project);
    if (baseContext == null) {
        return unavailable(Status.PERIOD_UNAVAILABLE,
                "项目基础数据未提供完整起止日期，请补充日期范围或完善字段映射");
    }
    ReportDataset member = uniqueDataset(PROJECT_MEMBER_DOMAIN);
    if (member == null) {
        return new Result(Status.READY, withoutMembership(baseContext),
                "项目成员数据源尚未配置，仅能判断项目直接关联记录");
    }
    return new Result(Status.READY,
            withMembership(baseContext, executeMembership(validated, person, project, member)),
            null);
}
```

`reauthorizePerson` 使用 `AuthorizedPersonDirectoryService`，`reauthorizeProject` 使用 `ProjectDirectoryService`；两者都先解析各自类型绑定的选择令牌，再用 `SELECTED_SUBJECT` 查询来源目录。查询入参中的 `employeeNo`、`projectCode` 和 `projectId` 必须由复权结果覆盖，不信任客户端同名字段。

`readProjectPeriod` 只读取 `safeFacts.calculation.project_base`，校验 `projectStartDate`、`projectEndDate`，不得读取原始工作流响应。

- [ ] **Step 5: 验证并提交**

```powershell
mvn -pl ai-agent -am -Dtest=ProjectPeriodContextServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
git add ai-agent/src/main/java/org/example/ai/agent/business/person/ProjectPeriodContextService.java ai-agent/src/test/java/org/example/ai/agent/business/person/ProjectPeriodContextServiceTest.java
git commit -m "feat(agent): resolve authorized project period context"
```

Expected: PASS；无数据库和配置文件变更。

### Task 4: 把项目关联服务收敛为纯分类规则

**Files:**
- Modify: `ai-agent/src/main/java/org/example/ai/agent/business/model/AssociationType.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/business/person/ProjectRecordAssociationService.java`
- Modify: `ai-agent/src/test/java/org/example/ai/agent/business/person/ProjectRecordAssociationServiceTest.java`
- Modify: `ai-agent/src/test/java/org/example/ai/agent/business/BusinessDomainContractTest.java`

- [ ] **Step 1: 写失败测试**

```java
@Test
void missingIdentityAndMembershipIsUnknownWithoutInventingTotals() {
    AssociationType type = service.classify(
            new ProjectIdentity("XXXT2674040", "P-1"),
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 12, 31),
            List.of(),
            new RecordReference("T-1", null, null, LocalDate.of(2026, 3, 1))
    );

    assertThat(type).isEqualTo(AssociationType.UNKNOWN);
}

@Test
void conflictingProjectIdentityIsUnrelatedAndNeverFallsBackToMembership() {
    AssociationType type = service.classify(
            new ProjectIdentity("XXXT2674040", "P-1"),
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 12, 31),
            List.of(new MembershipPeriod(LocalDate.of(2026, 1, 1), null)),
            new RecordReference("T-1", "OTHER", null, LocalDate.of(2026, 3, 1))
    );

    assertThat(type).isEqualTo(AssociationType.UNRELATED);
}
```

- [ ] **Step 2: 运行测试并确认失败**

```powershell
mvn -pl ai-agent -am -Dtest=ProjectRecordAssociationServiceTest,BusinessDomainContractTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL，现有服务把金额统计和关联分类混在一起，且没有 `UNKNOWN`。

- [ ] **Step 3: 实现纯分类契约**

在 `AssociationType` 增加 `UNKNOWN`，保留 `UNRELATED` 表达明确不属于目标项目：

```java
public enum AssociationType {
    DIRECT,
    PROJECT_PERSON_PERIOD,
    UNKNOWN,
    UNRELATED
}
```

删除 `BusinessRecord` 中的 `cost`、`hours`、`travelAmount`、`reimbursementAmount` 和 `ProjectTotals`。服务只返回分类：

```java
public AssociationType classify(
        ProjectIdentity target,
        LocalDate periodStart,
        LocalDate periodEnd,
        List<MembershipPeriod> memberships,
        RecordReference record) {
    validate(target, periodStart, periodEnd, record);
    if (StringUtils.hasText(record.projectCode())
            || StringUtils.hasText(record.projectId())) {
        return identityMatches(target, record)
                ? AssociationType.DIRECT
                : AssociationType.UNRELATED;
    }
    if (record.occurredOn() == null) {
        return AssociationType.UNKNOWN;
    }
    boolean contextual = memberships.stream().anyMatch(period ->
            contains(periodStart, periodEnd, record.occurredOn())
                    && contains(period.start(), period.end(), record.occurredOn()));
    return contextual
            ? AssociationType.PROJECT_PERSON_PERIOD
            : AssociationType.UNKNOWN;
}
```

- [ ] **Step 4: 验证并提交**

```powershell
mvn -pl ai-agent -am -Dtest=ProjectRecordAssociationServiceTest,BusinessDomainContractTest -Dsurefire.failIfNoSpecifiedTests=false test
git add ai-agent/src/main/java/org/example/ai/agent/business/model/AssociationType.java ai-agent/src/main/java/org/example/ai/agent/business/person/ProjectRecordAssociationService.java ai-agent/src/test/java/org/example/ai/agent/business/person/ProjectRecordAssociationServiceTest.java ai-agent/src/test/java/org/example/ai/agent/business/BusinessDomainContractTest.java
git commit -m "refactor(agent): separate project association from totals"
```

Expected: PASS；关联服务不再计算任何金额。

### Task 5: 在人员查询中应用项目关联和确定性统计

**Files:**
- Modify: `ai-agent/src/main/java/org/example/ai/agent/business/person/PersonBusinessQueryService.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/business/person/PersonBusinessFactAggregator.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/business/snapshot/BusinessSnapshotDerivationService.java`
- Modify: `ai-agent/src/test/java/org/example/ai/agent/business/person/PersonBusinessQueryServiceTest.java`
- Modify: `ai-agent/src/test/java/org/example/ai/agent/business/snapshot/BusinessSnapshotDerivationServiceTest.java`

- [ ] **Step 1: 写失败测试**

测试同一批记录包含直接关联、人员期间关联、未知和明确无关四类时，只有直接关联进入项目正式金额；期间记录只增加说明计数；缺失金额保持 incomplete。

```java
@Test
void projectContextCountsOnlyDirectRecordsAndKeepsContextDisclosure() {
    prepareTravelFacts(List.of(
            travel("T-1", "XXXT2674040", "2026-03-01T08:00:00", "10.00"),
            travel("T-2", null, "2026-03-02T08:00:00", "20.00"),
            travel("T-3", null, null, "30.00"),
            travel("T-4", "OTHER", "2026-03-03T08:00:00", "40.00")
    ));

    Result result = service.query(projectCommand());

    assertThat(result.travelSummary().tripCount().value()).isEqualTo(1);
    assertThat(result.travelSummary().totalAmount().value())
            .isEqualByComparingTo("10.00");
    assertThat(result.associationSummary().directCount()).isEqualTo(1);
    assertThat(result.associationSummary().contextCount()).isEqualTo(1);
    assertThat(result.associationSummary().unknownCount()).isEqualTo(1);
}
```

- [ ] **Step 2: 运行测试并确认失败**

```powershell
mvn -pl ai-agent -am -Dtest=PersonBusinessQueryServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL，当前聚合器会统计全部人员日期范围记录。

- [ ] **Step 3: 扩展命令和安全结果**

在 `PersonBusinessQueryService` 中定义：

```java
public record ProjectAssociationContext(
        String projectCode,
        String projectId,
        LocalDate periodStart,
        LocalDate periodEnd,
        List<ProjectPeriodContextService.MembershipPeriod> membershipPeriods,
        boolean membershipAvailable) {
    public ProjectAssociationContext {
        membershipPeriods = List.copyOf(membershipPeriods);
    }
}

public record AssociationSummary(
        int directCount,
        int contextCount,
        int unknownCount,
        int unrelatedCount) {
}
```

给 `Command` 增加可空的 `ProjectAssociationContext projectContext`；给 `Result` 增加 `AssociationSummary associationSummary` 和 `List<String> associationLabels`。没有项目上下文时保持原人员统计行为，summary 全部为零。

- [ ] **Step 4: 在聚合前分类记录**

`PersonBusinessFactAggregator` 对三个根业务事实使用明确日期字段：

```java
private LocalDate occurredOn(DatasetType type, Map<String, Object> record) {
    String field = switch (type) {
        case TRAVEL -> "startAt";
        case PUNCH -> "time";
        case REIMBURSEMENT -> "occurredAt";
        default -> null;
    };
    return field == null ? null : parseDate(record.get(field));
}
```

分类完成后构造新的 calculation map：根业务事实只保留 `DIRECT` 记录供项目正式统计；请假、排班、日历等考勤依赖仍按已授权时间范围处理。`PROJECT_PERSON_PERIOD`、`UNKNOWN`、`UNRELATED` 只形成计数和固定标签，不把记录明细或金额复制到模型上下文。

固定标签：

```java
private static final String CONTEXT_LABEL =
        "存在项目人员期间关联记录，仅供上下文参考，不计入项目直接统计";
private static final String UNKNOWN_LABEL =
        "部分记录的项目关联无法确认，未计入项目直接统计";
```

禁止改变 `DatasetExecutionResult` 或伪造完整性证明。来源执行结果先按现有流程写入来源快照；存在项目上下文时，再通过 `BusinessSnapshotDerivationService` 从已复权的来源快照派生项目关联快照：

```java
public record ProjectAssociationCommand(
        String agentRunId,
        String userId,
        String sessionId,
        String authorization,
        Map<String, Object> secureContext,
        String datasetCode,
        String subjectId,
        String sourceSnapshotId,
        Map<String, Object> targetQuery,
        DatasetType datasetType,
        ProjectAssociationContext projectContext) {
}

public record ProjectAssociationDerivation(
        BusinessSnapshot snapshot,
        Map<String, Object> directCalculationFacts,
        AssociationSummary summary,
        List<String> labels) {
}
```

派生快照复用现有 `ai_business_snapshot` 和 `ai_business_snapshot_item`：

- `source_snapshot_id` 指向来源快照。
- `direct` 执行项保存 `AssociationType.DIRECT` 和直接关联计数。
- `context` 执行项保存 `AssociationType.PROJECT_PERSON_PERIOD` 和上下文计数。
- `unknown` 执行项保存 `AssociationType.UNKNOWN` 和未知计数。
- `unrelated` 不进入导出事实，只保留计数供审计。
- `facts_json` 的 export/model/calculation/display 通道都重新经过当前字段策略清洗。
- 报告章节引用派生快照，不能继续引用未分类的来源快照。

`PersonBusinessQueryService` 使用 `directCalculationFacts` 计算回答，并将派生快照 ID 放入 `ModuleResult`，因此回答和报告共享同一份已分类事实。

- [ ] **Step 5: 验证并提交**

```powershell
mvn -pl ai-agent -am -Dtest=PersonBusinessQueryServiceTest,ProjectRecordAssociationServiceTest,BusinessSnapshotDerivationServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
git add ai-agent/src/main/java/org/example/ai/agent/business/person/PersonBusinessQueryService.java ai-agent/src/main/java/org/example/ai/agent/business/person/PersonBusinessFactAggregator.java ai-agent/src/main/java/org/example/ai/agent/business/snapshot/BusinessSnapshotDerivationService.java ai-agent/src/test/java/org/example/ai/agent/business/person/PersonBusinessQueryServiceTest.java ai-agent/src/test/java/org/example/ai/agent/business/snapshot/BusinessSnapshotDerivationServiceTest.java
git commit -m "feat(agent): calculate project-aware person facts"
```

Expected: PASS；金额继续使用 `BigDecimal`；缺失金额不变成零。

### Task 6: 连接双主体会话与项目期间编排

**Files:**
- Modify: `ai-agent/src/main/java/org/example/ai/agent/business/impl/BusinessAssistantServiceImpl.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/chat/memory/model/BusinessConversationState.java`
- Modify: `ai-agent/src/test/java/org/example/ai/agent/business/BusinessAssistantServiceTest.java`
- Modify: `ai-agent/src/test/java/org/example/ai/agent/business/BusinessAssistantSecurityIntegrationTest.java`

- [ ] **Step 1: 写失败测试**

覆盖：人员主体和项目主体分别解析；项目编码不会作为人员定位条件；项目期间结果覆盖查询计划日期；下一轮追问继承两种选择令牌；项目权限拒绝后人员业务工作流零调用。

```java
@Test
void projectPeriodUsesIndependentProjectAuthorizationAndEffectiveDates() throws Exception {
    fixture.intent(personProjectPeriodIntent());
    fixture.resolvePerson("person-token");
    fixture.resolveProject("project-token", "XXXT2674040");
    fixture.projectPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));

    fixture.handle("查询张三在 XXXT2674040 项目期间的出差");

    verify(personBusinessQueryService).query(argThat(command ->
            command.plans().stream().allMatch(plan ->
                    "2026-01-01".equals(plan.canonicalInput().get("startDate"))
                            && "2026-12-31".equals(plan.canonicalInput().get("endDate")))));
    assertThat(fixture.savedInput()).containsKeys(
            "personSelectionToken", "projectSelectionToken", "projectCode",
            "startDate", "endDate", "projectPeriodRequested");
}
```

- [ ] **Step 2: 运行测试并确认失败**

```powershell
mvn -pl ai-agent -am -Dtest=BusinessAssistantServiceTest,BusinessAssistantSecurityIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL，当前会话只保存一个通用 `selectionToken`，且不会执行项目期间上下文服务。

- [ ] **Step 3: 调整薄编排顺序**

人员分支执行顺序固定为：

```java
private void handlePerson(
        AgentRequest request,
        AgentStreamSession stream,
        String runId,
        BusinessQueryIntent intent,
        Selection selection,
        SubjectCandidate person) throws Exception {
    ProjectPeriodContextService.Result period = resolveProjectPeriodIfRequested(
            request, runId, intent, person
    );
    if (period != null && !period.ready()) {
        finishWithSafeMessage(request, stream, runId, intent, period.safeMessage());
        return;
    }
    BusinessQueryIntent effectiveIntent = period == null
            ? intent
            : withPeriod(intent, period.context().periodStart(), period.context().periodEnd());
    PersonDatasetPlanService.PlanResult planResult = personDatasetPlanService.plan(
            selection, configuredPersonDatasets(), canonicalQuery(effectiveIntent)
    );
    // 后续只执行 planResult.plans()，不可用模块单独进入回答说明。
}
```

项目上下文通过 `SubjectResolutionService` 使用 `BusinessSubjectType.PROJECT` 单独定位。未明确到唯一项目时返回项目候选，不执行人员业务数据集。

- [ ] **Step 4: 保存双主体上下文**

继续复用 `BusinessConversationState.lastInput` 的 JSON，不新增数据库字段：

```java
input.put("personSelectionToken", person.selectionToken());
if (project != null) {
    input.put("projectSelectionToken", project.selectionToken());
    input.put("projectCode", project.projectCode());
}
input.put("projectPeriodRequested", intent.projectPeriodRequested());
```

删除人员分支保存通用 `selectionToken` 的旧逻辑。主主体选择读取 `personSelectionToken` 或 `projectSelectionToken`；显式新项目编码必须清除继承的项目令牌，但不得清除已确认人员令牌。

- [ ] **Step 5: 友好结束规则**

- 项目期间不可用：只返回项目期间提示，不执行人员模块，不创建报告。
- 所有请求模块未配置：返回“当前尚未配置可用的数据源”，不调用报告服务。
- 至少一个模块成功或有效 EMPTY：回答可继续；缺失模块逐项披露。
- DENIED 使用统一安全提示，不区分不存在和无权查看。

- [ ] **Step 6: 验证并提交**

```powershell
mvn -pl ai-agent -am -Dtest=BusinessAssistantServiceTest,BusinessAssistantSecurityIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
git add ai-agent/src/main/java/org/example/ai/agent/business/impl/BusinessAssistantServiceImpl.java ai-agent/src/main/java/org/example/ai/agent/chat/memory/model/BusinessConversationState.java ai-agent/src/test/java/org/example/ai/agent/business/BusinessAssistantServiceTest.java ai-agent/src/test/java/org/example/ai/agent/business/BusinessAssistantSecurityIntegrationTest.java
git commit -m "feat(agent): orchestrate person project period queries"
```

Expected: PASS；权限拒绝场景没有后续业务调用。

### Task 7: 阻止空白报告并传递部分报告说明

**Files:**
- Modify: `ai-agent/src/main/java/org/example/ai/agent/business/report/BusinessAssistantReportService.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/business/report/BusinessReportPlanService.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/business/report/CompositeReportTaskService.java`
- Modify: `ai-agent/src/test/java/org/example/ai/agent/business/report/CompositeReportTaskServiceTest.java`
- Modify: `ai-agent/src/test/java/org/example/ai/agent/business/BusinessAssistantServiceTest.java`

- [ ] **Step 1: 写失败测试**

```java
@Test
void allUnavailablePersonModulesDoNotCreateReportTask() throws Exception {
    fixture.requestPdf();
    fixture.noConfiguredPersonDataset();

    fixture.handle("导出张三的出差、打卡和报销 PDF");

    verifyNoInteractions(compositeReportTaskService);
    assertThat(fixture.answerText()).contains("尚未配置").doesNotContain("生成队列");
}

@Test
void partialReportKeepsUnavailableSectionsAsSafeMetadata() {
    ArtifactBlock artifact = service.createPersonReport(command(
            successfulTravelModule(),
            List.of(
                    "报销数据源尚未配置，本次未纳入统计",
                    "存在项目人员期间关联记录，仅供上下文参考，不计入项目直接统计"
            )
    ));

    assertThat(artifact).isNotNull();
    verify(reportTaskService).create(argThat(value -> value.plannedReport().plan()
            .sections().get(0).safeMessage().contains("报销数据源尚未配置")));
}
```

- [ ] **Step 2: 运行测试并确认失败**

```powershell
mvn -pl ai-agent -am -Dtest=CompositeReportTaskServiceTest,BusinessAssistantServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL，当前报告命令没有缺失章节和关联标签元数据。

- [ ] **Step 3: 扩展人员报告命令**

```java
public record PersonReportCommand(
        ReportIdentity identity,
        String format,
        Map<String, Object> canonicalQuery,
        boolean refreshRequested,
        List<PersonBusinessQueryService.ModuleResult> modules,
        List<String> safeNotices) {
    public PersonReportCommand {
        canonicalQuery = safeMap(canonicalQuery);
        modules = List.copyOf(modules);
        safeNotices = List.copyOf(safeNotices);
    }
}
```

创建任务前筛选 `REUSED`、`SUCCESS`、`EMPTY` 模块：

```java
List<ModuleResult> reportable = command.modules().stream()
        .filter(this::reportable)
        .toList();
if (reportable.isEmpty()) {
    throw new IllegalStateException("没有可生成报告的有效业务章节");
}
PlannedReport planned = plan(reportable);
planned = attachSafeNotices(planned, command.safeNotices());
```

失败和未配置模块不伪造字段策略校验和，也不创建虚假章节。固定安全说明合并后写入第一个有效章节现有的 `safe_message` 字段。`BusinessReportPlanService` 和 `CompositeReportTaskService` 只允许服务端固定说明集合，拒绝任意原始接口错误；有效章节允许携带固定说明，但快照和字段策略校验规则不变。

- [ ] **Step 4: 验证并提交**

```powershell
mvn -pl ai-agent -am -Dtest=CompositeReportTaskServiceTest,BusinessAssistantServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
git add ai-agent/src/main/java/org/example/ai/agent/business/report/BusinessAssistantReportService.java ai-agent/src/main/java/org/example/ai/agent/business/report/BusinessReportPlanService.java ai-agent/src/main/java/org/example/ai/agent/business/report/CompositeReportTaskService.java ai-agent/src/test/java/org/example/ai/agent/business/report/CompositeReportTaskServiceTest.java ai-agent/src/test/java/org/example/ai/agent/business/BusinessAssistantServiceTest.java
git commit -m "feat(agent): guard partial business report creation"
```

Expected: PASS；没有新增表字段。

### Task 8: 连接生产报告文件处理器

**Files:**
- Create: `ai-agent/src/main/java/org/example/ai/agent/business/report/BusinessReportFileHandler.java`
- Create: `ai-agent/src/test/java/org/example/ai/agent/business/report/BusinessReportFileHandlerTest.java`
- Modify: `ai-agent/src/test/java/org/example/ai/agent/business/report/CompositeReportWorkerTest.java`
- Modify: `ai-agent/src/main/resources/application.yml`
- Modify: `ai-agent/src/main/resources/application-dev.yml`
- Modify: `ai-agent/src/main/resources/application-prod.yml`

- [ ] **Step 1: 写失败测试**

测试三种格式共享同一逻辑文档、只读取快照 export 通道、关联标签和未配置章节进入文件、存储路径等于 worker 给定路径。

```java
@ParameterizedTest
@ValueSource(strings = {"XLSX", "DOCX", "PDF"})
void rendersSafeSnapshotWithAssociationAndUnavailableDisclosure(String format) {
    prepareSectionSnapshot(
            "TRAVEL", exportTravelFacts(), AssociationType.DIRECT,
            "报销数据源尚未配置，本次未纳入统计"
    );

    ArtifactMetadata artifact = handler.generate(
            "task-1", format, "reports/task-1/report." + format.toLowerCase(), sections()
    );

    assertThat(artifact.storagePath()).startsWith("reports/task-1/");
    assertThat(storedBytes()).isNotEmpty();
    assertThat(capturedDocument().associationLabels()).contains("项目直接关联");
    assertThat(capturedDocument().statusSections()).extracting(StatusSection::safeMessage)
            .contains("报销数据源尚未配置，本次未纳入统计");
}
```

- [ ] **Step 2: 运行测试并确认失败**

```powershell
mvn -pl ai-agent -am -Dtest=BusinessReportFileHandlerTest,CompositeReportWorkerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL，生产环境尚无 `ReportFileHandler` Bean。

- [ ] **Step 3: 实现处理器边界**

```java
/** 从安全快照生成报告文件，不读取来源系统原始响应。 */
@Component
public class BusinessReportFileHandler implements CompositeReportWorker.ReportFileHandler {

    @Override
    public ArtifactMetadata generate(
            String taskId,
            String format,
            String targetPath,
            List<CompositeReportSection> sections) {
        CompositeReportTask task = requireTask(taskId);
        SafeReportFacts facts = loadSafeFacts(task, sections);
        LogicalReportDocument document = assembler.assemble(facts.toReportInput());
        byte[] content = renderer(format).render(document);
        SafeArtifactStorageService.StoredArtifact stored = store(targetPath, content);
        return new ArtifactMetadata(
                stored.relativePath(), stored.fileName(), mimeType(format),
                stored.fileSize(), stored.checksum()
        );
    }
}
```

实现必须遵守：

- 任务使用现有 `CompositeReportTaskMapper.selectByTaskId` 读取。
- 章节快照使用现有 `BusinessSnapshotMapper` 读取。
- 关联标签和计数使用现有 `BusinessSnapshotItemMapper` 读取。
- 数据集字段使用 `ReportDatasetFieldMapper` 读取并按 `displayOrder` 排序。
- 只消费派生快照 `facts_json` 中 `DIRECT` 执行项的 `export` 通道。
- `PROJECT_PERSON_PERIOD` 和 `UNKNOWN` 执行项只转换为固定关联标签，不导出其记录金额。
- 章节 `safe_message` 只能转换为固定 `StatusSection` 或关联说明。
- 渲染器复用 `XlsxReportRenderer`、`DocxReportRenderer`、`PdfReportRenderer`。
- 文件写入复用 `SafeArtifactStorageService.store`，不得直接调用 `Files.write`。
- 任一 JSON 结构不合法时失败关闭，让现有 worker 重试/失败状态处理接管。

PDF 字体路径通过环境变量提供，不硬编码本机路径：

```yaml
ai:
  business:
    composite-report:
      pdf-font-path: ${AI_BUSINESS_REPORT_PDF_FONT_PATH:}
```

- [ ] **Step 4: 验证并提交**

```powershell
mvn -pl ai-agent -am -Dtest=BusinessReportFileHandlerTest,CompositeReportWorkerTest,ReportRendererContractTest -Dsurefire.failIfNoSpecifiedTests=false test
git add ai-agent/src/main/java/org/example/ai/agent/business/report/BusinessReportFileHandler.java ai-agent/src/test/java/org/example/ai/agent/business/report/BusinessReportFileHandlerTest.java ai-agent/src/test/java/org/example/ai/agent/business/report/CompositeReportWorkerTest.java ai-agent/src/main/resources/application.yml ai-agent/src/main/resources/application-dev.yml ai-agent/src/main/resources/application-prod.yml
git commit -m "feat(agent): generate business report artifacts"
```

Expected: PASS；应用启动时 `CompositeReportWorker` 能取得唯一的 `ReportFileHandler` Bean。

### Task 9: 完成端到端验收、文档和最终门禁

**Files:**
- Modify: `ai-agent/src/test/java/org/example/ai/agent/business/BusinessAssistantAcceptanceFixture.java`
- Modify: `ai-agent/src/test/java/org/example/ai/agent/business/BusinessAssistantAcceptanceTest.java`
- Modify: `ai-agent/src/test/java/org/example/ai/agent/business/BusinessAssistantSecurityIntegrationTest.java`
- Create or Modify: `docs/runbooks/business-assistant.md`
- Create or Modify: `docs/prompts/business-assistant-system-prompt.md`
- Modify: `CONTEXT.md`

- [ ] **Step 1: 扩展验收夹具**

增加 `PROJECT_BASE`、`PROJECT_MEMBER`、未配置模块、项目拒绝、部分可用和真实文件处理器桩。夹具中的安全事实至少包含：

```java
Map.of(
        "project_base", Map.of(
                "projectId", "P-1",
                "projectCode", "XXXT2674040",
                "projectStartDate", "2026-01-01",
                "projectEndDate", "2026-12-31"
        )
)
```

成员记录和业务记录必须同时覆盖直接关联、期间关联、未知和无关四类，不使用生产数据。

- [ ] **Step 2: 完成用户故事验收**

扩展 `personProjectPeriodPdfKeepsProjectContextAndDisclosesIncompleteSection`，明确断言：

```java
assertThat(responseText).contains(
        "出差总金额", "打卡", "报销", "数据源尚未配置",
        "项目人员期间关联"
);
assertThat(reportCreated).isTrue();
assertThat(reportBytes).isNotEmpty();
assertThat(reportText).contains("XXXT2674040", "2026-01-01", "2026-12-31");
```

再增加“全部业务数据集未配置”的验收，断言提示存在、`reportCreated=false`、制品目录没有文件。

- [ ] **Step 3: 运行重点验收测试**

```powershell
mvn -pl ai-agent -am -Dtest=BusinessAssistantAcceptanceTest,BusinessAssistantSecurityIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS。

- [ ] **Step 4: 更新运行手册和提示词**

`docs/runbooks/business-assistant.md` 必须记录：

- `PROJECT_BASE`、`PROJECT_MEMBER`、`TRAVEL`、`ATTENDANCE`、`LEAVE`、`REIMBURSEMENT` 配置方式。
- 每个数据集可选择不同 READ 工作流及不同入参映射。
- 项目期间字段和业务记录关联字段的必填规则。
- 未配置、无记录、拒绝和失败的区别。
- 全部不可用不生成报告、部分可用允许报告。
- 报告 worker、存储目录、重试和排查方式。
- 普通员工本人数据与特殊权限人员查询的权限行为。

`docs/prompts/business-assistant-system-prompt.md` 与运行时 resolver 保持一致，并明确模型不得生成日期、金额、权限结果或工作流编码。

`CONTEXT.md` 只把测试通过的内容标记为已实现；外部真实数据仍标记为“待管理员配置”。

- [ ] **Step 5: 执行完整后端验证**

```powershell
mvn -pl ai-agent -am test
mvn -DskipTests compile
mvn test
git diff --check
git status --short
```

Expected:

- 三条 Maven 命令退出码均为 `0`。
- `git diff --check` 无输出。
- `git status --short` 只包含本任务预期文档变更，或为空。
- 没有外部业务数据时，测试证明功能行为正确，但不得声称真实接口数据已验证。

- [ ] **Step 6: 显式加入新增文件并提交**

```powershell
git add ai-agent/src/test/java/org/example/ai/agent/business/BusinessAssistantAcceptanceFixture.java ai-agent/src/test/java/org/example/ai/agent/business/BusinessAssistantAcceptanceTest.java ai-agent/src/test/java/org/example/ai/agent/business/BusinessAssistantSecurityIntegrationTest.java docs/runbooks/business-assistant.md docs/prompts/business-assistant-system-prompt.md CONTEXT.md
git commit -m "test(agent): verify configurable business assistant flow"
```

### Task 10: 最终清理和完成提示

**Files:**
- Review only: all files changed since `b421c0e`

- [ ] **Step 1: 检查变更范围**

```powershell
git diff --stat b421c0e..HEAD
git diff --name-status b421c0e..HEAD
git status --short --branch
```

Expected: 只修改 `ai-agent`、业务助手文档和 `CONTEXT.md`；无 `pom.xml`、Flyway、`ai-common`、`ai-rag` 变更。

- [ ] **Step 2: 检查新增 Java 文件和中文注释**

```powershell
git diff --name-status b421c0e..HEAD | Select-String '^A\s+.*\.java$'
git ls-files ai-agent/src/main/java/org/example/ai/agent/business/person/PersonDatasetPlanService.java ai-agent/src/main/java/org/example/ai/agent/business/person/ProjectPeriodContextService.java ai-agent/src/main/java/org/example/ai/agent/business/report/BusinessReportFileHandler.java
```

Expected: 三个新增类均由 `git ls-files` 输出，类级职责和关键安全规则有中文注释。

- [ ] **Step 3: 检查遗留旧逻辑**

```powershell
rg -n "人员业务查询缺少完整且唯一的已启用数据集配置|ProjectTotals|new BusinessQueryIntent" ai-agent/src/main/java ai-agent/src/test/java
```

Expected:

- 旧的“配置缺失直接抛异常”文案不存在。
- `ProjectTotals` 不存在。
- 所有 `BusinessQueryIntent` 构造器参数数量一致。

- [ ] **Step 4: 给出最终提示**

完成时必须向用户明确说明：

1. 已完成哪些功能。
2. 实际修改了哪些模块和关键文件。
3. 执行了哪些测试及其结果。
4. 外部出差、考勤、请假、报销数据仍需用户配置。
5. 是否存在未验证的真实接口、数据库或字体环境。
6. 代码仍位于隔离工作树和功能分支，尚未合并到主项目；等待用户决定最终合并方式。

不得在没有全部验证证据时使用“后台已经全部完成”或“真实数据已经验证”等表述。
