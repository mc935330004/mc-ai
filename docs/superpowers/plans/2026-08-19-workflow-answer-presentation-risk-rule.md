# 工作流文字问答、风险规则与上下文连续性 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在保留现有 `ReportSchema` 报表链路的同时，为工作流增加纯文字真实 SSE 问答、确定性风险判定、连续上下文、SPA 跨页面继续生成和主动终止。

**Architecture:** `GraphSpec` 保存展示模式和风险规则，并随工作流版本发布；工作流执行与安全字段投影保持不变，之后由展示策略分流到 `ANSWER` 或 `REPORT`。`ANSWER` 先输出后端确定性事实和规则结论，再直接消费现有 Reactor 模型流；前端用 Vue `reactive` 模块单例持有运行任务，不引入 Pinia/Vuex。

**Tech Stack:** Java 17、Spring Boot 4、Spring AI、Reactor `Flux`、MyBatis Plus、MySQL、Vue 3、Element Plus、Fetch SSE、Markdown-it、DOMPurify。

---

## 执行约束

- 后台阶段：Codex 输出完整代码、准确路径和替换位置，用户手工落地；Codex 随后只读取并检查用户修改结果。
- 前端阶段：Codex 直接修改 `D:/TraeProject/enterprise-vue-admin`。
- 所有新增或修改代码写清楚中文注释，但注释中不添加“中文注释”四个字。
- 能替换的旧逻辑直接替换；不为兼容本阶段代码保留重复调用链。
- 按用户要求，Codex 不运行 Maven/npm 编译、测试或验收；每阶段只执行 `rg`、`git diff --check` 和代码调用链静态检查，功能验收由用户完成。
- 本方案不新增数据库表、不修改 Flyway：风险规则保存在工作流发布快照，判定审计复用 `ai_run_step`。

## 文件结构映射

### 后台新增文件

- `ai-common/src/main/java/org/example/ai/agent/common/enums/WorkflowPresentationMode.java`：展示模式枚举。
- `ai-agent/src/main/java/org/example/ai/agent/workflow/answer/presentation/WorkflowAnswerPolicy.java`：展示模式和本轮明确意图的判定值对象。
- `ai-agent/src/main/java/org/example/ai/agent/workflow/answer/presentation/WorkflowAnswerPolicyResolver.java`：只从实际执行版本解析展示模式。
- `ai-agent/src/main/java/org/example/ai/agent/workflow/answer/text/WorkflowTextFacts.java`：确定性事实、汇总、对象集合和数据完整性。
- `ai-agent/src/main/java/org/example/ai/agent/workflow/answer/text/WorkflowTextFactBuilder.java`：从安全投影构建事实，不调用模型。
- `ai-agent/src/main/java/org/example/ai/agent/workflow/answer/text/WorkflowTextAnswerService.java`：提示词、真实模型流、降级文本和消息保存。
- `ai-agent/src/main/java/org/example/ai/agent/chat/support/ActiveAgentRunRegistry.java`：进程内活动任务注册与终止。
- `ai-agent/src/main/java/org/example/ai/agent/graph/model/risk/WorkflowRiskRuleSpec.java`：单条风险规则。
- `ai-agent/src/main/java/org/example/ai/agent/graph/model/risk/WorkflowRiskConditionSpec.java`：扁平条件。
- `ai-agent/src/main/java/org/example/ai/agent/workflow/answer/risk/WorkflowRiskEvaluation.java`：一次判定结果及脱敏证据。
- `ai-agent/src/main/java/org/example/ai/agent/workflow/answer/risk/WorkflowRiskRuleResolver.java`：从实际执行版本读取风险规则。
- `ai-agent/src/main/java/org/example/ai/agent/workflow/answer/risk/WorkflowRiskRuleEvaluator.java`：确定性规则执行器。
- `ai-agent/src/main/java/org/example/ai/agent/workflow/answer/risk/WorkflowRiskRuleValidator.java`：发布前字段和类型校验。

### 后台修改文件

- `ai-agent/src/main/java/org/example/ai/agent/graph/model/GraphSpec.java`：增加 `presentationMode` 和 `riskRules`。
- `ai-agent/src/main/java/org/example/ai/agent/workflow/snapshot/WorkflowGraphSnapshotFactory.java`：把风险规则校验加入发布校验结果。
- `ai-agent/src/main/java/org/example/ai/agent/workflow/answer/WorkflowAnswerComposer.java`：抽取 ANSWER/REPORT 共用的安全投影与 Artifact 准备。
- `ai-agent/src/main/java/org/example/ai/agent/chat/support/AgentStreamSession.java`：增加真正增量回答的开始、追加和完成方法。
- `ai-agent/src/main/java/org/example/ai/agent/chat/service/AgentOrchestrator.java`：增加终止方法。
- `ai-agent/src/main/java/org/example/ai/agent/chat/service/impl/DefaultAgentOrchestrator.java`：展示分流、活动任务、文字链路和终止状态。
- `ai-agent/src/main/java/org/example/ai/agent/chat/controller/AgentChatController.java`：增加终止接口。
- `ai-agent/src/main/java/org/example/ai/agent/trace/service/RunTraceService.java` 及实现：增加 `CANCELLED` 状态写入。
- `ai-agent/src/main/java/org/example/ai/agent/workflow/answer/trace/WorkflowAnswerTraceRecorder.java`：复用 `ai_run_step` 保存规则判定摘要。
- `ai-agent/src/main/java/org/example/ai/agent/chat/memory/model/BusinessConversationState.java`：增加展示顺序、风险集合和上次展示模式。
- `ai-agent/src/main/java/org/example/ai/agent/chat/memory/service/ConversationStateRecorder.java`：合并而不是无条件覆盖结构化状态。
- `ai-agent/src/main/java/org/example/ai/agent/chat/memory/service/ConversationContextResolver.java`：增加确定性指代解析和模型失败降级。
- `ai-agent/src/main/java/org/example/ai/agent/chat/entity/AgentRequest.java`：接收服务端注入的聚焦对象、风险对象和上次展示模式。

### 前端新增文件

- `D:/TraeProject/enterprise-vue-admin/src/composables/useAiTaskManager.js`：全局任务 Map、SSE 所有权和终止操作。
- `D:/TraeProject/enterprise-vue-admin/src/components/AiChat/AiRunningTaskIndicator.vue`：跨页面运行提示与终止按钮。
- `D:/TraeProject/enterprise-vue-admin/src/views/knowledge/workflow/components/WorkflowRiskRuleConfigDialog.vue`：扁平风险规则编辑器。

### 前端修改文件

- `D:/TraeProject/enterprise-vue-admin/src/api/agentChat.js`：增加终止接口。
- `D:/TraeProject/enterprise-vue-admin/src/utils/workflowGraph.js`：规范化展示模式和风险规则。
- `D:/TraeProject/enterprise-vue-admin/src/views/knowledge/workflow/studio.vue`：工作流展示模式选择和风险规则入口。
- `D:/TraeProject/enterprise-vue-admin/src/views/knowledge/AiChat/index.vue`：把请求所有权交给全局任务管理器，页面卸载不再取消。
- `D:/TraeProject/enterprise-vue-admin/src/layout/index.vue`：挂载轻量运行提示。

---

### Task 1：工作流展示模式与兼容分流

**Files:**

- Create: `ai-common/src/main/java/org/example/ai/agent/common/enums/WorkflowPresentationMode.java`
- Create: `ai-agent/src/main/java/org/example/ai/agent/workflow/answer/presentation/WorkflowAnswerPolicy.java`
- Create: `ai-agent/src/main/java/org/example/ai/agent/workflow/answer/presentation/WorkflowAnswerPolicyResolver.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/graph/model/GraphSpec.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/chat/service/impl/DefaultAgentOrchestrator.java`
- Modify: `D:/TraeProject/enterprise-vue-admin/src/utils/workflowGraph.js`
- Modify: `D:/TraeProject/enterprise-vue-admin/src/views/knowledge/workflow/studio.vue`

- [ ] **Step 1：后台增加展示模式枚举**

```java
package org.example.ai.agent.common.enums;

/**
 * 工作流默认展示方式。
 */
public enum WorkflowPresentationMode {
    AUTO,
    ANSWER,
    REPORT
}
```

- [ ] **Step 2：在 `GraphSpec` 根级增加可选字段**

```java
/**
 * 工作流默认展示方式。
 * 旧发布快照缺少该字段时，由解析器按 REPORT 兼容。
 */
private WorkflowPresentationMode presentationMode;
```

不要把该字段放进 `ReportDefinitionSpec`，否则没有报告配置的文字工作流无法使用。

- [ ] **Step 3：实现发布版本策略解析**

`WorkflowAnswerPolicyResolver.resolve(outcome)` 必须使用：

```java
PublishedWorkflow workflow = snapshotResolver.resolveExactVersion(
        outcome.workflowCode(),
        outcome.versionId()
);
GraphSpec graph = graphSpecParser.parse(workflow.version().getSnapshotJson());
```

返回规则固定为：

```java
WorkflowPresentationMode configured = graph.getPresentationMode();
WorkflowPresentationMode compatible = configured == null
        ? WorkflowPresentationMode.REPORT
        : configured;
return new WorkflowAnswerPolicy(compatible);
```

禁止读取 `WorkflowDefinition.graphSpecJson` 草稿。

- [ ] **Step 4：实现确定性意图覆盖**

在策略类中只识别明确表达：

```java
REPORT_MARKERS = List.of("完整报表", "生成报表", "完整明细", "导出报表");
ANSWER_MARKERS = List.of("简单说明", "总结一下", "有哪些风险", "是否异常");
```

判定顺序：明确 `REPORT` → 明确 `ANSWER` → 配置值 → `AUTO` 落到 `ANSWER`。不调用模型决定展示类型。

- [ ] **Step 5：前端保存工作流根级配置**

`workflowGraph.js` 规范化：

```javascript
presentationMode: ['AUTO', 'ANSWER', 'REPORT'].includes(source.presentationMode)
  ? source.presentationMode
  : null
```

`studio.vue` 增加单个下拉框，标签“默认回答方式”，选项“智能选择/文字问答/完整报表”。旧草稿值为空时只显示“兼容旧报表”，不能在未保存时自动写成 `AUTO`。

- [ ] **Step 6：静态检查**

```powershell
rg -n "presentationMode|WorkflowPresentationMode" ai-common ai-agent D:\TraeProject\enterprise-vue-admin\src
git diff --check
```

预期：字段只位于 `GraphSpec` 根级；运行时只读取发布版本；没有改动 `ReportSchema`。

- [ ] **Step 7：阶段说明**

说明本阶段实现展示策略配置与旧版本兼容，下一阶段进入文字回答和真实 SSE。后台落地由用户验收，不运行编译测试。

---

### Task 2：确定性事实与真实文字 SSE

**Files:**

- Create: `ai-agent/src/main/java/org/example/ai/agent/workflow/answer/text/WorkflowTextFacts.java`
- Create: `ai-agent/src/main/java/org/example/ai/agent/workflow/answer/text/WorkflowTextFactBuilder.java`
- Create: `ai-agent/src/main/java/org/example/ai/agent/workflow/answer/text/WorkflowTextAnswerService.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/workflow/answer/WorkflowAnswerComposer.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/chat/support/AgentStreamSession.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/chat/service/impl/DefaultAgentOrchestrator.java`

- [ ] **Step 1：抽取共用准备入口**

把 `prepareReport` 中的安全字段策略、`WorkflowAnswerModelPayload`、分块和 `ResultArtifact` 保存抽为：

```java
public WorkflowAnswerPreparation prepare(
        AgentRequest request,
        WorkflowExecutionOutcome outcome,
        String presentationType)
```

`prepareReport()` 只以 `REPORT` 调用该入口；文字链路以 `ANSWER` 调用。两条链路共用安全投影，但不共用展示对象。

- [ ] **Step 2：定义确定性事实对象**

```java
public record WorkflowTextFacts(
        String deterministicMarkdown,
        List<String> displayObjectIds,
        List<String> riskObjectIds,
        List<String> unknownObjectIds,
        Object safeModelInput,
        boolean dataComplete) {
}
```

`WorkflowTextFactBuilder` 只读取 `WorkflowAnswerPreparation.modelPayload()` 和字段语义；管理查询汇总总数、可聚合金额及异常集合，精确项目查询保留核心字段。不得把原始业务响应或隐藏字段重新放入模型输入。

- [ ] **Step 3：为 `AgentStreamSession` 增加真正增量 API**

新增三个同步方法：

```java
public synchronized void startAnswer(String presentationType);
public synchronized void appendAnswerDelta(String content);
public synchronized void finishAnswer(String finalContent);
```

行为要求：

- `startAnswer` 只发送一次 `ANSWER_START`。
- `appendAnswerDelta` 原样发送当前已到达的模型片段并累计完整内容。
- `finishAnswer` 发送 `ANSWER_SNAPSHOT`、`ANSWER_DONE`，再完成 emitter。
- 现有 `publishAnswer()` 保留给旧同步链路，但新文字链路禁止调用它分块伪装流式。

- [ ] **Step 4：实现模型真实流式服务**

核心调用固定为：

```java
ModelCallContext context = ModelCallContext.builder()
        .runId(runId)
        .conversationId(request.getConversationId())
        .userId(request.getUserId())
        .modelCode(request.getModelCode())
        .callType(ModelCallType.ANSWER)
        .build();

chatClientService.stream(context, SYSTEM_PROMPT, userPrompt)
        .map(response -> extractDelta(response, answer))
        .filter(StringUtils::hasText)
        .doOnNext(delta -> {
            answer.append(delta);
            stream.appendAnswerDelta(delta);
        })
        .blockLast();
```

`extractDelta` 必须同时兼容模型返回“当前片段”和“截至当前的累计文本”：如果新文本以已累计内容开头，只追加其后缀，否则按当前片段追加，避免重复回答。

系统提示只允许段落、粗体和一级列表，禁止表格、HTML、CSS、嵌套列表；明确告诉模型：事实、金额和风险结论已经由后端确定，只能解释和给建议。

- [ ] **Step 5：先发送事实，再追加模型解释**

顺序必须是：

```java
stream.startAnswer("MARKDOWN");
stream.appendAnswerDelta(facts.deterministicMarkdown());
```

模型失败时追加：

```text

智能分析暂时不可用，以上业务数据和规则判定结果仍然有效。
```

随后保存完整助手消息并调用 `finishAnswer`。工作流失败时沿用现有普通错误回答，不能进入本服务。

- [ ] **Step 6：在编排器中分流**

`executeWorkflowQuery()` 在 `outcome.success()` 后解析展示决策：

```java
if (presentationDecision == WorkflowPresentationMode.ANSWER) {
    executeWorkflowTextAnswer(request, stream, runId, plan, outcome);
    return outcome;
}
```

原 `REPORT_BASE → REPORT_DONE` 代码保持原位，只在 `REPORT` 分支执行。

- [ ] **Step 7：静态检查**

```powershell
rg -n "TrackedChatClientService\.stream|blockLast|appendAnswerDelta|REPORT_BASE" ai-agent\src\main\java
git diff --check
```

预期：文字链路直接消费模型 Flux；`ANSWER` 分支没有构造 `ReportSchemaVO`；模型失败仍保存确定性事实。

---

### Task 3：后台主动终止与运行状态

**Files:**

- Create: `ai-agent/src/main/java/org/example/ai/agent/chat/support/ActiveAgentRunRegistry.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/chat/service/AgentOrchestrator.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/chat/service/impl/DefaultAgentOrchestrator.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/chat/controller/AgentChatController.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/trace/service/RunTraceService.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/trace/service/impl/RunTraceServiceImpl.java`

- [ ] **Step 1：增加进程内活动任务注册表**

注册项必须绑定 `runId + userId + conversationId + Future<?>`。公开方法限定为：

```java
public void register(String runId, String userId, String conversationId, Future<?> task);
public boolean cancel(String runId, String userId, String conversationId);
public void remove(String runId);
```

`cancel` 必须同时匹配当前用户和会话，成功后执行 `future.cancel(true)` 并移除；禁止只凭 `runId` 终止他人任务。

- [ ] **Step 2：让聊天执行任务可取消**

`chat()` 使用 `FutureTask<Void>` 包装 `doChat()`，注册后交给现有 `agentChatExecutor.execute(task)`。`doChat` 的 `finally` 调用 `activeAgentRunRegistry.remove(runId)`。

- [ ] **Step 3：增加终止接口**

```java
@PostMapping("/sessions/{conversationId}/runs/{runId}/cancel")
public Result<Boolean> cancelRun(
        @PathVariable String conversationId,
        @PathVariable String runId) {
    String userId = currentUserProvider.getRequiredUserId();
    return Result.success(agentOrchestrator.cancel(userId, conversationId, runId));
}
```

- [ ] **Step 4：区分主动终止、断连和失败**

`RunTraceService` 增加 `markCancelled`，写入主记录状态 `CANCELLED`。捕获 `InterruptedException`、Reactor 取消或线程中断时：

- 保留已经累计的文字。
- 助手消息追加“回答已由用户终止”。
- 不调用 `markSuccess`，不记录模型连接失败。
- 完成 SSE 前先检查连接是否仍可写。

- [ ] **Step 5：静态检查**

```powershell
rg -n "ActiveAgentRunRegistry|markCancelled|/cancel|cancel\(true\)" ai-agent\src\main\java
git diff --check
```

预期：终止接口校验用户和会话；任务结束必清理；没有 Redis 或新数据表。

---

### Task 4：前端跨页面继续与随时终止

**Files:**

- Create: `D:/TraeProject/enterprise-vue-admin/src/composables/useAiTaskManager.js`
- Create: `D:/TraeProject/enterprise-vue-admin/src/components/AiChat/AiRunningTaskIndicator.vue`
- Modify: `D:/TraeProject/enterprise-vue-admin/src/api/agentChat.js`
- Modify: `D:/TraeProject/enterprise-vue-admin/src/views/knowledge/AiChat/index.vue`
- Modify: `D:/TraeProject/enterprise-vue-admin/src/layout/index.vue`

- [ ] **Step 1：增加终止 API**

```javascript
export function cancelChatRun(conversationId, runId) {
  return request({
    url: `/api/agent/chat/sessions/${encodeURIComponent(conversationId)}/runs/${encodeURIComponent(runId)}/cancel`,
    method: 'post'
  })
}
```

- [ ] **Step 2：建立模块级任务 Map**

任务结构固定为：

```javascript
{
  key: conversationId,
  conversationId,
  runId: '',
  status: 'RUNNING',
  message,
  controller,
  promise,
  stopRequested: false
}
```

composable 提供：

```javascript
startTask(conversationId, message, runner)
findTask(conversationId)
listRunningTasks()
stopTask(conversationId)
removeTask(conversationId)
```

`runner` 继续使用现有 SSE 解析逻辑，但 `AbortController` 和 Promise 必须属于任务对象，而不是页面局部变量。

- [ ] **Step 3：改造聊天页面所有权**

- 删除页面级 `let abortController = null`。
- 删除提交前无条件 `cancelCurrentRequest()`。
- `onUnmounted` 只解除页面引用，禁止调用 abort。
- 切换会话时，把对应运行任务中的 `message` 合并到历史消息末尾；以 `runId` 去重。
- 当前会话是否 loading 由 `findTask(activeSessionId)?.status === 'RUNNING'` 计算。

- [ ] **Step 4：主动终止顺序**

`stopTask` 在已有 `runId` 时先调用后端终止 API；后端响应后再 `controller.abort()`。若首个 SSE 事件尚未返回 `runId`，按钮显示“正在建立连接”，暂不允许误当成后台已终止。

- [ ] **Step 5：全局运行提示**

`AiRunningTaskIndicator.vue` 仅显示“AI 正在回答 · N”和终止按钮，不复制聊天内容。挂载到 `layout/index.vue`，通过 composable 读取运行任务；点击后调用 `stopTask`。

- [ ] **Step 6：静态检查**

```powershell
rg -n "onUnmounted|abortController|useAiTaskManager|cancelChatRun" D:\TraeProject\enterprise-vue-admin\src
git -C D:\TraeProject\enterprise-vue-admin diff --check
```

预期：`AiChat/index.vue` 卸载不再 abort；全局任务拥有连接；切换页面和会话不清除增量消息。

---

### Task 5：结构化上下文记忆

**Files:**

- Modify: `ai-agent/src/main/java/org/example/ai/agent/chat/memory/model/BusinessConversationState.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/chat/entity/AgentRequest.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/chat/memory/service/ConversationStateRecorder.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/chat/memory/service/ConversationContextResolver.java`

- [ ] **Step 1：扩展 JSON 状态而不改表**

新增字段：

```java
private List<String> displayObjectIds = new ArrayList<>();
private List<String> riskObjectIds = new ArrayList<>();
private List<String> unknownObjectIds = new ArrayList<>();
private String focusedObjectId;
private String lastPresentationMode;
private String riskEvaluationRunId;
private LocalDateTime updatedAt;
```

这些字段保存在现有 `state_json`，不增加数据库字段。

- [ ] **Step 2：保存状态时合并必要上下文**

`recordWorkflowResult` 接收 `WorkflowTextFacts` 和最终展示模式。保存前加载旧状态，只继承仍属于同一工作流、同一业务主题的必要字段；新项目或“重新开始”必须清除旧集合。禁止把整个业务结果写进 `state_json`。

- [ ] **Step 3：增加确定性指代规则**

在调用上下文模型前处理：

- “这个项目”：`focusedObjectId` 存在或对象集合只有一个时继承；多于一个时设置等待澄清。
- “这些风险项目”：继承 `riskObjectIds`。
- “第一个项目”：使用 `displayObjectIds.get(0)`。
- “继续分析”“为什么有风险”：注入 `resultArtifactId + riskEvaluationRunId`。
- “生成完整报表”：继承对象和输入，并把本轮展示意图设为 `REPORT`。

- [ ] **Step 4：修复模型失败即遗忘**

`rewriteService.decide()` 返回空或抛异常时，先执行确定性解析；能够唯一解析则继续，不能唯一解析则生成追问，禁止直接返回 `null` 后重新路由到错误工作流。

- [ ] **Step 5：静态检查**

```powershell
rg -n "displayObjectIds|riskObjectIds|focusedObjectId|riskEvaluationRunId|resolveDeterministic" ai-agent\src\main\java
git diff --check
```

预期：同一会话引用稳定；状态中无完整业务 JSON；新会话不继承。

---

### Task 6：风险规则配置模型、校验、判定和审计

**Files:**

- Create: `ai-agent/src/main/java/org/example/ai/agent/graph/model/risk/WorkflowRiskRuleSpec.java`
- Create: `ai-agent/src/main/java/org/example/ai/agent/graph/model/risk/WorkflowRiskConditionSpec.java`
- Create: `ai-agent/src/main/java/org/example/ai/agent/workflow/answer/risk/WorkflowRiskEvaluation.java`
- Create: `ai-agent/src/main/java/org/example/ai/agent/workflow/answer/risk/WorkflowRiskRuleResolver.java`
- Create: `ai-agent/src/main/java/org/example/ai/agent/workflow/answer/risk/WorkflowRiskRuleEvaluator.java`
- Create: `ai-agent/src/main/java/org/example/ai/agent/workflow/answer/risk/WorkflowRiskRuleValidator.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/graph/model/GraphSpec.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/workflow/snapshot/WorkflowGraphSnapshotFactory.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/workflow/answer/trace/WorkflowAnswerTraceRecorder.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/workflow/answer/text/WorkflowTextFactBuilder.java`

- [ ] **Step 1：定义扁平规则协议**

```java
public record WorkflowRiskRuleSpec(
        String code,
        String name,
        RiskSeverity severity,
        RiskLogic logic,
        Long objectKeyFieldId,
        String objectPath,
        boolean enabled,
        List<WorkflowRiskConditionSpec> conditions) {

    public enum RiskSeverity { LOW, MEDIUM, HIGH }
    public enum RiskLogic { AND, OR }
}

public record WorkflowRiskConditionSpec(
        Long leftFieldId,
        String leftPath,
        RiskOperator operator,
        RiskRightType rightType,
        String constantValue,
        Long rightFieldId,
        String rightPath,
        BigDecimal multiplier) {

    public enum RiskOperator {
        EQ, NE, GT, GTE, LT, LTE,
        CONTAINS, NOT_CONTAINS,
        IS_NULL, NOT_NULL,
        IN, NOT_IN
    }

    public enum RiskRightType { CONSTANT, FIELD }
}
```

`objectPath`、`leftPath`、`rightPath` 由前端字段选择自动生成并只读；用户只选择判断对象、字段、运算符和值。条件字段必须属于同一对象数组作用域。

- [ ] **Step 2：发布校验**

校验内容必须明确返回 GraphSpec 路径：

- 规则 code 在当前工作流内唯一。
- 已启用规则至少一个条件。
- `objectKeyFieldId` 和条件 fieldId 属于当前工作流已发布字段字典。
- 数值比较只能用于数字字段/可转换常量。
- `IS_NULL/NOT_NULL` 不允许右值。
- 字段右值必须与左值类型兼容。
- 条件路径必须与字典机器字段及同一对象作用域一致。
- 每条规则最多 10 个条件，工作流最多 30 条规则。

运行时由 `WorkflowRiskRuleResolver` 使用 `WorkflowRuntimeSnapshotResolver.resolveExactVersion()` 和 `GraphSpecParser` 读取本次实际执行版本中的 `riskRules`，禁止读取工作流草稿。

- [ ] **Step 3：确定性三态判定**

`WorkflowRiskRuleEvaluator.evaluate(...)` 对每个对象逐条执行规则：

- 路径不存在、值为空但运算符不是空值判断、数字转换失败 → `UNKNOWN`。
- 条件满足 → `MATCHED`。
- 数据完整且条件不满足 → `NOT_MATCHED`。
- `AND/OR` 只组合有效条件；任一必要条件 `UNKNOWN` 时不得输出无风险。
- 金额全部使用 `BigDecimal.compareTo`，禁止 `double`。

- [ ] **Step 4：复用运行步骤审计**

在 `WorkflowAnswerTraceRecorder` 增加 `WORKFLOW_RISK_EVALUATION` 步骤类型。`outputJson` 只保存规则编码、工作流版本 ID、对象编码、状态、字段名称、脱敏证据显示值和原因；不保存原始接口响应。

- [ ] **Step 5：接入文字事实**

`WorkflowTextFactBuilder` 将：

- `MATCHED` 对象加入 `riskObjectIds`，并按严重级别排序。
- `UNKNOWN` 对象加入 `unknownObjectIds`，列出缺失字段。
- 没有启用规则时明确生成“当前未配置风险判定规则”。
- 正常项目只计数，不逐条列出。

- [ ] **Step 6：静态检查**

```powershell
rg -n "WorkflowRiskRuleSpec|MATCHED|NOT_MATCHED|UNKNOWN|WORKFLOW_RISK_EVALUATION|BigDecimal" ai-agent\src\main\java
rg -n "CREATE TABLE|ALTER TABLE" ai-agent\src\main\resources\db\migration
git diff --check
```

预期：风险功能没有数据库结构变更；AI 不参与状态判定；缺字段不会被判为无风险。

---

### Task 7：前端傻瓜式风险规则配置

**Files:**

- Create: `D:/TraeProject/enterprise-vue-admin/src/views/knowledge/workflow/components/WorkflowRiskRuleConfigDialog.vue`
- Modify: `D:/TraeProject/enterprise-vue-admin/src/utils/workflowGraph.js`
- Modify: `D:/TraeProject/enterprise-vue-admin/src/views/knowledge/workflow/studio.vue`

- [ ] **Step 1：GraphSpec 默认值和清理**

```javascript
riskRules: Array.isArray(source.riskRules)
  ? source.riskRules.map(normalizeRiskRule)
  : []
```

保存时删除 UI 临时字段，只保留后台协议字段；旧工作流缺少 `riskRules` 时保持空数组。

- [ ] **Step 2：增加简单规则弹窗**

界面只展示：

1. 规则名称、严重级别、AND/OR、启用开关。
2. 判断对象字段下拉框，例如“项目编码”。
3. 条件行：左字段、运算符、右值类型、固定值/右字段、倍率。

路径字段不提供手工输入；选择字段后自动填充并隐藏或只读。字段选项复用工作流当前能力的已发布字段字典，不新建字段接口。

- [ ] **Step 3：前端即时校验**

- 左右类型不兼容时阻止“应用规则”。
- `IS_NULL/NOT_NULL` 自动清空右值。
- 选择字段右值时只展示兼容类型字段。
- 条件跨对象数组时提示“条件字段必须属于同一业务对象”。
- 规则编码首次创建时生成稳定值，编辑时不重建。

- [ ] **Step 4：接入工作流工具栏**

在 `studio.vue` 与“报告配置”同级增加“风险规则”，不塞进报告模板弹窗。弹窗只修改当前 `graphSpec.riskRules` 草稿，最终仍由现有保存/发布流程落库和版本化。

- [ ] **Step 5：静态检查**

```powershell
rg -n "riskRules|WorkflowRiskRuleConfigDialog|objectKeyFieldId|multiplier" D:\TraeProject\enterprise-vue-admin\src
git -C D:\TraeProject\enterprise-vue-admin diff --check
```

预期：没有手写 JSONPath、自然语言规则或脚本输入；不影响现有报告配置弹窗。

---

### Task 8：全链路静态复核与最终闭环

**Files:**

- Review only: 上述全部文件。

- [ ] **Step 1：检查两条展示链路隔离**

确认 `ANSWER` 分支不调用 `reportSchemaBuilder.build()`，`REPORT` 分支不调用文字事实渲染器。

- [ ] **Step 2：检查事实和规则边界**

确认模型提示中没有让 AI 决定金额、数量、风险状态或数据完整性；所有风险输出均来自 `WorkflowRiskRuleEvaluator`。

- [ ] **Step 3：检查上下文连续性**

逐项追踪“这个项目、这些风险项目、第一个项目、继续分析、刷新数据、生成完整报表、重新开始”的状态读写位置，确保本轮不会忘记上一轮已确认对象。

- [ ] **Step 4：检查终止与跨页面**

确认页面卸载不 abort；后端终止接口先校验用户/会话；主动终止保存部分文字并标记 `CANCELLED`。

- [ ] **Step 5：检查范围和重复代码**

```powershell
git status --short
git diff --check
git diff --unified=0 -- ai-common ai-agent | rg "TODO|TBD|中文注释|ReportSchema"
git -C D:\TraeProject\enterprise-vue-admin diff --unified=0 | rg "TODO|TBD|中文注释|ReportSchema"
```

只报告与本需求修改文件相关的问题，不用个人格式偏好约束无关旧代码。不运行 Maven/npm 测试或编译。

- [ ] **Step 6：最终闭环总结**

用户完成验收后，明确提示“已最终闭环”，并总结：

- 实现了哪些功能。
- 使用了哪些技术。
- `ANSWER` 与 `REPORT` 的最终边界。
- 上下文、风险判定、SSE、跨页面和终止的实现方式。
- 第一版未包含浏览器刷新重连、跨标签页和多实例 Redis 终止协调。

---

## 规格覆盖检查

- 展示模式与旧版本兼容：Task 1。
- 纯文字真实 SSE、受限 Markdown、模型失败降级：Task 2。
- 后台主动终止：Task 3。
- SPA 页面/会话切换继续生成：Task 4。
- 同一会话结构化上下文和确定性指代：Task 5。
- 精确风险规则、三态判定、证据与审计：Task 6。
- 简单规则配置页面：Task 7。
- 架构边界、静态检查和闭环总结：Task 8。

明确延期项未进入任何实施任务：浏览器刷新重连、事件重放、跨标签页接管、多实例 Redis 终止协调、自然语言规则、RAG 风险判定和脚本规则引擎。
