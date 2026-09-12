# Business Assistant Task17.2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完成“已选部门 -> 当前登录人授权成员目录 -> 100 人以内有界逐人查询 -> 安全部门汇总回答”的后端闭环。

**Architecture:** 新增一个独立的部门成员目录边界，并复用现有目录配置校验、结果证明校验、主体选择令牌、六类人员查询计划和有界扇出服务。部门编排服务负责复权、分页完整性和安全聚合，`BusinessAssistantServiceImpl` 只负责路由、SSE 回答组装和会话状态保存。

**Tech Stack:** Java 17、Spring Boot 4、MyBatis Plus、Jackson、JUnit 5、Mockito、AssertJ、Maven。

---

## 执行门禁

本计划严格按用户要求分阶段执行：每完成一个 Task，必须运行该 Task 的验证、显式 `git add` 新增后台类、提交一次独立 commit、向用户汇报，然后等待用户确认再进入下一个 Task。不得在一次确认中连续实现多个 Task。

本阶段不新增数据库表、Flyway、Mapper、Mapper.xml、依赖或部门报告导出。若实施时发现必须扩大这些范围，立即停止并说明风险，不自行扩展。

## 文件职责总览

- `BusinessQueryIntent`：只增加“是否明确要求异常人员名单”的展示意图，不承载权限。
- `DepartmentMemberDirectoryQuery`：部门成员目录的安全分页输入，字符串输出不暴露部门 ID、认证或上下文。
- `DepartmentMemberDirectoryService`：部门成员目录边界，便于部门编排与工作流实现解耦。
- `WorkflowBackedDepartmentMemberDirectoryService`：执行后台已注册 READ 数据集并失败关闭。
- `DirectoryResultParser`：复用同一套目录安全事实、候选字段、证明和分页协议；不复制第二份解析器。
- `DepartmentBusinessQueryService`：部门复权、最多两页成员读取、人员令牌签发、有界扇出和状态计数。
- `BusinessAssistantServiceImpl`：部门路由、确定性卡片/异常表格、导出不支持提示和会话状态。
- `AgentStreamSession`：仅增加一个只读“业务查询是否应停止”方法，把用户取消、线程中断和 SSE 断开传给有界扇出。

### Task 1: 扩展结构化意图的异常人员展示标志

**Files:**
- Modify: `ai-agent/src/main/java/org/example/ai/agent/business/intent/BusinessQueryIntent.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/business/intent/BusinessQueryIntentResolver.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/business/impl/BusinessAssistantServiceImpl.java`
- Modify: `ai-agent/src/test/java/org/example/ai/agent/business/intent/BusinessIntentValidatorTest.java`
- Modify: `ai-agent/src/test/java/org/example/ai/agent/business/BusinessAssistantServiceTest.java`

- [ ] **Step 1: 编写意图解析失败测试**

在 `BusinessIntentValidatorTest` 增加两个测试：模型明确返回 `anomalyPeopleRequested=true` 时保留为 `true`；旧 JSON 未返回该字段时 Jackson 的 primitive 默认值必须为 `false`。核心断言：

```java
assertThat(resolved.anomalyPeopleRequested()).isTrue();
assertThat(missingFieldResolved.anomalyPeopleRequested()).isFalse();
```

同时把现有模型 JSON fixture 补成完整字段，避免“明确返回”和“字段缺失”两个场景混在一起。

- [ ] **Step 2: 运行测试确认 RED**

```powershell
mvn -pl ai-agent -am -Dtest=BusinessIntentValidatorTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：因 `BusinessQueryIntent` 尚无 `anomalyPeopleRequested()` 而编译失败。

- [ ] **Step 3: 最小修改意图契约和提示词**

将记录尾部改为：

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
        boolean anomalyPeopleRequested
) {
    public BusinessQueryIntent {
        datasetCodes = datasetCodes == null ? List.of() : List.copyOf(datasetCodes);
    }
}
```

`BusinessQueryIntentResolver.SYSTEM_PROMPT` 增加完整字段：

```json
"anomalyPeopleRequested": false
```

并明确规则：“仅当用户明确询问谁、哪些人、人员名单或人员明细时为 true；该字段只控制展示，不改变可查询数据范围，也不能替代后端校验。”提示词中继续避免出现“权限/permission”等执行层词，保持现有提示词边界测试通过。

- [ ] **Step 4: 更新全部构造点但不改变现有行为**

使用以下命令找全构造点：

```powershell
rg -n "new BusinessQueryIntent" ai-agent/src/main ai-agent/src/test
```

现有项目、人员及上下文继承构造一律追加 `false`；复制已有意图的 `withSubjectType(...)`、`inheritQueryContext(...)` 必须传递 `intent.anomalyPeopleRequested()`，不能重置为 `false`。本 Task 不接入部门查询。

- [ ] **Step 5: 运行测试确认 GREEN**

```powershell
mvn -pl ai-agent -am -Dtest=BusinessIntentValidatorTest,BusinessAssistantServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：两个测试类全部通过，现有项目/人员问答行为不变。

- [ ] **Step 6: 提交**

```powershell
git add ai-agent/src/main/java/org/example/ai/agent/business/intent/BusinessQueryIntent.java ai-agent/src/main/java/org/example/ai/agent/business/intent/BusinessQueryIntentResolver.java ai-agent/src/main/java/org/example/ai/agent/business/impl/BusinessAssistantServiceImpl.java ai-agent/src/test/java/org/example/ai/agent/business/intent/BusinessIntentValidatorTest.java ai-agent/src/test/java/org/example/ai/agent/business/BusinessAssistantServiceTest.java
git commit -m "feat(agent): capture department anomaly detail intent"
```

### Task 2: 新增严格的部门成员目录契约

**Files:**
- Create: `ai-agent/src/main/java/org/example/ai/agent/business/subject/DepartmentMemberDirectoryQuery.java`
- Create: `ai-agent/src/main/java/org/example/ai/agent/business/subject/DepartmentMemberDirectoryService.java`
- Create: `ai-agent/src/main/java/org/example/ai/agent/business/subject/WorkflowBackedDepartmentMemberDirectoryService.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/business/subject/DirectoryResultParser.java`
- Test: `ai-agent/src/test/java/org/example/ai/agent/business/subject/WorkflowBackedDepartmentMemberDirectoryServiceTest.java`

- [ ] **Step 1: 编写目录契约失败测试**

新测试类使用现有 `WorkflowBackedSubjectDirectoryServiceTest` 的签名结果和数据集配置 fixture，但数据集编码固定为 `DEPARTMENT_MEMBER_DIRECTORY`。测试方法固定为：

```text
executesConfiguredMemberDirectoryWithCurrentLoginIdentity
returnsOnlyPersonCandidatesWithMaskedEmployeeNumbers
failsClosedWhenDatasetCodeIsMissingOrMalformed
failsClosedWhenProofOrSourceDoesNotMatch
failsClosedWhenNonDisplayChannelContainsFacts
failsClosedWhenCandidateTypeOrPagingMetadataIsInvalid
queryToStringDoesNotLeakDepartmentAuthorizationOrSecureContext
```

成功场景必须捕获 `DatasetExecutionRequest` 并断言：

```java
assertThat(request.datasetCode()).isEqualTo("DEPARTMENT_MEMBER_DIRECTORY");
assertThat(request.subjectType()).isEqualTo(BusinessSubjectType.PERSON);
assertThat(request.subjectId()).isEqualTo("login-user-1");
assertThat(request.canonicalInput()).containsExactly(
        entry("departmentSubjectId", "department-raw-1"),
        entry("pageNumber", 1),
        entry("pageSize", 50)
);
```

- [ ] **Step 2: 运行测试确认 RED**

```powershell
mvn -pl ai-agent -am -Dtest=WorkflowBackedDepartmentMemberDirectoryServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：因三个新增类型尚不存在而编译失败。

- [ ] **Step 3: 创建安全查询记录和目录接口**

查询记录只接受必要参数：

```java
public record DepartmentMemberDirectoryQuery(
        String agentRunId,
        String userId,
        String sessionId,
        String authorization,
        Map<String, Object> secureContext,
        String departmentSubjectId,
        int pageNumber,
        int pageSize) {

    public DepartmentMemberDirectoryQuery {
        secureContext = freezeAndValidate(secureContext);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> freezeAndValidate(Map<String, Object> value) {
        Map<String, Object> frozen = (Map<String, Object>)
                ReportDatasetValidator.freezeSafeValue(value == null ? Map.of() : value);
        SubjectRequestLimits.validateContext(frozen);
        return frozen;
    }

    @Override
    public String toString() {
        return "DepartmentMemberDirectoryQuery[authorizationPresent="
                + (authorization != null && !authorization.isBlank())
                + ", secureContextPresent=" + !secureContext.isEmpty()
                + ", pageNumber=" + pageNumber
                + ", pageSize=" + pageSize + ']';
    }
}
```

`freezeAndValidate` 直接使用 `ReportDatasetValidator.freezeSafeValue(...)` 和同包 `SubjectRequestLimits.validateContext(...)`；不新增通用工具类。

接口保持单方法：

```java
public interface DepartmentMemberDirectoryService {
    SubjectDirectoryPage search(DepartmentMemberDirectoryQuery query);
}
```

- [ ] **Step 4: 让现有解析器复用人员候选协议**

不复制 `DirectoryResultParser`。保留现有 `parse(SubjectDirectoryQuery, ...)`，新增一个部门成员重载，并让两个重载进入同一个私有核心方法：

```java
SubjectDirectoryPage parse(
        DepartmentMemberDirectoryQuery query,
        DirectoryDatasetContractValidator.DirectoryDatasetContract contract,
        Map<String, Object> canonicalInput,
        DatasetExecutionResult result) {
    return parse(
            query.userId(), query.sessionId(), BusinessSubjectType.PERSON,
            query.pageNumber(), query.pageSize(), candidate -> { },
            contract, canonicalInput, result
    );
}
```

私有核心方法接收 `Consumer<AuthorizedSubjectCandidate>` 作为精确定位校验；普通主体目录传入现有 `validateExactLocator(query, candidate)`，成员目录传入空校验。证明、来源、四通道白名单、候选字段、掩码、页数量和 `hasNext` 的现有校验全部只保留一份。

- [ ] **Step 5: 实现工作流目录服务**

构造器依赖与现有主体目录实现一致，另接收：

```java
@Value("${ai.business.subject.directory.department-member-dataset-code:}")
String departmentMemberDatasetCode
```

核心输入和执行主体固定为：

```java
Map<String, Object> canonicalInput = Map.of(
        "departmentSubjectId", query.departmentSubjectId(),
        "pageNumber", query.pageNumber(),
        "pageSize", query.pageSize()
);
DatasetExecutionRequest request = new DatasetExecutionRequest(
        query.agentRunId(), query.userId(), query.sessionId(),
        query.authorization(), query.secureContext(), datasetCode,
        BusinessSubjectType.PERSON, query.userId(), canonicalInput
);
```

输入不完整、配置非法、数据集契约不符、执行失败或结果协议不符时统一返回 `SubjectDirectoryPage.denied()`。日志只输出 `traceHash`、安全分类和配置编码，不输出部门 ID、工号、Authorization、secureContext 或原始异常消息。

- [ ] **Step 6: 运行目录测试确认 GREEN**

```powershell
mvn -pl ai-agent -am -Dtest=WorkflowBackedDepartmentMemberDirectoryServiceTest,WorkflowBackedSubjectDirectoryServiceTest,SubjectResolutionServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：新增目录测试和现有主体目录测试全部通过。

- [ ] **Step 7: 显式暂存新增后台类并提交**

```powershell
git add ai-agent/src/main/java/org/example/ai/agent/business/subject/DepartmentMemberDirectoryQuery.java ai-agent/src/main/java/org/example/ai/agent/business/subject/DepartmentMemberDirectoryService.java ai-agent/src/main/java/org/example/ai/agent/business/subject/WorkflowBackedDepartmentMemberDirectoryService.java ai-agent/src/main/java/org/example/ai/agent/business/subject/DirectoryResultParser.java ai-agent/src/test/java/org/example/ai/agent/business/subject/WorkflowBackedDepartmentMemberDirectoryServiceTest.java
git commit -m "feat(agent): query authorized department members"
```

### Task 3: 实现部门复权、完整分页和有界逐人查询

**Files:**
- Create: `ai-agent/src/main/java/org/example/ai/agent/business/department/DepartmentBusinessQueryService.java`
- Test: `ai-agent/src/test/java/org/example/ai/agent/business/department/DepartmentBusinessQueryServiceTest.java`

- [ ] **Step 1: 编写部门编排失败测试**

测试方法固定为：

```text
deniesBeforeMemberLookupWhenDepartmentTokenIsInvalid
deniesBeforeMemberLookupWhenDepartmentAuthorizationWasRevoked
stopsWithoutFanOutWhenAuthorizedPeopleExceedConfiguredLimit
loadsTwoPagesAndIssuesFreshPersonTokensBeforeFanOut
failsClosedForChangedTotalDuplicateMemberOrPrematureEmptyPage
returnsCompletedZeroSummaryForAnEmptyAuthorizedDepartment
countsEveryPersonTerminalStatusWithoutExposingPeopleList
keepsFormalTotalsEmptyWhenFanOutIsPartial
passesCallerCancellationSignalToFanOut
```

超限测试必须断言：

```java
assertThat(result.status()).isEqualTo(DepartmentQueryStatus.LIMIT_EXCEEDED);
assertThat(result.processedPeople()).isZero();
verify(fanOutService, never()).query(any(), any());
verify(memberDirectoryService, times(1)).search(any());
```

两页成功测试必须断言所有 `PersonRequest.command().selectionToken()` 均来自 `SubjectSelectionTokenService.issue(...)`，且 `displayLabel` 仅为 `姓名（脱敏工号）`，不能包含 rawSubjectId。

- [ ] **Step 2: 运行测试确认 RED**

```powershell
mvn -pl ai-agent -am -Dtest=DepartmentBusinessQueryServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：因 `DepartmentBusinessQueryService` 尚不存在而编译失败。

- [ ] **Step 3: 创建唯一的部门编排服务**

服务构造器只注入五个现有边界和一个配置对象：

```java
public DepartmentBusinessQueryService(
        DepartmentDirectoryService departmentDirectoryService,
        DepartmentMemberDirectoryService memberDirectoryService,
        SubjectSelectionTokenService selectionTokenService,
        BoundedPersonFanOutService fanOutService,
        BusinessAssistantProperties properties)
```

公开入口保持一个：

```java
public Result query(Command command, BooleanSupplier cancellationRequested)
```

`Command` 只包含 run/user/session/authorization/secureContext、部门选择令牌、refresh 标志、异常人员展示标志和同一份六类 `List<PersonBusinessQueryService.DatasetPlan>`：

```java
public record Command(
        String agentRunId,
        String userId,
        String sessionId,
        String authorization,
        Map<String, Object> secureContext,
        String departmentSelectionToken,
        boolean refreshRequested,
        boolean anomalyPeopleRequested,
        List<PersonBusinessQueryService.DatasetPlan> plans) {

    @SuppressWarnings("unchecked")
    public Command {
        secureContext = (Map<String, Object>) ReportDatasetValidator.freezeSafeValue(
                secureContext == null ? Map.of() : secureContext
        );
        plans = plans == null ? List.of() : List.copyOf(plans);
    }

    @Override
    public String toString() {
        return "Command[authorizationPresent=" + StringUtils.hasText(authorization)
                + ", secureContextSize=" + secureContext.size()
                + ", selectionTokenPresent=" + StringUtils.hasText(departmentSelectionToken)
                + ", refreshRequested=" + refreshRequested
                + ", anomalyPeopleRequested=" + anomalyPeopleRequested
                + ", planCount=" + plans.size() + ']';
    }
}
```

- [ ] **Step 4: 按直线流程完成部门复权和分页**

方法顺序固定，避免多层套用：

```java
Optional<String> departmentId = selectionTokenService.resolve(
        command.departmentSelectionToken(), command.userId(),
        command.sessionId(), BusinessSubjectType.DEPARTMENT
);
if (departmentId.isEmpty()) {
    return Result.denied();
}
if (!departmentStillAuthorized(command, departmentId.get())) {
    return Result.denied();
}
SubjectDirectoryPage firstPage = memberDirectoryService.search(
        memberQuery(command, departmentId.get(), 1)
);
if (!firstPage.accessible()) {
    return Result.denied();
}
if (firstPage.totalCount() > properties.getMaxPeople()) {
    return Result.limitExceeded(firstPage.totalCount());
}
List<AuthorizedSubjectCandidate> members = loadAllPages(command, departmentId.get(), firstPage);
if (members == null) {
    return Result.denied();
}
```

`departmentStillAuthorized(...)` 必须执行现有部门目录的精确复核：

```java
SubjectDirectoryPage page = departmentDirectoryService.search(new SubjectDirectoryQuery(
        command.agentRunId(), command.userId(), command.sessionId(),
        command.authorization(), command.secureContext(), BusinessSubjectType.DEPARTMENT,
        SubjectSearchMode.SELECTED_SUBJECT, departmentId, null, null, null,
        null, null, 1, 2
));
return page.accessible()
        && page.totalCount() == 1
        && !page.hasNext()
        && page.candidates().size() == 1
        && page.candidates().get(0).type() == BusinessSubjectType.DEPARTMENT
        && departmentId.equals(page.candidates().get(0).rawSubjectId());
```

增加 `private static final int HARD_MAX_PEOPLE = 100`，实际限制取 `Math.min(properties.getMaxPeople(), HARD_MAX_PEOPLE)`，部署配置只能调低，不能把本阶段放大到 100 人以上。成员页大小固定为 `Math.min(50, effectiveLimit)`，因此当前最多读取两页，不增加新的分页配置。

`loadAllPages(...)` 使用 `LinkedHashMap<String, AuthorizedSubjectCandidate>` 按 rawSubjectId 保序去重。每次请求下一页后立即校验：

```java
if (!page.accessible()
        || page.pageNumber() != expectedPage
        || page.pageSize() != pageSize
        || page.totalCount() != expectedTotal
        || (page.hasNext() && page.candidates().isEmpty())) {
    return null;
}
for (AuthorizedSubjectCandidate candidate : page.candidates()) {
    if (candidate.type() != BusinessSubjectType.PERSON
            || members.putIfAbsent(candidate.rawSubjectId(), candidate) != null) {
        return null;
    }
}
```

最大页数使用 Java 17 可用的 `(expectedTotal + pageSize - 1) / pageSize` 计算；循环结束必须同时满足 `!lastPage.hasNext()` 和 `members.size() == expectedTotal`，否则失败关闭。`expectedTotal` 必须先验证不超过有效限制和 `Integer.MAX_VALUE`。

- [ ] **Step 5: 复用人员令牌和有界扇出**

每位成员构造：

```java
String personToken = selectionTokenService.issue(
        member.rawSubjectId(), command.userId(), command.sessionId(),
        BusinessSubjectType.PERSON
);
PersonBusinessQueryService.Command personCommand =
        new PersonBusinessQueryService.Command(
                command.agentRunId(), command.userId(), command.sessionId(),
                command.authorization(), command.secureContext(), personToken,
                command.refreshRequested(), command.plans()
        );
requests.add(new PersonRequest(
        member.displayName() + "（" + member.maskedEmployeeNo() + "）",
        command.anomalyPeopleRequested(), personCommand
));
```

`Result` 只保留：部门执行状态、`dataComplete`、授权人数、已加载人数、已处理人数、`MultiPersonSummary.Aggregate`、按 `PersonQueryStatus` 计数的不可变 Map、允许披露的 `AnomalyPerson` 和安全提示。不得返回成员候选、原始人员 ID、人员令牌或单人模块事实。

空部门不调用扇出，返回 complete=true、人数和三个正式指标均为 0。扇出部分成功时直接保留现有 `Aggregate.complete=false` 和空正式指标，不自行累加部分金额。

- [ ] **Step 6: 运行编排测试确认 GREEN**

```powershell
mvn -pl ai-agent -am -Dtest=DepartmentBusinessQueryServiceTest,BoundedPersonFanOutServiceTest,PersonBusinessQueryServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：部门编排、现有有界扇出和单人查询测试全部通过。

- [ ] **Step 7: 显式暂存新增后台类并提交**

```powershell
git add ai-agent/src/main/java/org/example/ai/agent/business/department/DepartmentBusinessQueryService.java ai-agent/src/test/java/org/example/ai/agent/business/department/DepartmentBusinessQueryServiceTest.java
git commit -m "feat(agent): aggregate authorized department queries"
```

### Task 4: 接入部门 SSE 回答、异常名单和缩小范围提示

**Files:**
- Modify: `ai-agent/src/main/java/org/example/ai/agent/chat/support/AgentStreamSession.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/business/impl/BusinessAssistantServiceImpl.java`
- Modify: `ai-agent/src/test/java/org/example/ai/agent/business/BusinessAssistantServiceTest.java`
- Modify: `ai-agent/src/test/java/org/example/ai/agent/chat/BusinessAssistantStreamIntegrationTest.java`

- [ ] **Step 1: 编写业务入口失败测试**

新增精确测试：

```text
departmentSummaryShowsCountsAndCompleteTotalsWithoutPeopleNames
departmentAnomalyQuestionShowsOnlyAllowedMaskedAnomalyPeople
departmentLimitExceededPromptsUserToNarrowScope
departmentPartialResultDoesNotPublishFormalTotals
departmentExportReturnsUnsupportedDatasetWithoutCreatingReportTask
departmentQueryReceivesUserCancellationAndDisconnectedStreamSignal
departmentResponseAndStoredJsonDoNotContainRawIdentifiersOrTokens
```

普通汇总断言最终响应包含授权人数、处理人数和完整总计，不包含 `张三`、`10***01`。异常追问只允许出现 `张三（10***01）` 和异常类型，不能出现原始工号。部门导出必须断言：

```java
verifyNoInteractions(reportTaskService);
assertThat(savedResponse).contains("本阶段暂不支持部门报告导出");
```

- [ ] **Step 2: 运行测试确认 RED**

```powershell
mvn -pl ai-agent -am -Dtest=BusinessAssistantServiceTest,BusinessAssistantStreamIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：部门分支仍返回旧占位，新增测试失败。

- [ ] **Step 3: 暴露只读取消信号**

在 `AgentStreamSession` 增加：

```java
/** 供有界业务查询读取停止信号，不改变最终保存状态。 */
public boolean shouldStopBusinessQuery() {
    return cancellationRequested
            || !connectionOpen.get()
            || Thread.currentThread().isInterrupted();
}
```

它不发事件、不结束响应、不修改状态，仅供 `BooleanSupplier` 读取。测试分别通过 `requestCancellation()` 和 SSE completion callback 证明两类信号都能变为 `true`；不能改变现有其他回答在断线后继续收尾保存的行为。

- [ ] **Step 4: 用一个直线方法替换部门占位**

向 `BusinessAssistantServiceImpl` 构造器注入 `DepartmentBusinessQueryService`，并把旧的：

```java
failedDataset("DEPARTMENT", "部门综合查询尚未配置")
```

替换为 `handleDepartment(...)`。调用形态固定为：

```java
DepartmentBusinessQueryService.Result result = departmentBusinessQueryService.query(
        new DepartmentBusinessQueryService.Command(
                runId, request.getUserId(), request.getConversationId(),
                request.getAuthorization(), Map.of(), subject.selectionToken(),
                intent.refresh(), intent.anomalyPeopleRequested(), personPlans(intent)
        ),
        stream::shouldStopBusinessQuery
);
```

普通 `displayFacts` 只放授权人数、加载人数、处理人数、五类人员状态数量和完整时的出差次数/金额、报销支付金额。仅当 `anomalyPeopleRequested=true` 时增加 `anomalyRows`，每行只含 `displayLabel` 和 `anomalyTypes`。`modelFacts` 保持为空，部门回答使用确定性组件，不调用模型生成全员总结。

- [ ] **Step 5: 组装固定指标和可选异常表格**

部门数据集编码固定为内部回答编码 `DEPARTMENT_SUMMARY`，不是后台接口编码。指标定义为：

```text
authorizedPeople 授权人数 人
processedPeople 处理人数 人
successPeople 成功人数 人
partialPeople 部分成功 人
failedPeople 失败人数 人
timeoutPeople 超时人数 人
cancelledPeople 取消人数 人
tripCount 出差次数 次
travelAmount 出差总金额 元
reimbursementAmount 报销支付金额 元
```

只有异常名单明确请求且结果非空时创建一张 `department_anomalies` 表，列为“人员（姓名+脱敏工号）”和“异常类型”。超限安全提示固定为“当前授权成员超过单次查询上限，请按下级部门或人员范围缩小查询”；不得建议只缩短时间就绕过人数限制。

如果 `exportFormat` 非空，在汇总数据集后追加：

```java
failedDataset("DEPARTMENT_REPORT", "本阶段暂不支持部门报告导出，请缩小到单个人员后导出")
```

不得调用 `BusinessAssistantReportService` 或 `CompositeReportTaskService`。最后复用现有 `saveState(...)` 保存部门选择令牌和时间范围，供下一轮追问重新复权。

- [ ] **Step 6: 运行入口和 SSE 测试确认 GREEN**

```powershell
mvn -pl ai-agent -am -Dtest=BusinessAssistantServiceTest,BusinessAssistantStreamIntegrationTest,DepartmentBusinessQueryServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：context snapshot 仍早于业务块，sequence 单调，取消能传递，部门安全回答测试全部通过，项目和人员测试不回归。

- [ ] **Step 7: 提交**

```powershell
git add ai-agent/src/main/java/org/example/ai/agent/chat/support/AgentStreamSession.java ai-agent/src/main/java/org/example/ai/agent/business/impl/BusinessAssistantServiceImpl.java ai-agent/src/test/java/org/example/ai/agent/business/BusinessAssistantServiceTest.java ai-agent/src/test/java/org/example/ai/agent/chat/BusinessAssistantStreamIntegrationTest.java
git commit -m "feat(agent): answer bounded department summaries"
```

### Task 5: 完整验证、双重评审和阶段收口

**Files:**
- Verify only; no planned production file changes.

- [ ] **Step 1: 检查修改范围和 SQL 边界**

```powershell
git diff 9558958 --check
git diff 9558958 --name-only
git status --short
rg -n "@(Select|Insert|Update|Delete)" ai-agent/src/main/java/org/example/ai/agent/business/department ai-agent/src/main/java/org/example/ai/agent/business/subject
```

预期：无空白错误；文件仅限本计划；最后一条无输出；未新增 Mapper、Mapper.xml、Flyway、pom 变更或数据库结构变更。

- [ ] **Step 2: 独占运行完整测试**

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17.0.19'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
$env:MODEL_CONFIG_ENCRYPTION_KEY=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('0123456789abcdef0123456789abcdef'))
mvn -pl ai-agent -am clean test
```

预期：Reactor `BUILD SUCCESS`。同一工作树不得并发启动第二个 Maven 进程写入 `target`。

- [ ] **Step 3: 运行规格评审**

使用独立评审代理逐条核对设计文档第 2、7、8、9、10、11、12 节。必须确认：101 人零扇出、100 人内完整分页、每人再次复权、普通汇总无名单、明确追问才显示脱敏异常、部分失败无正式总计、部门导出不建任务、无敏感信息泄露。

- [ ] **Step 4: 运行代码质量评审**

使用另一独立评审代理检查：中文注释、类职责、方法长度、异常失败关闭、无方法套方法、无重复目录解析、无无用兼容代码、无 SQL 写入 Java、无未使用代码和无关改动。发现问题后只修复本阶段范围，重新执行聚焦测试和 `git diff --check`。

- [ ] **Step 5: 最终状态检查并汇报**

```powershell
git status --short --branch
git log --oneline -5
```

预期：工作树干净，Task17.2 的每个实现 Task 各有独立提交。最终提示必须明确：Task17.2 部门汇总已完成；部门报告、超过 100 人异步批次、Task17.3 动态数据集依赖闭包和 Task18 前端仍未实现，不能宣称整个业务助手全部完成。

## 自检结果

- 规格覆盖：已覆盖设计文档中的部门复权、独立成员目录、100 人限制、分页完整性、有界扇出、取消、默认不显示名单、异常人员按需脱敏展示、部分结果不形成正式总计、导出安全拒绝、配置失败关闭和无数据库变更。
- YAGNI：没有新增部门快照、报告、异步任务、缓存、消息队列、通用分页框架、数据集计划服务或新依赖；六类人员计划继续由现有入口生成并传入部门服务。
- 类型一致性：成员目录复用 `SubjectDirectoryPage` / `AuthorizedSubjectCandidate`；逐人执行复用 `PersonBusinessQueryService.DatasetPlan` / `BoundedPersonFanOutService.PersonRequest`；回答复用 `DatasetAnswerInput`。
- 安全边界：原始部门 ID 和人员 ID 只存在于目录/令牌服务内部；回答、模型、SSE、日志和 `toString()` 只允许计数、正式汇总及用户明确请求的姓名+脱敏工号。
