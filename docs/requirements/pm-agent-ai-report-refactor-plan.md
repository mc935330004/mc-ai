# PM Agent AI Report 重构方案与跨设备协作计划

> 项目：`mc-ai`
>
> 当前阶段：方案确认，尚未开始代码修改
>
> 目标：解决 AI Report 响应慢、样式不稳定、数据较多时拥挤或疑似丢失，并为项目结算、概算、现金流、产值、合同、回款、成本、进度、风险等报告提供可扩展基础。

---

## 1. 最终结论

当前问题的根因不是单纯的 CSS 问题，而是后端让 AI 直接生成 Markdown，导致内容结构、表格列数、换行和链接格式不稳定。

当前响应慢的主要原因是：业务查询完成后，系统还要顺序执行结果分块、模型调用、失败重试和多轮汇总，最终 Markdown 生成完成后才发送主要回答。

推荐的核心改造方向是：

```text
业务数据由后端查询和保存
        ↓
后端生成固定 ReportSchema
        ↓
SSE 立即发送基础报告
        ↓
前端固定 ReportRenderer 渲染
        ↓
AI 异步生成 summary / highlights / warnings
        ↓
SSE 追加结构化分析结果
```

这个方案与当前项目的模块化单体架构兼容，不需要拆分微服务，也不需要重写工作流执行引擎。

---

## 2. 当前项目架构事实

项目是 Maven 多模块结构：

- `ai-common`：公共配置、异常、文件处理、基础设施和通用模型能力
- `ai-rag`：企业知识库、文档、文档版本、文本切片、向量化和 PGVector 检索
- `ai-agent`：Agent、意图路由、能力调用、工作流、运行追踪和回答生成

数据存储：

- MySQL：业务数据和知识库关系型元数据
- PostgreSQL + PGVector：向量数据
- Flyway：数据库迁移

报告相关现有能力：

- `WorkflowExecutionOutcome` 保存工作流结果和批量项目执行摘要
- `ForEachGraphNodeExecutor` 支持多项目循环和并发执行
- `ResultArtifactService` 保存完整结果快照，并校验分块数量、顺序和 SHA-256
- `WorkflowAnswerComposer` 负责字段过滤、结果分块、AI 分析和最终汇总
- `AgentStreamSession` 已支持 SSE v1/v2、事件序号、快照和内容哈希
- `ChatTextPayloadVO` 已支持 `presentationType=REPORT`，但目前没有真正的 ReportSchema

当前仓库没有前端 `ReportRenderer` 源码，因此后端协议可以在本项目完成，页面组件需要在前端项目中配合接入。

---

## 3. 当前调用链和问题定位

```text
POST /api/agent/chat/stream
        ↓
AgentChatController
        ↓
DefaultAgentOrchestrator
        ↓
IntentRouter
        ↓
PlanTemplateRegistry
        ↓
WorkflowExecutionFacade
        ↓
GraphSpecRuntimeExecutor
        ↓
ForEachGraphNodeExecutor
        ↓
WorkflowExecutionOutcome
        ↓
WorkflowAnswerComposer
        ↓
分块模型调用 + 层级汇总模型调用
        ↓
publishAssistantAnswer
        ↓
SSE ANSWER / ANSWER_DELTA
```

### 3.1 响应慢和页面空白

当前 `DefaultAgentOrchestrator.executeWorkflowQuery()` 在工作流执行完成后，会同步调用 `WorkflowAnswerComposer.compose()`，完成以下动作后才发送最终回答：

1. 字段字典查询和字段安全过滤
2. 业务结果 JSON 分块
3. 保存完整 Artifact
4. 每个分块调用模型
5. 分块失败后重试
6. 多轮摘要汇总
7. 生成最终 Markdown

此外，`app.agent.stream.default-version` 当前为 `1`。当前端没有显式发送 `X-Agent-Stream-Version: 2` 时，服务端只发送最终完整答案，不会发送增量答案事件。

### 3.2 样式错乱

当前以下代码仍让 AI 或字符串拼接逻辑生成 Markdown：

- `PlanTemplateRegistry`
- `WorkflowAnswerSummaryReducer`
- `ResultArtifactStatisticsService.renderMarkdown()`
- `DefaultAnswerComposer`

截图中的空表格行、`| - |`、长链接和层级混乱，说明 Markdown 本身不稳定。固定 CSS 无法可靠修复非法 Markdown。

### 3.3 多项目数据疑似丢失

当前 `ForEachGraphNodeExecutor` 的行为是：

- 并发上限为 5
- 并发结果按输入顺序收集
- `processAllItems=true` 时不会因为数量超过 5 而截断
- `processAllItems=false` 且数量超过限制时会直接失败，而不是静默丢弃

同时，`ResultArtifactService` 会保存完整结果并校验完整性。因此目前更可能出现的是“AI 摘要或 Markdown 漏掉了业务记录”，而不是业务查询已经丢失数据。

必须通过以下数据进行定位，不能直接猜测：

1. `WorkflowExecutionOutcome.batches().items`
2. `ai_result_artifact.planned_chunk_count`
3. `ai_result_artifact.stored_chunk_count`
4. Artifact 还原后的完整 JSON
5. 最终 ReportSchema 的行数
6. 最终 Markdown 或前端显示的行数

---

## 4. 目标架构

### 4.1 两种查询类型

#### DATA_QUERY

适用于：

- 查询项目结算
- 查询项目概算
- 查询现金流
- 查询合同
- 查询回款
- 查询成本

流程：

```text
IntentRouter
→ Workflow / Capability 查询
→ 保存完整结果
→ ReportSchemaBuilder
→ REPORT_BASE
→ 前端固定组件渲染
```

原则上不调用 AI。

#### ANALYSIS_REPORT

适用于：

- 分析概算执行情况
- 分析现金流风险
- 分析结算异常
- 分析进度风险

流程：

```text
业务查询
→ ReportSchema
→ REPORT_BASE
→ 前端先展示基础数据
→ 后台 AI 分析
→ REPORT_ANALYSIS_DELTA
```

AI 只生成：

- `summary`
- `highlights`
- `warnings`

AI 不生成：

- HTML
- CSS
- Markdown 表格
- 页面布局
- 业务原始数据

### 4.2 ReportSchema

建议第一版采用以下结构：

```json
{
  "reportId": "run-or-artifact-id",
  "reportType": "PROJECT_SETTLEMENT",
  "queryType": "DATA_QUERY",
  "title": "项目结算信息",
  "subtitle": "",
  "status": "BASE_READY",
  "dataComplete": true,
  "sections": [
    {
      "type": "METRICS",
      "title": "汇总指标",
      "items": []
    },
    {
      "type": "TABLE",
      "title": "项目明细",
      "columns": [],
      "rows": []
    },
    {
      "type": "WARNINGS",
      "title": "数据状态",
      "items": []
    }
  ],
  "analysis": {
    "status": "PENDING",
    "summary": "",
    "highlights": [],
    "warnings": []
  },
  "meta": {
    "totalCount": 0,
    "successCount": 0,
    "failureCount": 0,
    "skippedCount": 0,
    "artifactId": ""
  }
}
```

约束：

- 金额、日期、数量使用类型化值
- 表格列由后端定义
- 行数据不能由 AI 重写
- 保留成功、失败、跳过和总数
- 保留 `dataComplete`
- 保留 Artifact 引用，支持断线恢复和后续追问
- 相同查询的字段顺序和 section 顺序必须稳定

### 4.3 ReportType 的来源

`reportType` 不应由模型自由生成，优先从以下来源确定：

```text
工作流元数据
→ 能力定义
→ 已发布字段字典
→ ReportDefinitionRegistry
```

如果暂时无法判断，使用 `GENERIC_WORKFLOW_REPORT`，不能编造不存在的报告类型。

---

## 5. 分阶段实施计划

## Phase 0：基线验证和数据完整性定位

### 目标

先确认数据究竟在哪一层丢失，并记录当前响应时间和模型调用次数。

### 工作内容

- 选定一个多项目结算查询作为固定样例
- 记录路由耗时、工作流耗时、Artifact 保存耗时、AI 分析耗时
- 对比工作流结果、Artifact、最终答案的项目数和明细数
- 确认前端是否发送 `X-Agent-Stream-Version: 2`
- 确认前端是否消费 `workflow_result` 事件

### 产出

- 一份基线数据
- 一份数据丢失定位结论
- 一份 SSE 当前客户端兼容性结论

### 代码范围

原则上只增加日志、指标或测试，不改变业务行为。

---

## Phase 1：固定 ReportSchema，先展示基础数据

### 目标

先解决样式不稳定、页面空白和业务数据依赖 AI 的问题。

### 预计修改类

| 类 | 修改原因 |
|---|---|
| `IntentResult` | 增加 `reportType` 和 `queryType` 元数据 |
| `RuleBasedIntentRouter` | 增加 DATA_QUERY / ANALYSIS_REPORT 判断 |
| 新增 `ReportSchema` 相关 DTO | 定义统一报告结构 |
| 新增 `ReportSchemaBuilder` | 根据安全业务结果生成固定结构 |
| `ChatTextPayloadVO` | 增加 `reportSchema`，保留旧字段兼容 |
| `AgentStreamEventType` | 增加报告基础数据事件 |
| `AgentStreamSession` | 支持发送结构化 ReportSchema |
| `DefaultAgentOrchestrator` | 工作流完成后先发送基础报告 |
| 相关测试类 | 验证字段稳定性和数据完整性 |

### 目标流程

```text
WorkflowExecutionFacade.execute()
→ ReportSchemaBuilder.build()
→ 保存 Artifact 和基础消息
→ REPORT_BASE
→ 前端固定渲染
```

### 重要边界

- 不删除 `WorkflowAnswerComposer`
- 不删除 Artifact 机制
- 不修改 Graph 执行引擎
- 不让 ReportSchemaBuilder 直接访问业务数据库
- 只使用 `WorkflowExecutionOutcome` 和字段字典提供的数据

---

## Phase 2：AI 分析异步化

### 目标

基础数据展示后，后台异步生成分析，不阻塞首屏。

### 调整方式

将 `WorkflowAnswerComposer` 拆成两个逻辑阶段：

```text
prepareReport()
    ├─ 字段安全过滤
    ├─ Artifact 保存
    └─ ReportSchema 生成

analyzeReport()
    ├─ 读取已保存 Artifact
    ├─ 生成摘要
    ├─ 生成重点
    └─ 生成风险
```

AI 分析失败时：

- 基础报告仍然可用
- 报告状态为 `ANALYSIS_FAILED`
- 不重新请求业务系统
- 用户可以基于已有 Artifact 再次分析

### SSE 事件

建议增加：

```text
REPORT_START
REPORT_BASE
REPORT_ANALYSIS_START
REPORT_ANALYSIS_DELTA
REPORT_DONE
```

`REPORT_ANALYSIS_DELTA` 只更新 `analysis` 字段，不发送整张 Markdown。

---

## Phase 3：历史恢复和断线恢复

### 目标

刷新页面、SSE 断开或换设备后，仍然可以恢复完整报告。

### 预计修改

- `AiChatSessionService` 增加按 `userId + sessionId + runId` 更新报告载荷的方法
- `AiChatSessionServiceImpl` 更新同一条助手消息的 `payloadJson`
- `ChatTextPayloadVO` 保存完整 `ReportSchema`
- 继续复用 `resultArtifactId`
- 历史消息接口返回报告状态和结构化载荷

### 推荐持久化状态

```text
BASE_READY
ANALYSIS_PENDING
ANALYSIS_COMPLETED
ANALYSIS_FAILED
PARTIAL_DATA
```

---

## Phase 4：前端固定渲染和大数据体验

该阶段需要前端项目配合，当前 `mc-ai` 仓库没有前端源码。

### 前端组件建议

- `ReportRenderer`
- `MetricCardSection`
- `ReportTableSection`
- `ProjectGroupSection`
- `WarningSection`
- `ReportAnalysisSection`

### 多项目展示建议

- 项目分组折叠
- 汇总指标固定在顶部
- 明细表格支持横向滚动
- 表头固定
- 大数据量时使用虚拟列表
- 失败和跳过项目单独展示
- 不使用 Markdown 解析业务表格
- 行数过多时再引入分页，不在第一阶段提前设计复杂分页服务

---

## 6. 兼容性策略

### 后端兼容

- 保留 `MARKDOWN` 展示方式
- 保留现有 `ANSWER` / `ANSWER_DELTA`
- 新增 `REPORT_*` 事件
- `ChatTextPayloadVO` 新增字段，不删除旧字段
- 老客户端继续使用旧答案
- 新客户端使用 ReportSchema

### SSE 版本

前端未确认支持 v2 前，不直接修改默认协议。可以先使用：

```http
X-Agent-Stream-Version: 2
```

进行灰度验证。确认兼容后，再考虑修改：

```yaml
app.agent.stream.default-version: 2
```

---

## 7. 验证标准

### 数据完整性

- 两个项目查询，项目数量完整
- 多项目中一个成功、一个失败时，成功数据仍完整
- 跳过项目有明确状态
- 输入顺序和报告顺序一致
- Artifact 分块数量等于计划数量
- Artifact SHA-256 校验通过
- ReportSchema 行数与 Artifact 还原数据一致

### 性能

- `REPORT_BASE` 不等待 AI 汇总
- 记录首个基础报告事件耗时
- 记录最终分析完成耗时
- 记录模型调用次数和重试次数
- DATA_QUERY 不调用分析模型

### 样式稳定

- 同一份数据重复生成，字段顺序一致
- 表格列固定
- 空值、金额、日期格式稳定
- 长文件名和链接不会破坏布局
- 不再依赖 Markdown 解析表格

### 恢复能力

- SSE 断开后历史消息可恢复
- 页面刷新后报告仍可展示
- AI 分析失败不影响基础数据
- 后续追问仍可复用 Artifact

---

## 8. 不建议做的事情

当前阶段不建议：

- 拆分微服务
- 引入消息队列
- 重写工作流执行引擎
- 删除现有 Artifact 机制
- 让 AI 生成完整 ReportSchema
- 让前端继续猜测 Markdown 报告类型
- 一次性实现所有业务报告
- 为未来需求提前增加复杂抽象
- 顺手进行无关模块重构

---

## 9. 风险和未确认事项

### 已确认风险

- 当前前端源码不在本仓库，无法直接修改和验证 ReportRenderer
- 当前默认 SSE 版本为 v1，客户端需要确认是否支持 v2
- 当前工作区存在未提交修改，实施时必须保留并避开无关格式化

### 需要在 Phase 0 确认

- 多项目数据是在工作流结果、Artifact、AI 摘要还是前端渲染层丢失
- 当前前端是否消费 `workflow_result`
- 当前前端是否能接入 `REPORT_BASE` 和 `REPORT_ANALYSIS_DELTA`
- 当前工作流的 `processAllItems` 配置是否符合业务预期

### 额外安全事项

当前 `application.yml` 中存在明文外部服务密钥和 Token 加密密钥。这与本次报告重构无直接关系，建议单独迁移到环境变量或密钥管理系统，不与本次功能改造混合处理。

---

## 10. 跨设备同步需求和进度的推荐方法

### 推荐方案：Git + 项目文档 + 进度日志

不要只依赖桌面上的单个 Markdown 文件。最稳妥的方式是把需求和进度纳入项目版本管理：

```text
项目仓库
├─ AGENTS.md
├─ docs/requirements/pm-agent-ai-report.md
├─ docs/progress/pm-agent-ai-report-progress.md
└─ docs/decisions/pm-agent-ai-report-decisions.md
```

职责分别是：

- `AGENTS.md`：长期有效的项目开发规则
- `pm-agent-ai-report.md`：稳定需求、架构和验收标准
- `pm-agent-ai-report-progress.md`：按日期追加实施进度、测试结果和阻塞项
- `pm-agent-ai-report-decisions.md`：记录重要方案选择和变更原因

跨电脑同步流程：

```powershell
git pull
# 在当前电脑工作
git add docs AGENTS.md
git commit -m "docs: update PM agent report progress"
git push
```

另一台电脑：

```powershell
git pull
```

这样可以同时同步：

- 需求
- 架构决策
- 实施进度
- 测试结果
- Codex 的项目约束
- 代码本身

### 更适合实时协作的组合

如果项目已经使用 GitHub 或 GitLab，建议组合使用：

1. Git 仓库存放代码和正式文档
2. Issue 记录每个阶段的任务
3. Pull Request 记录实现和评审意见
4. `progress.md` 记录当前完成度和下一步
5. `AGENTS.md` 保持 Codex 在不同电脑上的行为一致

### 不推荐只使用云盘同步一个 Markdown

OneDrive、网盘或共享文件夹可以同步文件，但存在：

- 两台电脑同时编辑产生覆盖
- 无法清晰查看历史版本
- 需求、代码和进度可能不一致
- 很难知道哪次修改已经验证

如果暂时没有 Git 远程仓库，云盘可以作为临时方案，但建议至少保留带日期的进度日志，不要多人同时编辑同一个文件。

### 敏感信息注意事项

跨设备同步时不要提交：

- `.env`
- API Key
- 数据库密码
- Token
- 生产环境配置

建议提交：

- `.env.example`
- 不包含真实值的配置模板
- 本文档和进度日志

---

## 11. 建议的下一步

第一步不是直接重构全部报告，而是先做 Phase 0：

1. 固定一个多项目结算查询样例
2. 对比工作流结果、Artifact 和最终答案数量
3. 确认 SSE v2 客户端兼容性
4. 确认前端 ReportRenderer 的代码位置
5. 再开始 Phase 1 的 ReportSchema 改造

只有当 Phase 0 证明数据在后端仍然完整时，才进入报告渲染改造；如果业务查询层已经丢数据，应先修复工作流或能力接口，不能用 ReportSchema 掩盖数据问题。

---

## 12. 实施进度

### Phase 0：基线验证和数据完整性定位

- 状态：进行中
- 已完成：确认当前后端调用链、SSE v1/v2 机制、Artifact 完整性校验和多项目循环行为
- 已完成：确认当前仓库没有前端 `ReportRenderer` 源码
- 已完成：确认当前工作区存在未提交修改，后续操作必须避开无关文件
- 待用户执行：固定一个多项目结算查询样例并记录实际结果
- 待用户执行：对比 `WorkflowExecutionOutcome`、Artifact、最终回答和前端显示数量
- 待用户确认：前端是否发送 `X-Agent-Stream-Version: 2`
- 待用户提供：前端 `ReportRenderer` 或报告消息渲染所在项目路径
- 本阶段代码状态：未修改后台业务代码，未修改前端代码

### 下一阶段

Phase 1 已启动：新增固定 `ReportSchema`，先让业务数据在 AI 分析前展示。

> 说明：Phase 0 的代码链路检查已完成，运行时数量对比仍待实际 `runId` 证据确认。Phase 1 先采用兼容式接入，不删除旧 Markdown 和 `workflow` 字段；如果后续证据证明业务查询层存在丢数，立即暂停渲染改造并回到工作流链路修复。

### Phase 1：固定 ReportSchema，先展示基础数据

- 状态：进行中
- 本阶段目标：让前端获得稳定的结构化报告数据，不再从 Markdown 猜测表格和布局
- 本阶段范围：`ReportSchema` DTO、`ReportSchemaBuilder`、`ChatTextPayloadVO.reportSchema`、工作流消息组装位置
- 本阶段不做：不删除 `WorkflowAnswerComposer`，不修改 Graph 执行引擎，不新增数据库表，不新增依赖
- 本阶段暂不修改：`IntentResult`、`RuleBasedIntentRouter`、`AgentStreamEventType`、`AgentStreamSession`；当前固定报告接入不需要先扩展这些类
- 后台代码状态：仅输出带中文注释的修改示例，由用户手工应用
- 前端代码状态：等待用户提供前端项目路径或报告消息渲染文件
- 编码约束：优先短方法、单一职责、平铺主流程，禁止无必要的方法层层嵌套
- 编码约束：发现无用类、无用字段、无用方法或重复逻辑时，先指出删除建议，不保留“以后可能用到”的代码
- 编码约束：不为了抽象而新增接口、工厂或配置项；一个实现只保留最直接的调用路径

### Phase 1 完成标准

- 同一工作流返回稳定的 `reportType`、section 顺序和字段顺序
- `dataComplete`、成功数、失败数、跳过数和 Artifact 引用可被前端直接读取
- 旧客户端仍能读取 `workflow` 和 Markdown，不影响现有兼容链路
- 用户应用后台示例后，提供修改后的文件片段供我做位置检查

### Phase 1 当前检查结论

- 已发现问题：当前 `ReportSchemaBuilder` 的表格行直接读取 `WorkflowExecutionOutcome` 的 `item/data`，绕过 `WorkflowAnswerFieldContextResolver` 的字段安全投影
- 已发现问题：当前构建器遍历所有 FOREACH 批次，嵌套批次可能被重复展示，不能直接作为项目总表
- 处理决定：暂不进入前端渲染；先改为使用已有安全结果投影，或在无法确认字段安全时只发送指标和状态，不发送原始明细
- 维护决定：删除 `ChatTextPayloadVO` 中同包重复 import；不新增 `AgentStreamEventType`、`IntentResult` 或额外工厂层
- 当前检查：安全投影已接入，当前代码未发现原始 `item/data` 直接发送路径
- 当前阻塞：仓库内没有前端源码，无法编写 `REPORT_BASE` 固定渲染组件
- 当前建议：为 `ReportSchemaBuilder` 的异常降级增加一条中文上下文日志，避免静默吞掉字段策略错误

### 前端 Phase 1 设计状态

- 前端项目：`D:\TraeProject\enterprise-vue-admin`
- 已确认技术栈：Vue 3、Element Plus、Vite
- 已确认布局：A 方案，汇总优先
- 已确认策略：不保留旧 AI Report Markdown 兼容分支；普通 RAG Markdown 继续保留
- 已确认实现边界：统一使用 `streamQueryKnowledge` 和 `createSseParser`，删除前端重复 SSE 解析
- 设计文档：`D:\TraeProject\enterprise-vue-admin\docs\superpowers\specs\2026-08-07-ai-report-frontend-design.md`
- 前端实现：已完成 `AiChatWindow.vue`、`AiMessage.vue`、`AiReport.vue` 和 `ReportDataBlock.vue`
- 前端清理：已删除旧报告 Markdown 渲染、报告 facts 聚合、报告 references 展示和重复 SSE 解析
- 前端检查：已确认统一使用 `/api/agent/chat/stream` 和 `createSseParser`
- 当前状态：前端 Phase 1 已完成代码实现和位置检查；后台 `REPORT_ANALYSIS_*` 事件仍待 Phase 2

### Phase 1 当前下一步

1. 用户手工应用 `ReportSchemaVO`、`ReportSchemaBuilder` 和消息组装修改示例
2. 提供实际修改后的文件片段或提交后的文件路径
3. 我只检查：调用位置、字段命名、中文注释、是否存在无用代码和重复逻辑
4. 提供前端项目路径后，再编写 `report_base` 的固定渲染组件
5. Phase 1 检查通过后，才进入 Phase 2 的异步 AI 分析
