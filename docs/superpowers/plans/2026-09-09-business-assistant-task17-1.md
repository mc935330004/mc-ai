# Business Assistant Task17.1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补齐项目全景快照追问复用和单人员组合报告创建，使 Task17 的 snapshot follow-up、explicit refresh 与 report creation 真正闭环。

**Architecture:** 新增聚焦的项目全景快照读取服务，复用前执行当前项目与数据集权限复核并只恢复安全事实；扩展人员模块结果携带不透明快照引用；把项目/人员报告任务组装从业务入口提取到独立服务。业务助手入口只选择复用或重查并维持现有 SSE 顺序。

**Tech Stack:** Java 17、Spring Boot 4、MyBatis Plus、MySQL、Jackson、JUnit 5、Mockito、AssertJ、Maven。

---

### Task 1: 安全读取并恢复项目全景快照

**Files:**
- Create: `ai-agent/src/main/java/org/example/ai/agent/business/panorama/ProjectPanoramaSnapshotReuseService.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/business/panorama/model/ProjectPanoramaResult.java`
- Test: `ai-agent/src/test/java/org/example/ai/agent/business/panorama/ProjectPanoramaSnapshotReuseServiceTest.java`

- [ ] **Step 1: 编写失败测试**

测试至少覆盖：有效快照恢复成功；用户/会话/项目不匹配；仅客户端提供快照 ID 不进入服务；过期；查询哈希不匹配；当前方案校验和变化；模块字段策略变化；模块权限复核失败。成功结果必须恢复原模块顺序、状态、快照 ID、display/model 安全事实和问题结论。

- [ ] **Step 2: 运行测试确认 RED**

```powershell
mvn -pl ai-agent -am -Dtest=ProjectPanoramaSnapshotReuseServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：因 `ProjectPanoramaSnapshotReuseService` 尚不存在或复用方法尚未实现而失败。

- [ ] **Step 3: 扩展安全结果模型**

在 `ProjectPanoramaResult.ModuleResult` 中增加安全展示事实、模型事实和字段策略校验和，并保留现有六参数构造器供执行路径和已有测试使用：

```java
public record ModuleResult(
        String datasetCode,
        boolean required,
        DatasetExecutionStatus status,
        boolean dataComplete,
        String snapshotId,
        @JsonIgnore DatasetExecutionResult executionResult,
        Map<String, Object> displayFacts,
        Map<String, Object> modelFacts,
        String fieldPolicyChecksum) {

    public ModuleResult(
            String datasetCode,
            boolean required,
            DatasetExecutionStatus status,
            boolean dataComplete,
            String snapshotId,
            DatasetExecutionResult executionResult) {
        this(datasetCode, required, status, dataComplete, snapshotId, executionResult,
                safeChannel(executionResult, "display"),
                safeChannel(executionResult, "model"),
                executionResult == null ? null
                        : executionResult.source().fieldPolicyChecksum());
    }
}
```

紧凑构造器必须冻结 Map，禁止外部修改；`safeChannel` 只读取已经过滤后的通道。

- [ ] **Step 4: 实现项目全景快照读取服务**

公开接口固定为：

```java
public Optional<ProjectPanoramaResult> reuse(ReuseCommand command)

public record ReuseCommand(
        String agentRunId,
        String userId,
        String sessionId,
        String authorization,
        Map<String, Object> secureContext,
        String selectionToken,
        String panoramaSnapshotId,
        Map<String, Object> canonicalQuery) {}
```

服务必须通过 `ProjectSubjectAuthorizationService` 重新授权项目，通过 `ProjectPanoramaProfileService` 读取当前方案，通过 `BusinessSnapshotAccessService` 逐模块复核当前权限和字段策略。使用现有 Mapper 的 MyBatis Plus Wrapper 读取聚合、模块引用和业务快照；不在 Java 中写 SQL，不在业务助手入口直接访问 Mapper。任何不匹配统一返回 `Optional.empty()`，日志只记录安全分类和数据集编码。

- [ ] **Step 5: 运行测试确认 GREEN**

```powershell
mvn -pl ai-agent -am -Dtest=ProjectPanoramaSnapshotReuseServiceTest,ProjectPanoramaExecutionServiceTest,ProjectPanoramaSnapshotServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：全部通过。

- [ ] **Step 6: 暂存新增类并提交**

```powershell
git add ai-agent/src/main/java/org/example/ai/agent/business/panorama/ProjectPanoramaSnapshotReuseService.java ai-agent/src/main/java/org/example/ai/agent/business/panorama/model/ProjectPanoramaResult.java ai-agent/src/test/java/org/example/ai/agent/business/panorama/ProjectPanoramaSnapshotReuseServiceTest.java
git commit -m "feat(agent): reuse authorized project panoramas"
```

### Task 2: 让人员查询返回安全快照引用

**Files:**
- Modify: `ai-agent/src/main/java/org/example/ai/agent/business/person/PersonSnapshotReuseService.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/business/person/PersonBusinessQueryService.java`
- Modify: `ai-agent/src/test/java/org/example/ai/agent/business/person/PersonSnapshotReuseServiceTest.java`
- Modify: `ai-agent/src/test/java/org/example/ai/agent/business/person/PersonBusinessQueryServiceTest.java`

- [ ] **Step 1: 编写失败测试**

断言新查询和快照复用都返回相同结构的安全引用；成功/空数据/复用状态必须带快照 ID 与字段策略校验和，失败/超时/拒绝不得带引用。断言 `toString()` 不包含快照 ID、工号、认证或事实值。

- [ ] **Step 2: 运行测试确认 RED**

```powershell
mvn -pl ai-agent -am -Dtest=PersonSnapshotReuseServiceTest,PersonBusinessQueryServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：因 `ReuseResult`、`ModuleData` 和 `ModuleResult` 尚无安全引用字段而失败。

- [ ] **Step 3: 返回复用快照引用**

`PersonSnapshotReuseService.ReuseResult` 调整为：

```java
public record ReuseResult(
        String snapshotId,
        String fieldPolicyChecksum,
        boolean dataComplete,
        Map<String, Object> calculation) {}
```

快照 ID 和校验和来自已完成全部归属、权限、配置、策略、查询及有效期校验的 `BusinessSnapshot`。`toString()` 只输出是否存在引用和事实数量。

- [ ] **Step 4: 贯通新建快照引用**

`PersonBusinessQueryService.executeModule(...)` 保存 `BusinessSnapshotService.create(...)` 返回实体并取得 `snapshotId`、`fieldPolicyChecksum`。模块返回契约调整为：

```java
public record ModuleResult(
        DatasetType type,
        String datasetCode,
        ModuleStatus status,
        boolean complete,
        String snapshotId,
        String fieldPolicyChecksum) {}
```

校验成功类状态与快照引用同时存在，失败类状态与引用同时不存在。人员工号和认证上下文不进入返回对象。

- [ ] **Step 5: 运行测试确认 GREEN**

```powershell
mvn -pl ai-agent -am -Dtest=PersonSnapshotReuseServiceTest,PersonBusinessQueryServiceTest,BoundedPersonFanOutServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：全部通过，且多人有界扇出原有汇总行为不变。

- [ ] **Step 6: 提交**

```powershell
git add ai-agent/src/main/java/org/example/ai/agent/business/person/PersonSnapshotReuseService.java ai-agent/src/main/java/org/example/ai/agent/business/person/PersonBusinessQueryService.java ai-agent/src/test/java/org/example/ai/agent/business/person/PersonSnapshotReuseServiceTest.java ai-agent/src/test/java/org/example/ai/agent/business/person/PersonBusinessQueryServiceTest.java
git commit -m "feat(agent): expose safe person snapshot references"
```

### Task 3: 接入项目复用和人员报告任务

**Files:**
- Create: `ai-agent/src/main/java/org/example/ai/agent/business/report/BusinessAssistantReportService.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/business/impl/BusinessAssistantServiceImpl.java`
- Modify: `ai-agent/src/test/java/org/example/ai/agent/business/BusinessAssistantServiceTest.java`
- Modify: `ai-agent/src/test/java/org/example/ai/agent/chat/BusinessAssistantStreamIntegrationTest.java`

- [ ] **Step 1: 编写失败的业务入口测试**

覆盖以下行为：

```text
服务端继承有效 panoramaSnapshotId + 非刷新 -> 复用，ProjectPanoramaExecutionService 调用 0 次
服务端继承有效 panoramaSnapshotId + 刷新 -> 不复用，执行全景 1 次
客户端 extra 伪造 panoramaSnapshotId -> 不复用
人员查询 + PDF -> 创建 CompositeReportTask 并返回 ArtifactBlock
人员某章节 DENIED/FAILED -> 报告章节保持对应安全终态且 dataComplete=false
模型失败 -> 确定性事实和 ArtifactBlock 仍保留
```

- [ ] **Step 2: 运行测试确认 RED**

```powershell
mvn -pl ai-agent -am -Dtest=BusinessAssistantServiceTest,BusinessAssistantStreamIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：项目追问仍执行全景，人员报告仍返回失败占位。

- [ ] **Step 3: 提取报告任务组装服务**

`BusinessAssistantReportService` 仅暴露两个方法：

```java
public ArtifactBlock createProjectReport(ProjectReportCommand command)

public ArtifactBlock createPersonReport(PersonReportCommand command)
```

两个方法都必须使用当前选择令牌解析稳定主体 ID，基于实际模块快照引用构造模板章节，调用 `BusinessReportPlanService.plan(...)`，最后调用 `CompositeReportTaskService.create(...)`。不得直接插入报告表，不得把项目编码或脱敏工号当作稳定主体 ID。

- [ ] **Step 4: 在业务入口选择复用或重查**

`handleProject(...)` 只从以下来源读取项目聚合快照 ID：

```java
String panoramaSnapshotId = trustedInheritedText(request, "panoramaSnapshotId");
```

当 `intent.refresh()` 为 false 且该值存在时调用复用服务；返回空结果才调用 `panoramaExecutionService.execute(...)`。`handlePerson(...)` 在指定格式时调用人员报告方法，删除原“人员组合报告缺少安全快照引用”占位逻辑。

`projectDatasets(...)` 使用 `ModuleResult.displayFacts()` 和 `modelFacts()`；报告章节使用 `ModuleResult.fieldPolicyChecksum()`，不依赖仅存在于即时执行内存的原始执行对象。

- [ ] **Step 5: 保持 SSE 顺序并运行聚焦测试**

```powershell
mvn -pl ai-agent -am -Dtest=BusinessAssistantServiceTest,BusinessAssistantStreamIntegrationTest,ProjectPanoramaSnapshotReuseServiceTest,PersonBusinessQueryServiceTest,CompositeReportTaskServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：context snapshot 早于 status/facts/model/artifact，sequence 单调，最终 checksum 正确，全部测试通过。

- [ ] **Step 6: 显式暂存新增后台类并提交**

```powershell
git add ai-agent/src/main/java/org/example/ai/agent/business/report/BusinessAssistantReportService.java ai-agent/src/main/java/org/example/ai/agent/business/impl/BusinessAssistantServiceImpl.java ai-agent/src/test/java/org/example/ai/agent/business/BusinessAssistantServiceTest.java ai-agent/src/test/java/org/example/ai/agent/chat/BusinessAssistantStreamIntegrationTest.java
git commit -m "feat(agent): complete business follow-up reports"
```

### Task 4: 完整验证与阶段收口

**Files:**
- Verify only; no planned production file changes.

- [ ] **Step 1: 运行差异检查**

```powershell
git diff 6bbe516 --check
git status --short
```

预期：无空白错误，只包含本计划列出的后端、测试和计划文档。

- [ ] **Step 2: 运行独占完整测试**

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17.0.19'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
$env:MODEL_CONFIG_ENCRYPTION_KEY=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('0123456789abcdef0123456789abcdef'))
mvn -pl ai-agent -am clean test
```

预期：Reactor `BUILD SUCCESS`。禁止同时启动第二个 Maven 进程写入同一 `target`。

- [ ] **Step 3: 进行规格和质量双重评审**

规格评审必须逐条核对本设计第 6 节；质量评审检查权限失败关闭、敏感字段、Mapper 边界、中文注释、类职责和无关改动。评审问题必须修复并复审通过。

- [ ] **Step 4: 确认阶段边界**

最终报告必须明确 Task17.1 已完成但 Task17 仍有 Task17.2 部门契约与 Task17.3 动态数据集依赖闭包，不得宣布总体业务助手全部完成，也不得进入 Task18。
