# Business Assistant Task17.3 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让人员和部门查询按用户点名的出差、考勤、报销语义生成最小执行闭包，并只发布用户请求的回答结果。

**Architecture:** 新增一个纯规则服务，将稳定语义解析成用户请求根类型和有序执行类型；现有入口继续从已注册数据集生成具体计划。计划用 `userRequested` 区分展示根类型与内部依赖，单人查询、多人员汇总、部门回答和会话继承都使用同一契约。

**Tech Stack:** Java 17、Spring Boot 4、JUnit 5、AssertJ、Mockito、Maven、现有业务快照与报告服务

---

## 文件结构

- 新增 `ai-agent/src/main/java/org/example/ai/agent/business/person/PersonDatasetSelectionService.java`：唯一负责人员业务语义规范化与依赖闭包。
- 新增 `ai-agent/src/test/java/org/example/ai/agent/business/person/PersonDatasetSelectionServiceTest.java`：覆盖默认、单模块、组合、别名和非法语义。
- 修改 `PersonBusinessQueryService.java`：计划增加请求标记并支持有效子集。
- 修改 `BoundedPersonFanOutService.java`：按请求根类型判断完整性和聚合。
- 修改 `DepartmentBusinessQueryService.java`：允许动态非空计划并维持安全边界。
- 修改 `BusinessAssistantServiceImpl.java`：接入选择结果、过滤回答、动态指标、保存与继承语义。
- 修改 `BusinessQueryIntentResolver.java`、`BusinessIntentValidator.java`：收紧模型契约和输入边界。
- 修改上述类的现有测试，不新增 Mapper、SQL、Flyway、配置或依赖。

### Task 1: 稳定语义选择与会话契约

**Files:**
- Create: `ai-agent/src/main/java/org/example/ai/agent/business/person/PersonDatasetSelectionService.java`
- Create: `ai-agent/src/test/java/org/example/ai/agent/business/person/PersonDatasetSelectionServiceTest.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/business/intent/BusinessQueryIntentResolver.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/business/intent/BusinessIntentValidator.java`
- Modify: `ai-agent/src/test/java/org/example/ai/agent/business/intent/BusinessIntentValidatorTest.java`

- [ ] **Step 1: 写语义闭包失败测试**

新增真实规则测试，至少包含：

```java
@Test
void shouldResolveOnlyReimbursement() {
    Selection result = service.resolve(List.of("REIMBURSEMENT"));

    assertThat(result.semanticCodes()).containsExactly("REIMBURSEMENT");
    assertThat(result.requestedTypes()).containsExactly(DatasetType.REIMBURSEMENT);
    assertThat(result.executionTypes()).containsExactly(DatasetType.REIMBURSEMENT);
}

@Test
void shouldExpandAttendanceAndRemoveDuplicateTravel() {
    Selection result = service.resolve(List.of("ATTENDANCE", "TRAVEL"));

    assertThat(result.semanticCodes()).containsExactly("TRAVEL", "ATTENDANCE");
    assertThat(result.requestedTypes()).containsExactlyInAnyOrder(
            DatasetType.TRAVEL, DatasetType.PUNCH);
    assertThat(result.executionTypes()).containsExactly(
            DatasetType.TRAVEL, DatasetType.PUNCH, DatasetType.LEAVE,
            DatasetType.SCHEDULE, DatasetType.CALENDAR);
}

@Test
void shouldRejectUnknownPersonSemanticInsteadOfQueryingAll() {
    assertThatThrownBy(() -> service.resolve(List.of("LEAVE")))
            .isInstanceOf(BusinessException.class)
            .hasMessage("暂不支持该人员业务数据类型，请查询出差、考勤或报销");
}
```

另加空数组默认全览、`PUNCH` 归一为 `ATTENDANCE`、考勤加报销执行六类的测试。

- [ ] **Step 2: 运行测试并确认 RED**

Run:

```powershell
mvn -pl ai-agent -Dtest=PersonDatasetSelectionServiceTest test
```

Expected: FAIL，原因是 `PersonDatasetSelectionService` 尚不存在。

- [ ] **Step 3: 实现最小纯规则服务**

实现公开 API：

```java
@Component
public class PersonDatasetSelectionService {

    private static final List<String> DEFAULT_CODES =
            List.of("TRAVEL", "ATTENDANCE", "REIMBURSEMENT");
    private static final Set<DatasetType> ATTENDANCE_TYPES = EnumSet.of(
            DatasetType.TRAVEL, DatasetType.PUNCH, DatasetType.LEAVE,
            DatasetType.SCHEDULE, DatasetType.CALENDAR);

    public Selection resolve(List<String> source) {
        List<String> codes = normalize(source);
        EnumSet<DatasetType> requested = EnumSet.noneOf(DatasetType.class);
        EnumSet<DatasetType> execution = EnumSet.noneOf(DatasetType.class);
        for (String code : codes) {
            switch (code) {
                case "TRAVEL" -> add(requested, execution, DatasetType.TRAVEL);
                case "ATTENDANCE" -> {
                    requested.add(DatasetType.PUNCH);
                    execution.addAll(ATTENDANCE_TYPES);
                }
                case "REIMBURSEMENT" -> add(
                        requested, execution, DatasetType.REIMBURSEMENT);
                default -> throw unsupported();
            }
        }
        return new Selection(codes, requested, ordered(execution));
    }
}
```

`normalize` 将空列表替换成默认值，将 `PUNCH` 归一为 `ATTENDANCE`，去重后按 `TRAVEL`、`ATTENDANCE`、`REIMBURSEMENT` 固定顺序返回。`Selection` 深冻结集合和列表，并提供 `requested(DatasetType type)`；新增类和关键边界添加中文注释，不记录用户输入。

- [ ] **Step 4: 收紧模型提示和基础校验**

`BusinessQueryIntentResolver.SYSTEM_PROMPT` 明确：

```text
PERSON 或 DEPARTMENT 的 datasetCodes 只允许 TRAVEL、ATTENDANCE、REIMBURSEMENT；
“打卡、缺卡、考勤”必须返回 ATTENDANCE；未点名业务模块时返回空数组。
PROJECT 的模块仍由项目全景配置决定。
```

`BusinessIntentValidator` 在现有格式校验前拒绝超过 4 项和完全重复编码：

```java
if (intent.datasetCodes().size() > 4
        || new HashSet<>(intent.datasetCodes()).size() != intent.datasetCodes().size()) {
    return false;
}
```

为数量和重复项补失败用例，项目现有大写编码格式测试保持通过。

- [ ] **Step 5: 运行聚焦测试并确认 GREEN**

Run:

```powershell
mvn -pl ai-agent -Dtest=PersonDatasetSelectionServiceTest,BusinessIntentValidatorTest test
```

Expected: 两个测试类全部通过。

- [ ] **Step 6: 显式暂存新增类并提交**

```powershell
git add -- ai-agent/src/main/java/org/example/ai/agent/business/person/PersonDatasetSelectionService.java ai-agent/src/test/java/org/example/ai/agent/business/person/PersonDatasetSelectionServiceTest.java ai-agent/src/main/java/org/example/ai/agent/business/intent/BusinessQueryIntentResolver.java ai-agent/src/main/java/org/example/ai/agent/business/intent/BusinessIntentValidator.java ai-agent/src/test/java/org/example/ai/agent/business/intent/BusinessIntentValidatorTest.java
git commit -m "feat(agent): resolve person dataset semantics"
```

### Task 2: 单人查询支持动态计划

**Files:**
- Modify: `ai-agent/src/main/java/org/example/ai/agent/business/person/PersonBusinessQueryService.java`
- Modify: `ai-agent/src/test/java/org/example/ai/agent/business/person/PersonBusinessQueryServiceTest.java`

- [ ] **Step 1: 写动态子集失败测试**

在现有真实服务测试中新增：

```java
@Test
void shouldExecuteOnlyRequestedReimbursementPlan() {
    Result result = service.query(command(List.of(
            plan(DatasetType.REIMBURSEMENT, true))));

    assertThat(result.modules()).extracting(ModuleResult::type)
            .containsExactly(DatasetType.REIMBURSEMENT);
    assertThat(result.reimbursementSummary().paidAmount().complete()).isTrue();
    assertThat(result.travelSummary().tripCount().complete()).isFalse();
    verify(executionService, times(1)).execute(any());
}

@Test
void shouldRejectPunchWithoutAttendanceDependencies() {
    assertThatThrownBy(() -> service.query(command(List.of(
            plan(DatasetType.PUNCH, true)))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("考勤查询缺少依赖数据集");
}
```

再覆盖五类考勤执行、无 `PUNCH` 时不核算考勤、依赖失败使考勤闭包不完整。

- [ ] **Step 2: 运行测试并确认 RED**

Run:

```powershell
mvn -pl ai-agent -Dtest=PersonBusinessQueryServiceTest test
```

Expected: FAIL，原因是现有计划没有 `userRequested` 且服务强制六类。

- [ ] **Step 3: 扩展计划契约**

将记录改为：

```java
public record DatasetPlan(
        DatasetType type,
        String datasetCode,
        Map<String, Object> canonicalInput,
        String requestedGrain,
        Set<String> requiredFactCodes,
        boolean userRequested) {
}
```

`toString()` 只增加 `userRequested` 布尔值。同步更新全部四处构造点，不保留旧五参数构造器。

- [ ] **Step 4: 实现动态校验和执行**

最小规则：

```java
if (command.plans().isEmpty() || command.plans().size() > DatasetType.values().length) {
    throw new IllegalArgumentException("人员业务查询数据集计划不完整");
}
if (plans.values().stream().noneMatch(DatasetPlan::userRequested)) {
    throw new IllegalArgumentException("人员业务查询缺少用户请求数据集");
}
if (plans.containsKey(DatasetType.PUNCH)
        && !plans.keySet().containsAll(ATTENDANCE_DATASETS)) {
    throw new IllegalArgumentException("考勤查询缺少依赖数据集");
}
```

只有 `PUNCH` 存在时执行五类时间与粒度一致性校验。查询循环按 `DatasetType.values()` 固定顺序过滤存在的计划：

```java
for (DatasetType type : DatasetType.values()) {
    DatasetPlan plan = validated.plans().get(type);
    if (plan == null) {
        continue;
    }
    // 保留现有加载、快照和安全事实逻辑
}
```

只有 `PUNCH` 存在时调用 `factAggregator.attendance(...)`，否则返回空列表。未执行的出差和报销摘要继续通过 `SafeFacts=null` 生成不完整指标。

- [ ] **Step 5: 运行聚焦测试并确认 GREEN**

```powershell
mvn -pl ai-agent -Dtest=PersonBusinessQueryServiceTest test
```

Expected: 全部通过，精确执行次数与计划数量一致。

- [ ] **Step 6: 提交**

```powershell
git add -- ai-agent/src/main/java/org/example/ai/agent/business/person/PersonBusinessQueryService.java ai-agent/src/test/java/org/example/ai/agent/business/person/PersonBusinessQueryServiceTest.java
git commit -m "feat(agent): execute dynamic person datasets"
```

### Task 3: 多人和部门按请求计划汇总

**Files:**
- Modify: `ai-agent/src/main/java/org/example/ai/agent/business/person/BoundedPersonFanOutService.java`
- Modify: `ai-agent/src/test/java/org/example/ai/agent/business/person/BoundedPersonFanOutServiceTest.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/business/department/DepartmentBusinessQueryService.java`
- Modify: `ai-agent/src/test/java/org/example/ai/agent/business/department/DepartmentBusinessQueryServiceTest.java`

- [ ] **Step 1: 写子集完整性和聚合失败测试**

新增测试证明：

```java
@Test
void reimbursementOnlyShouldCompleteWithoutTravelMetrics() {
    MultiPersonSummary result = service.query(
            List.of(personRequest(reimbursementOnlyCommand())), () -> false);

    assertThat(result.status()).isEqualTo(ExecutionStatus.COMPLETED);
    assertThat(result.aggregate().complete()).isTrue();
    assertThat(result.aggregate().tripCount()).isNull();
    assertThat(result.aggregate().travelAmount()).isNull();
    assertThat(result.aggregate().reimbursementAmount()).isEqualByComparingTo("80.00");
}
```

再覆盖出差单查、考勤不发布出差金额、考勤依赖失败为部分完成、部门接收一类/五类计划且每位成员计划相同。

- [ ] **Step 2: 运行两个测试类并确认 RED**

```powershell
mvn -pl ai-agent -Dtest=BoundedPersonFanOutServiceTest,DepartmentBusinessQueryServiceTest test
```

Expected: FAIL，原因是完整性、聚合和部门校验仍固定六类。

- [ ] **Step 3: 按请求根类型判断人员完成**

`completePersonResult` 同时接收请求：

```java
private boolean completePersonResult(PersonRequest request, Result result) {
    Set<DatasetType> requested = request.command().plans().stream()
            .filter(DatasetPlan::userRequested)
            .map(DatasetPlan::type)
            .collect(Collectors.toUnmodifiableSet());
    return result.modules().stream().allMatch(ModuleResult::complete)
            && (!requested.contains(DatasetType.TRAVEL)
                || result.travelSummary().tripCount().complete()
                && result.travelSummary().totalAmount().complete())
            && (!requested.contains(DatasetType.REIMBURSEMENT)
                || result.reimbursementSummary().paidAmount().complete());
}
```

只有请求 `TRAVEL` 才累加出差指标，只有请求 `REIMBURSEMENT` 才累加报销支付金额，只有请求 `PUNCH` 才收集考勤异常。未请求字段在完整聚合中保持 `null`。

- [ ] **Step 4: 放宽部门计划校验但不放宽安全边界**

`DepartmentBusinessQueryService.validate` 改为计划数量 1 到 6、类型和编码唯一、至少一个 `userRequested=true`；保留 required facts、不可变计划和所有权限校验。实际闭包正确性继续由每个单人服务校验，入口只会从 Task 1 的选择服务生成计划。

- [ ] **Step 5: 运行聚焦测试并确认 GREEN**

```powershell
mvn -pl ai-agent -Dtest=BoundedPersonFanOutServiceTest,DepartmentBusinessQueryServiceTest test
```

Expected: 全部通过，子集没有空指针，原有权限、分页、取消和 100 人边界继续通过。

- [ ] **Step 6: 提交**

```powershell
git add -- ai-agent/src/main/java/org/example/ai/agent/business/person/BoundedPersonFanOutService.java ai-agent/src/test/java/org/example/ai/agent/business/person/BoundedPersonFanOutServiceTest.java ai-agent/src/main/java/org/example/ai/agent/business/department/DepartmentBusinessQueryService.java ai-agent/src/test/java/org/example/ai/agent/business/department/DepartmentBusinessQueryServiceTest.java
git commit -m "feat(agent): aggregate selected person datasets"
```

### Task 4: 入口回答、报告与追问继承

**Files:**
- Modify: `ai-agent/src/main/java/org/example/ai/agent/business/impl/BusinessAssistantServiceImpl.java`
- Modify: `ai-agent/src/test/java/org/example/ai/agent/business/BusinessAssistantServiceTest.java`

- [ ] **Step 1: 写入口行为失败测试**

新增或改写测试，直接断言以下可观察行为：

```java
@Test
void reimbursementOnlyShouldBuildOnePlanAndOneReportSection() {
    // 意图 datasetCodes=[REIMBURSEMENT]，执行现有单人入口。
    ArgumentCaptor<PersonBusinessQueryService.Command> query =
            ArgumentCaptor.forClass(PersonBusinessQueryService.Command.class);
    verify(personBusinessQueryService).query(query.capture());
    assertThat(query.getValue().plans()).extracting(DatasetPlan::type)
            .containsExactly(DatasetType.REIMBURSEMENT);
    assertThat(answerDatasetCodes()).containsExactly("PERSON_REIMBURSEMENT");
}
```

还要覆盖：考勤生成五计划但回答只有 PUNCH；考勤与出差时 TRAVEL 标记为请求；部门仅报销不发布出差指标；会话保存规范语义；下一轮空语义继承；上下文重置不继承；项目分支不调用人员选择服务。

补充候选轮测试：人员或部门首次进入多候选时保存规范 `datasetCodes`，下一轮选择候选且意图未重新点名业务模块时，继续使用原选择范围；每轮在主体解析前只调用一次人员语义服务。

- [ ] **Step 2: 运行入口测试并确认 RED**

```powershell
mvn -pl ai-agent -Dtest=BusinessAssistantServiceTest test
```

Expected: FAIL，原因是入口仍生成六计划并固定回答指标和表格。

- [ ] **Step 3: 注入选择服务并生成标记计划**

人员和部门入口先解析一次：

```java
Selection selection = personDatasetSelectionService.resolve(intent.datasetCodes());
List<DatasetPlan> plans = personPlans(intent, selection);
```

`personPlans` 只为 `selection.executionTypes()` 创建计划：

```java
return selection.executionTypes().stream()
        .map(type -> personPlan(
                type, configured, query, selection.requested(type)))
        .toList();
```

只要求 `selection.executionTypes()` 涉及的已启用人员数据集配置完整且唯一。仅报销只需要报销配置，考勤必须具备五类依赖，默认全览仍要求六类；未选择类型的缺失、停用或重复配置不阻断本轮查询。

- [ ] **Step 4: 只组装用户请求的回答**

`personDatasets` 跳过 `userRequested=false` 的模块。`personMetrics` 按计划逐项追加，`personTables` 仅在请求 `PUNCH` 时返回考勤表。考勤模块完整性使用五类执行模块共同判断，不能只读取 PUNCH 模块。

部门业务指标按 `plans` 中的请求标记发布：

```java
if (result.aggregate().complete() && requested(plans, DatasetType.TRAVEL)) {
    // 添加 tripCount 和 travelAmount
}
if (result.aggregate().complete() && requested(plans, DatasetType.REIMBURSEMENT)) {
    // 添加 reimbursementAmount
}
```

异常表还必须同时满足请求 `PUNCH`、用户明确请求异常人员且安全异常列表非空。

- [ ] **Step 5: 保存与继承规范业务语义**

保存状态时添加：

```java
input.put("datasetCodes", selection.semanticCodes());
```

将本轮规范语义随处理结果传给 `saveState`。`inheritQueryContext` 只从服务端 `inheritedInput.datasetCodes` 读取字符串列表；当前意图非空或 `contextReset=true` 时不继承。读取值重新经过选择服务校验，不能信任客户端上下文。

- [ ] **Step 6: 保持报告与实际执行闭包一致**

人员报告继续传 `result.modules()`：单查只含一个模块，考勤含五个核算依据模块，组合请求是去重后的并集。不得把仅有 PUNCH 的原始快照称为派生考勤汇总。部门导出安全拒绝逻辑保持不变。

- [ ] **Step 7: 运行入口测试并确认 GREEN**

```powershell
mvn -pl ai-agent -Dtest=BusinessAssistantServiceTest test
```

Expected: 全部通过；只报销为一计划，考勤为五计划，回答只含请求根模块。

- [ ] **Step 8: 提交**

```powershell
git add -- ai-agent/src/main/java/org/example/ai/agent/business/impl/BusinessAssistantServiceImpl.java ai-agent/src/test/java/org/example/ai/agent/business/BusinessAssistantServiceTest.java
git commit -m "feat(agent): answer selected business datasets"
```

### Task 5: 全量验证与双阶段审查

**Files:**
- Verify: `ai-agent/src/main/java/org/example/ai/agent/business/**`
- Verify: `ai-agent/src/test/java/org/example/ai/agent/business/**`
- Verify: `docs/superpowers/specs/2026-09-10-business-assistant-task17-3-design.md`

- [ ] **Step 1: 运行 Task17.3 聚焦回归**

```powershell
mvn -pl ai-agent -Dtest=PersonDatasetSelectionServiceTest,PersonBusinessQueryServiceTest,BoundedPersonFanOutServiceTest,DepartmentBusinessQueryServiceTest,BusinessAssistantServiceTest,BusinessIntentValidatorTest test
```

Expected: 全部通过，无失败和错误。

- [ ] **Step 2: 运行模块全量测试**

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17.0.19'
$env:MODEL_CONFIG_ENCRYPTION_KEY=[Convert]::ToBase64String(
    [Text.Encoding]::UTF8.GetBytes('0123456789abcdef0123456789abcdef')
)
mvn -pl ai-agent -am clean test
```

Expected: Reactor `BUILD SUCCESS`；`ai-agent` 和依赖模块测试无失败、无错误。

- [ ] **Step 3: 检查改动边界**

```powershell
git diff c2186da..HEAD --check
git diff --name-only c2186da..HEAD
rg -n "@(Select|Insert|Update|Delete)" ai-agent/src/main/java/org/example/ai/agent/business/person ai-agent/src/main/java/org/example/ai/agent/business/department
git status --short
```

Expected: 无空白错误；没有 Mapper、Mapper XML、Flyway、`pom.xml`、配置或前端文件变更；新增类均已被 Git 跟踪；没有注解 SQL。

- [ ] **Step 4: 规格审查**

审查者逐条核对设计第 2、5 至 12 节，重点确认精确执行闭包、请求与依赖隔离、未知语义失败关闭、部门未请求事实不发布、报告限制如实表达和会话继承只使用服务端状态。发现问题必须由实现者修复并复审。

- [ ] **Step 5: 代码质量审查**

规格通过后再检查中文注释、职责、空指针、敏感日志、重复规则、测试真实性和无关改动。不得用评审替代测试，发现问题必须修复并复审。

- [ ] **Step 6: 确认阶段边界**

```powershell
git status --short --branch
git log --oneline -8
```

Expected: 工作树干净，Task17.3 每个实现任务都有独立提交。最终提示必须明确 Task17 后端闭环已完成，同时说明独立考勤派生报告章节、部门报告、超过 100 人异步批次和 Task18 前端仍不在本阶段；必须停下并等待用户确认后才能进入 Task18。

## 自检结果

- 规格覆盖：Task 1 覆盖稳定语义和白名单；Task 2 覆盖单人动态执行；Task 3 覆盖多人和部门；Task 4 覆盖回答、报告和追问；Task 5 覆盖全量验证与审查。
- 完整性扫描：每个代码步骤都给出明确文件、接口、命令和预期结果，没有待补内容。
- 类型一致：统一使用 `PersonDatasetSelectionService.Selection`、`DatasetPlan.userRequested`、`semanticCodes`、`requestedTypes` 和 `executionTypes`。
- YAGNI：没有依赖图配置、派生考勤数据集、部门报告、异步批次、新数据库结构或新依赖。
