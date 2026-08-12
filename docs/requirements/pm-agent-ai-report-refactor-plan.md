# PM Agent AI Report 重构方案与跨设备协作计划

> 项目：`mc-ai`
>
> 当前阶段：扩展 Phase 6C 结算文件双列展示，待页面运行验收
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

当前仓库没有前端 `ReportRenderer` 源码；前端项目位于 `E:\traeProjects\enterprise-vue-admin`，后端协议在本项目维护，页面组件在前端项目中配合接入。

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
  "schemaVersion": 1,
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
    "status": "NOT_REQUIRED",
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
- `TABLE.columns` 禁止使用 `JSON` 数据类型
- `TABLE.rows` 的单元格只能是字符串、数字、布尔值、日期字符串或空值
- `TABLE.rows` 的单元格禁止包含 `Map`、`List`、`JsonNode` 或工作流运行时对象
- 前端正常报告页面禁止通过 `JSON.stringify` 展示业务数据
- `DATA_QUERY` 不调用 AI，`analysis.status` 固定为 `NOT_REQUIRED`
- 模板无法识别业务结构时只返回指标和告警，不向前端降级发送原始 JSON

### 4.3.1 首条固定验收案例

首条固定验收案例为“查询项目结算信息”。

预期结果：

1. 页面先展示项目和结算汇总指标
2. 结算明细使用后端定义的固定业务列
3. 一条结算明细对应一行
4. 项目公共字段可以合并到结算明细行
5. 失败和跳过项目进入 `WARNINGS`，不混入业务表格
6. 页面不出现“展开业务数据”或任何原始 JSON
7. 该查询属于 `DATA_QUERY`，不启动 AI 分析
8. 报告刷新后仍能从历史消息恢复

当前验收失败证据：

- 页面虽然已展示汇总指标和报告外壳，但“业务数据”仍为单列折叠 JSON
- 后端当前将整份安全结果放入一个 `data` 单元格，并将列类型声明为 `JSON`
- 前端发现复杂单元格后调用通用数据块执行 `JSON.stringify`
- 普通项目结算查询仍然进入 AI 分析流程

因此，现有实现只能认定为“报告外壳和事件链路已接入”，不能认定为“结构化业务表格完成”。

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
---

## 13. 2026-08-08 验收整改计划

本节是当前有效进度，覆盖上方历史实施记录中的阶段完成判断。历史记录继续保留，用于说明已经实施过的方案和产生当前问题的原因。

### Phase 0：重新建立验收基线

- 状态：已完成
- 本阶段只修改需求文档，未修改后台业务代码，未修改前端代码
- 已撤回“AI Report 已形成最终闭环”的判断
- 已确认当前页面仍展示单列 JSON，结构化业务表格未通过验收
- 已确认当前普通数据查询仍可能启动 AI 分析，不符合 `DATA_QUERY` 约束
- 已固定“查询项目结算信息”为首条端到端验收案例
- 已明确表格单元格只能包含标量值，禁止对象、数组和 JSON
- 已明确模板无法识别数据结构时只能返回指标和告警，不能泄露原始结果
- 已确认前端项目实际路径为 `E:\traeProjects\enterprise-vue-admin`
- 本阶段按约定未运行测试或编译，仅检查文档、现有代码位置和页面验收截图

### Phase 0 验收结论

当前实现已具备以下基础：

- SSE v2 报告事件链路
- 报告指标卡片和状态区域
- AI 分析异步区域
- 基础消息和最终消息更新能力
- 前端报告组件外壳

当前仍未完成：

- `DATA_QUERY` 与 `ANALYSIS_REPORT` 的真实执行分流
- `schemaVersion` 和稳定查询类型协议
- `ReportTemplate` 注册和选择机制
- 嵌套 FOREACH 业务记录提取
- 字段白名单到固定表格列的映射
- 多区块前端固定渲染
- 正常报告路径移除 JSON 展示
- 用户实际运行验收和最终闭环确认

### Phase 1：报告协议与查询类型

- 状态：已完成
- 已新增 `ReportQueryType` 和 `ReportType`
- `IntentResult` 已携带稳定的 `queryType`
- `RuleBasedIntentRouter` 已区分 `DATA_QUERY` 和 `ANALYSIS_REPORT`
- `ReportSchemaVO` 已增加 `schemaVersion`
- `DATA_QUERY` 的 `analysis.status` 固定为 `NOT_REQUIRED`
- `ANALYSIS_REPORT` 的初始分析状态为 `PENDING`
- SSE v2 已在业务工作流完成后先发送 `REPORT_BASE`
- `DATA_QUERY` 已在发送基础报告后直接结束，不进入 AI 分析线程
- `prepareReport()` 只执行安全投影、分块和 Artifact 保存，不调用大模型
- 已检查所有 `ReportSchemaBuilder.build()` 和 `ReportSchemaVO` 构造调用位置
- 本阶段按约定未运行测试或编译，仅完成后台代码位置、签名和调用关系检查

### Phase 2：项目结算固定模板

- 状态：代码实现和静态位置检查已完成，运行页面验收待用户执行
- 已新增最小 `ReportTemplate` 接口和 Spring 自动注册器
- 已新增 `ProjectSettlementReportTemplate`
- 已确认真实工作流编码为 `project_settlements_list`
- 已按两层 `FOREACH` 结构提取项目和结算明细
- 一条 `settlements[]` 记录对应一行，项目公共字段合并到明细行
- 固定列不包含 `JSON` 数据类型
- 所有表格单元格只允许字符串、数字、布尔值或空值
- 文件数组已经转换为普通字符串，不向前端发送对象或数组
- `ReportSchemaBuilder` 已删除旧的单列 JSON 构建方法
- 未匹配模板或字段策略异常时只返回指标和告警
- 前端 `AiReport.vue` 已在 `NOT_REQUIRED` 时隐藏 AI 分析区域
- 前端 `ReportTableSection.vue` 已移除复杂值 JSON 展示分支
- 已删除无调用方的旧 `ReportDataBlock.vue`
- 本阶段按约定未运行测试或编译，仅完成代码位置、字段路径、标量约束和调用关系检查
- 当前不得标记最终闭环，仍需实际页面验收

### 下一阶段：Phase 3

Phase 3 只执行“查询项目结算信息”端到端页面验收和问题整改。

验收顺序：

1. 用户自行启动后台和前端
2. 使用固定问题“查询项目结算信息”发起 `DATA_QUERY`
3. 确认页面不再出现“展开业务数据”和任何原始 JSON
4. 确认项目数、结算明细数及金额合计与业务数据一致
5. 确认一条结算记录对应一行且没有重复行
6. 确认普通数据查询不展示 AI 分析区域，也不调用分析模型
7. 刷新页面，确认历史消息仍能恢复相同固定报告
8. 用户提供页面截图和发现的问题，由我进行代码位置检查或前端整改

### 最终闭环后扩展待办

- 最终闭环确认后，提醒用户提供项目概算的真实工作流结构、安全结果结构和字段字典
- 根据用户提供的结构新增 `ProjectEstimateReportTemplate`
- 概算模板不在当前闭环内提前实现

### 最终闭环约束

在以下条件全部满足前，不得再次标记“最终闭环完成”：

1. 项目结算表格不再出现 JSON
2. `DATA_QUERY` 不调用 AI
3. `ANALYSIS_REPORT` 在 AI 完成前先展示基础报告
4. 多项目和多明细数量正确且没有重复行
5. AI 失败不影响基础数据
6. 刷新或断线后可以恢复报告
7. 用户完成实际页面验收并明确确认结果符合预期

---

## 14. 2026-08-08 项目结算最终模板整改进度

### 本轮目标

按用户最终确认的项目结算报告版式，替换原来的通用指标加宽表展示：

- 抬头展示项目编号、部门、生成时间和审批状态
- 顶部只展示合同金额、结算金额和计量金额
- 普通数据查询展示确定性报告摘要，不伪装成 AI 生成内容
- 按结算单位分组展示合同金额和累计结算金额
- 明细表固定为日期、含税金额、不含税金额、质保金、质保金日期和文件
- 附件名称可点击，并使用后端安全结果中的真实文件地址
- 页脚固定展示“数据来源：PM业务系统”

### 当前状态

- 前端状态：已完成代码编写和静态位置检查
- 后台状态：用户已应用完整替换示例，静态位置和字段映射检查通过
- 示例图状态：已生成，供用户确认视觉方向
- 测试与编译：按用户要求未执行
- 最终闭环：未完成，等待实际页面验收
### 后台静态检查结论

- `ProjectSettlementReportTemplate` 已写入规定路径
- `SETTLEMENT_DETAILS` 和 `SETTLEMENT_ATTACHMENTS` 与前端区块名称一致
- 项目、结算单位和明细记录使用稳定键进行分组
- 明细行只包含标量值，没有对象、数组或 JSON 展示字段
- 附件已经拆分为独立标量行，文件名称和文件地址映射完整
- 项目结算金额、计量金额使用元转万元格式，明细金额使用元格式
- 普通数据查询摘要由确定性规则生成，不调用 AI
- 代码没有方法内嵌套方法，也没有新增依赖、数据库或配置修改
- 按用户要求未运行测试或编译

### 前端修改文件

- `E:\traeProjects\enterprise-vue-admin\src\components\AiChat\AiReport.vue`
- `E:\traeProjects\enterprise-vue-admin\src\components\AiChat\ProjectSettlementReport.vue`

### 后台操作说明

后台完整修改示例见：

`docs/requirements/pm-agent-ai-report-phase3-settlement-template-backend-example.md`

用户需要完整替换：

`ai-agent/src/main/java/org/example/ai/agent/workflow/answer/report/template/ProjectSettlementReportTemplate.java`

### 本阶段下一步

1. 用户自行启动前后端并查询项目结算信息
2. 验收金额单位、分组、明细数量和附件链接
3. 刷新页面，验收历史报告恢复后的生成时间和内容是否保持一致
4. 用户提供实际页面截图，由 Codex 检查视觉结果和字段对应关系
5. 用户明确确认页面符合最终模板后，才能标记项目结算报告闭环完成

### 最终闭环后待办

闭环完成时必须提醒用户提供项目概算的真实数据结构，再新增 `ProjectEstimateReportTemplate`。
---

## 15. 2026-08-08 上下文补参与实时报告问题整改

### 问题一：补充项目编号后上下文失效

- 现象：助手要求补充项目编号后，用户只输入 `2674033`，系统没有继续执行上一轮结算工作流
- 根因：补参关系完全依赖模型分类，分类失败或置信度低于 `0.80` 时直接丢弃已保存的工作流状态
- 推荐修复：在现有 `state_json` 增加 `awaitingClarification` 标记，只对等待补参状态下的简单业务编号提供确定性兜底
- 后台状态：用户已应用修复；运行记录确认 `2674033` 已继承结算工作流并执行成功
- 后台示例：`docs/requirements/pm-agent-ai-report-phase3-context-live-fix-backend-example.md`
- 数据库影响：无，不新增字段和迁移文件
- 运行证据：`runId=676b82f22f75413ea5df08492eafdebb`，路由 `WORKFLOW_QUERY`，状态 `SUCCESS`，耗时 `9834ms`
- 消息证据：对应助手消息已保存 `PROJECT_SETTLEMENT`，包含 3 个区块，载荷长度约 12.8KB

### 问题二：实时查询不显示报告，刷新后才出现

- 现象：业务查询完成后实时消息显示“未返回回答内容”，刷新页面后结构化报告恢复正常
- 根因一：报告已经持久化；上一版兜底仍强制要求先从 SSE 取得 `runId`，报告事件整段未解析时 `runId` 为空，恢复逻辑直接返回
- 根因二：SSE 请求可能在 `fetch()` 等待响应头阶段阻塞，放在 `await fetch()` 之后的恢复逻辑无法启动
- 根因三：`REPORT_DONE` 已写入 `ReportSchema` 后，通用 `ANSWER_DONE` 又携带默认 `MARKDOWN`，把实时消息的报告展示类型覆盖为 Markdown
- 前端状态：已完成请求并行恢复、当前用户消息边界校验和报告类型防降级修复
- 最终补充：历史消息请求增加时间戳参数，禁止浏览器复用旧缓存
- Vite 验证：两个修改文件均返回 HTTP 200，转换后的模块包含最新用户消息边界和缓存规避逻辑
- 浏览器验证：已使用用户当前 Chrome 页面完成真实查询；未刷新页面时报告标题数量从 6 增加到 7，可见“正在思考…”数量为 0
- 上下文验证：真实执行“查询结算项目”后只补充 `2674033`，后台生成 `PROJECT_SETTLEMENT` 报告，补参链路执行成功
- 修改文件：`E:\traeProjects\enterprise-vue-admin\src\views\knowledge\AiChat\index.vue`、`E:\traeProjects\enterprise-vue-admin\src\api\agentChat.js`
- 修复行为：请求发出时立即并行检查持久化报告；优先按 `runId` 恢复，缺少 `runId` 时只读取最新用户消息之后的结构化报告；已有 `ReportSchema` 后禁止通用结束事件降级为 Markdown
- 安全边界：兜底消息必须包含 `ReportSchema`，并且位于当前会话最新用户消息之后，避免加载上一轮报告
- 后台协议建议：`AgentStreamSession.send()` 收到 `presentationType=REPORT` 时同步更新最终展示类型，避免 `complete()` 再发送默认 `MARKDOWN`

### 本阶段下一步

1. 用户核对项目金额、结算单位数量和明细行数是否与 PM 业务数据一致
2. 用户核对附件名称和点击地址是否正确
3. 确认普通结算查询没有原始 JSON，也不展示 AI 分析区域
4. 完成 `ANALYSIS_REPORT` 基础报告先展示、AI 分析后追加的页面验收
5. 用户明确确认结果符合最终模板后，标记项目结算报告最终闭环完成

### 验证说明

- 按用户要求未运行测试或编译
- 已完成浏览器实时展示验收；当前仍不得标记最终闭环完成，剩余业务数据和分析分流验收

### 2026-08-08 上下文补参工作流复用补充整改

- 复现场景：用户先输入“查询结算信息”，助手要求补充项目条件；随后只输入 `2584008`，系统再次返回“请确认需要执行的具体业务查询”
- 状态证据：会话状态已正确保存 `workflowCode=project_settlements_list`、`awaitingClarification=true`，因此不是会话状态丢失
- 运行证据：第二轮依次调用 `CONTEXT_REWRITE` 和 `WORKFLOW_PLANNER`，但没有进入 `WORKFLOW_PARAMETER_EXTRACTOR`，最终路由为 `CLARIFY`
- 根因：上下文解析器已经继承上一轮工作流后，工作流规划器仍调用模型重新选择工作流；第二次选择置信度不足，导致在参数提取前再次追问
- 推荐修复一：等待补参且当前输入是简单业务编号时，直接复用结构化会话状态，不调用上下文改写模型
- 推荐修复二：`previousWorkflowCode` 对应当前已发布工作流时，工作流规划器直接复用该工作流，不再调用模型重复选择
- 影响文件：`ConversationContextResolver.java`、`WorkflowPlanner.java`
- 数据库影响：无，不新增字段，不修改 Flyway
- 后台状态：用户已应用修复，两个修改位置静态检查通过
- 验证结果：用户使用新会话依次输入“查询结算信息”和 `2584008`，第二轮已直接进入参数提取和结算工作流，报告正常显示
- 运行证据：`runId=805b81c0522f4ef590a3bfb95b8b96f4`，路由 `WORKFLOW_QUERY`，状态 `SUCCESS`，工作流 `project_settlements_list`
- 消息证据：助手消息已保存结构化报告载荷，`payload_json` 长度为 `27161`
- 模型调用证据：补充编号这一轮只执行 `WORKFLOW_PARAMETER_EXTRACTOR`，不再执行 `CONTEXT_REWRITE` 和重复的 `WORKFLOW_PLANNER`
- 扩展验证：后续查询 `2574025` 和 `2674033` 两个项目也成功生成结算报告
- 阶段结论：上下文补参工作流复用问题已完成闭环
- 测试与编译：按用户要求不执行，仅在用户应用后检查代码位置并进行浏览器验收

---

## 16. Phase 4：项目结算结果聚合分析

### 本阶段目标

- 支持基于上一轮结算结果快照进行 Java 本地确定性统计
- 支持用户一次指定多个金额字段，并对每个字段执行相同统计操作
- 首条验收语句为“含税金额和不含税金额各自的总和是多少”
- 大模型只选择统计操作和受控字段 ID，不读取完整业务数据，也不计算金额
- 统计继续使用 Artifact 快照，不重新请求 PM 业务系统

### 跨业务模块复用约束

- 本阶段实现的是统一 Artifact 结果统计能力，项目结算只是首个验收案例，不得将实现绑定到 `PROJECT_SETTLEMENT` 或结算工作流编码
- 项目概算、现金流、合同、回款、成本、产值、进度和风险等后续模块必须复用同一个 `ResultArtifactStatisticsService`
- 多字段选择只能依赖通用字段目录中的受控字段 ID，不得在 Java 中硬编码 `amount`、`taxAmount` 等结算字段名
- 聚合权限必须读取字段语义中的 `aggregatable`，字段定位必须读取 `fieldPath`，单位换算必须读取通用 `format` 和 `meaning`
- “各自”“分别”等多字段语义以及元、万元等单位要求属于通用统计协议，不得为每个报告模板重复实现
- 新业务模板只负责将业务数据转换为统一 ReportSchema 和字段语义，不负责复制统计计算代码
- 结算验收通过后，其他模块接入只允许增加模板和字段字典配置；除非出现新的通用统计类型，否则不得修改统计主链路

### 当前问题与证据

- 旧 Artifact 中 `amount`、`taxAmount` 的 `aggregatable` 均为 `false`，因此安全校验正确拒绝求和
- 当前字段字典已重新建立两个金额字段，数据库值均为 `aggregatable=0`、`visible=1`、`publish_status=PUBLISHED`
- 字段字典变更不会追溯更新旧 Artifact，必须重新执行一次结算查询生成新快照
- 当前 `AnalysisPlan` 只有一个 `metricFieldId`，无法稳定表达“一次统计多个字段”

### 推荐最小实现

- 只修改 `ResultArtifactStatisticsService.java`
- `AnalysisPlan` 增加 `metricFieldIds`，同时兼容原有单字段 `metricFieldId`
- 一个统计操作可以对应多个受控字段 ID
- Java 对每个字段分别执行现有 `extractFieldValues()` 和 `calculate()`
- 输出一张多行统计表，每个字段一行
- 任一字段未开放聚合、路径不明确或包含非数字值时继续失败关闭，不允许模型猜测

### 影响范围

- 后台 Java：`ResultArtifactStatisticsService.java`
- 前端：不修改，继续使用现有 Markdown 消息渲染
- 数据库结构：不修改
- Flyway：不修改
- 外部依赖：不新增

### 当前状态

- 根因和影响范围已确认
- 字段字典当前配置已完成只读检查
- 用户已应用后台多字段统计代码，静态位置检查通过
- `AnalysisPlan` 已支持 `metricFieldIds`，并兼容原有 `metricFieldId`
- 任意字段 ID 不存在时整体拒绝，禁止部分字段静默参与统计
- 多个字段分别执行路径提取和 `BigDecimal` 计算，不混合累计
- 聚合权限、字段路径、非数字值和多结构重复值保护仍然生效
- 统计结果已改为一张多行固定表格，每个字段一行
- 前端、数据库结构、Flyway 和依赖均未修改
- Phase 4 已完成运行验收，统计结果和页面展示均由用户确认通过
- 按用户要求不运行测试或编译

### 首次运行验收失败与补充整改

- 失败现象：连续询问含税金额、不含税金额总和并要求换算万元时，前端显示“未返回回答内容”
- 运行状态：相关请求均进入 `RESULT_ANALYSIS` 且运行记录为 `SUCCESS`，不是 PM 查询失败
- Artifact 状态：`amount`、`taxAmount` 均存在完整路径，且最新快照中 `aggregatable=true`
- 根因一：统计规划提示词使用 `request.getEffectiveQuestion()`，上下文模型改写可能丢失“含税、不含税、各自、万元”等原始限定词
- 根因二：字段规划失败提示包含“字段路径”，统一回答清理器将整行删除，导致可见回答变为空字符串，助手消息不保存，SSE 也没有正文
- 根因三：结果统计返回 Markdown，但编排器错误标记为 `presentationType=REPORT`，同时没有携带 `ReportSchema`
- 缺失能力：现有计算结果没有处理用户明确要求的“元换算万元”
- 推荐整改：统计规划使用用户原话；失败提示改为用户可见文案；统计回答按 `MARKDOWN` 发布；Java 根据字段单位执行确定性万元换算
- 影响文件：`ResultArtifactStatisticsService.java`、`DefaultAgentOrchestrator.java`
- 前端影响：无
- 数据库和 Flyway 影响：无
- 当前状态：待用户按照本轮聊天代码示例应用
- 测试与编译：按用户要求不执行，用户应用后仅检查代码位置并重新进行页面验收

### 第二次运行验收：Artifact 机器数据与展示数据重复

- 用户现象：再次基于上一轮报告统计时，页面返回“本次分析没有生成可展示内容”
- 会话状态证据：`resultArtifactId=3baa3969581d4b3096050141316d7430` 仍保存在当前会话，工作流、项目参数和最后运行记录均未丢失
- Artifact 证据：状态为 `COMPLETE`，计划 4 个分块、实际保存 4 个分块，数据完整且未过期
- 结论：本次不是上下文记忆失效，也不是上一轮报告无法获取
- 实际根因：Artifact 的 `result` 同时包含 `workflowData`、`data` 和 `displayData`；统计器扫描完整 `result` 时把机器数据和展示副本识别成多个结构路径，为防止重复求和主动拒绝
- 空回答原因：多结构拒绝提示中包含“字段路径”，统一安全过滤器删除整行，最后保护只能返回通用空回答提示
- 通用修复原则：所有业务模块的确定性统计只读取 `workflowData`；旧 Artifact 没有该字段时回退 `data`；不得扫描 `displayData`
- 兼容范围：结算、概算、现金流、合同、回款、成本、产值、进度和风险等模块统一生效，不允许新增结算专用判断
- 影响文件：仅 `ResultArtifactStatisticsService.java`
- 数据库、Flyway、前端和依赖影响：无
- 后台代码状态：用户已应用机器数据选择和可见错误文案修改，静态位置检查通过
- 运行证据：`runId=66cb067a7dbb4ee29943060e96495c5d`，路由 `RESULT_ANALYSIS`，状态 `SUCCESS`
- 统计结果：不含税金额共 8 条，求和 `0.2981 万元`；含税金额共 8 条，求和 `0.4933 万元`
- 持久化证据：助手 Markdown 正文已保存，不再触发“未返回回答内容”或空回答最后保护
- 上下文结论：同一会话成功复用上一轮完整 Artifact，没有重新执行 PM 业务查询
- 通用能力结论：统计入口优先读取 `workflowData`、旧结构回退 `data`，未硬编码结算字段、报告类型或工作流编码
- 当前状态：Phase 4 已由用户确认完成，后台统计链路和页面结果均通过验收

### 验收步骤

1. 重新查询项目结算信息，生成包含最新字段语义的新 Artifact
2. 输入“含税金额和不含税金额各自的总和是多少”
3. 页面一次展示含税金额、不含税金额两行统计结果
4. 两个结果必须由 Java `BigDecimal` 基于 Artifact 全量数据计算
5. 本轮不得重新调用 PM 查询接口
6. 用户确认金额与业务数据一致后，Phase 4 才能标记完成

---

## 17. Phase 5：ANALYSIS_REPORT 异步分析状态收口

### 本阶段目标

- `ANALYSIS_REPORT` 在 AI 调用前先发送并保存完整基础报告
- AI 只生成 `summary`、`highlights` 和 `warnings`
- AI 分析通过 SSE 追加到已有报告，不生成 HTML、CSS、Markdown 表格或业务数据
- AI 返回空结构或分析失败时标记 `analysis.status=FAILED`，基础报告继续可用
- 数据完整性提示与 AI 执行状态分离，完成后不得残留“AI 分析尚未开始”
- 该状态协议对结算、概算、现金流、合同、回款、成本、产值、进度和风险统一生效

### 现有能力检查

- 已存在 `DATA_QUERY` 与 `ANALYSIS_REPORT` 路由分流
- 已存在 `prepareReport()` 和 `analyzeReport()` 两阶段处理
- 已存在 `REPORT_BASE`、`REPORT_ANALYSIS_START`、`REPORT_ANALYSIS_DELTA` 和 `REPORT_DONE`
- 已存在基础消息先保存、最终分析结果更新同一条助手消息的能力
- 已存在 AI 失败时保存 `analysis.status=FAILED` 并保留基础报告的降级链路
- 前端主聊天页面已按事件更新同一条消息的 `reportSchema` 和 `analysis`

### 本阶段最小整改

- 后台 `ReportSchemaBuilder`：数据状态只描述业务数据完整性，不再写入会过期的 AI 执行状态
- 后台 `WorkflowAnswerComposer`：结构化 JSON 至少包含一项可展示分析内容，否则按分析失败处理
- 前端 `ProjectSettlementReport.vue`：展示等待、分析中、完成和失败状态，失败时明确提示基础报告仍可使用
- 前端 `AiReport.vue`：已有风险提示时不再同时显示“暂无 AI 分析内容”
- 不新增接口、抽象层、数据库表、Flyway、配置项或外部依赖

### 当前状态

- 前端代码已完成并进行静态位置检查
- 后台全部修改已由用户应用，方法位置、调用签名、中文注释和空结果校验静态检查通过
- `Analysis.initial(...)` 和 `Analysis.pending()` 已移除业务 `warnings` 参数
- 业务数据告警只进入 `WARNINGS` 区块，AI 初始风险列表固定为空
- 旧的 `Analysis.initial(queryType, warnings)` 和 `Analysis.pending(warnings)` 调用已全部移除
- 当前状态：Phase 5 已完成
- 浏览器验收状态：本轮浏览器控制连接初始化失败，未操作用户页面，也未使用其他方式绕过
- 按用户要求不运行测试或编译
- 运行验收结果：用户已确认基础报告先展示、AI 状态原位更新和历史恢复符合预期
- 阶段结论：`ANALYSIS_REPORT` 异步分析链路完成验收

### 验收步骤

1. 输入“分析 2584008 项目结算情况”或等价分析问题
2. 确认页面先出现完整结算报告，并显示“AI 分析等待开始”或“AI 分析中”
3. 确认 AI 完成后原页面更新为“AI 分析完成”，同时展示摘要、重点和风险
4. 确认最终数据状态不再显示“AI 分析尚未开始”
5. 使用不可用模型或后台日志中的真实失败场景确认：分析失败时结算表格仍保留
6. 刷新页面，确认 `DONE` 或 `FAILED` 状态及报告内容可以从历史消息恢复

### 下一阶段

Phase 5 运行验收通过后，进入项目结算最终业务数据核对与闭环确认；闭环完成时提醒用户提供项目概算真实结构，再新增 `ProjectEstimateReportTemplate`。

---

## 18. Phase 6：项目结算最终业务数据核对与闭环确认

### 本阶段目标

- 使用单项目和多项目真实查询核对 ReportSchema 与 PM 业务数据
- 确认项目、结算单位和结算明细没有漏行、重复行或错误分组
- 确认金额、日期、审批状态和附件与 PM 原始数据一致
- 确认普通查询、分析查询、上下文追问和历史恢复形成完整闭环
- 本阶段只验收和修复真实问题，不新增抽象、依赖、数据库结构或未来模板

### 固定验收输入

1. 单项目：`查询2584008项目的结算信息`
2. 多项目：`查询2584008、2574025和2674033项目的结算信息`
3. 上下文统计：`根据上面的报告，含税金额和不含税金额各自的总和是多少，并换算成万元`
4. 分析报告：`分析2584008项目结算情况`

### 业务数据核对项

- 项目编号、部门、审批状态和生成时间正确
- 合同金额、结算金额和计量金额的数值及单位正确
- 项目卡片数量等于查询项目数量，输入顺序和展示顺序一致
- 每个项目的结算单位数量与 PM 数据一致
- 每个单位的结算记录数量与 PM 数据一致，一条记录只展示一行
- 含税金额、不含税金额、质保金、结算日期和质保金日期正确
- 附件名称正确，有地址时可以点击，无地址时不得生成无效链接
- 普通查询不出现原始 JSON，不启动 AI 分析
- 分析查询先展示基础报告，AI 失败时基础报告仍保留
- 刷新页面后报告、分析状态、附件和分组结果保持一致

### 当前状态

- Phase 6 已由用户完成运行验收
- 后台代码：暂无新增修改
- 前端代码：暂无新增修改
- 数据库、Flyway、配置和依赖：不修改
- 测试与编译：按用户要求不执行
- 最终闭环：项目结算 AI Report 已完成最终闭环

### 问题处理规则

- 数据数量不一致：先对比工作流结果、Artifact 和 ReportSchema，不先修改前端
- 字段值不一致：先检查字段字典和 `ProjectSettlementReportTemplate` 映射
- 实时与刷新结果不一致：先检查 SSE 事件和持久化消息载荷
- 只有找到真实根因后才提供最小代码修改

### 2026-08-09 工作流查询准确度整改

- 用户输入：`帮我生成XXCY2674033的结算信息`
- 失败运行：`runId=2a5f0c97da674e679de9f9f975870a64`，路由为 `CLARIFY`，未记录工作流编码
- 模型调用证据：本轮只调用一次 `WORKFLOW_PLANNER`，调用成功，但工作流选择结果未匹配
- 能力回退证据：`ai_capability_route_log` 为 `NO_CANDIDATE`，候选列表为空
- 排除项：同一会话后续只输入 `XXCY2674033` 时，`runId=7cdd64f1170847338291a40158d47240` 成功执行 `project_settlements_list`，因此编号格式不是根因
- 真实根因：明确命中“结算”的请求仍完全依赖模型选择工作流，低随机性不能保证模型每次作出相同判断
- 风险判断：将“生成”直接替换为“查询”会误伤真正的新增、生成或写操作，不采用该方案
- 推荐方案：路由器将已命中的业务关键词传给工作流规划器；关键词能够唯一匹配一个工作流名称或用途时确定性选择，零匹配或多匹配时继续使用现有模型选择
- 精度边界：确定性匹配要求候选工作流包含全部业务关键词，优先避免误调用；它提高明确业务词查询的稳定性，但不能解决工作流名称和描述本身缺少业务同义词的问题
- 影响文件：`RuleBasedIntentRouter.java`、`WorkflowPlanner.java`
- 前端、数据库、Flyway、配置和依赖：不修改
- 后台状态：待用户按照聊天内代码示例应用
- 验证要求：原失败语句必须直接进入 `WORKFLOW_QUERY`，不再先进入 `CLARIFY`，并且歧义查询仍需模型判断或追问
- 测试与编译：按用户要求不执行，应用后只检查代码位置和实际运行记录

### 最终闭环后的下一阶段

本阶段全部通过后，明确提示“项目结算 AI Report 最终闭环完成”，并提醒用户提供项目概算的真实工作流结构、安全结果结构和字段字典，开始 `ProjectEstimateReportTemplate`。

---

## 19. 扩展计划：后台可配置报告与工作流二次问答

本扩展计划在项目结算 AI Report 闭环后执行。目标不是为概算、现金流、产值、合同、回款、开票、成本和利润分别复制页面，而是让普通业务报告复用统一协议、配置和前端组件。

### 扩展 Phase 0：现状审计与协议冻结

#### 本阶段目标

- 核对现有 `ReportSchema + ReportTemplate + 前端组件注册表` 的真实能力
- 明确后台可配置的边界，避免把业务字段识别错误地交给大模型
- 冻结普通报告、动态树表和会话二次问答的最小协议
- 明确概算模块所需的真实输入，不根据示意图伪造工作流编码和字段路径
- 本阶段不修改后台业务代码、不修改前端代码、不新增数据库表

#### 现有能力事实

- 后端已经存在 `ReportSchemaVO`、`ReportTemplate`、`ReportTemplateRegistry` 和 `ReportSchemaBuilder`
- `ReportTemplateRegistry` 当前根据 Spring Bean 和 `workflowCode` 选择模板，不是数据库配置
- `ReportSchemaVO.Section` 当前使用字符串 `type`，已有 `items`、`columns` 和 `rows`
- 前端通用报告当前只渲染 `METRICS`、`TABLE`、`WARNINGS` 和 AI 分析区
- 项目结算通过 `ProjectSettlementReportTemplate` 和专用前端组件渲染
- 当前不存在 `ReportDefinition`、报告配置表、`KEY_VALUE`、`TREE_TABLE` 或通用组件动态注册配置
- 当前会话状态能保存上一工作流、输入参数和 Artifact ID，但不存在“等待用户选择业务明细”的通用状态
- `previousWorkflowCode` 只用于复用上一工作流，不能用于从概算汇总切换到概算科目明细工作流
- 当前报告消息存在 `reportSchema` 时，前端不会同时展示同一条消息的普通正文；继续查询提示必须作为独立助手消息处理
- 当前源码中没有发现概算汇总工作流、概算科目明细工作流、真实安全结果结构和字段字典

#### 前提纠正

- 后台配置可以消除普通列表的重复页面和大部分重复模板代码，但不能自动理解任意上游 JSON
- 如果不同业务工作流返回完全不同的数据结构，每个模块仍需提供字段映射；推荐把映射放进受校验的报告定义，而不是为每个模块复制 Java 页面模板
- 大模型不负责决定金额字段、比例、层级关系和页面列，也不负责生成 HTML、Markdown 或 CSS
- “是否继续查询科目明细”的提示可以由后端确定性生成，不需要调用大模型；只有科目名称存在歧义时才考虑模型辅助匹配

#### 冻结的 ReportSchema 区块协议

| 区块类型 | 数据位置 | 用途 | 约束 |
|---|---|---|---|
| `KEY_VALUE` | `items` | 项目编号、名称、经理、部门等基本信息 | 只允许标量值 |
| `METRICS` | `items` | 概算金额、已用金额、项目结余、成本率等核心指标 | 金额和比例由后端计算并格式化 |
| `TABLE` | `columns + rows` | 普通平面列表 | 不允许对象或数组作为单元格值 |
| `TREE_TABLE` | `columns + rows` | 动态科目、成本分类等层级数据 | 每行必须有稳定 `rowKey`，子级使用 `children`，汇总行可使用 `summary=true` |
| `WARNINGS` | `items` | 数据缺失、部分成功和业务状态提示 | 不混入 AI 分析状态 |

#### 概算展示协议

- 项目信息使用 `KEY_VALUE`
- 概算核心指标使用 `METRICS`
- 动态、不固定的概算科目使用 `TREE_TABLE`
- 概算表格列固定为：科目名称、变更后概算、占比、已用金额、已用率
- 概算表格不包含“操作”列
- 报告内部不提供科目下拉框、查看按钮或继续查询表单
- 普通概算查询不调用 AI；分析概算时才异步追加 AI 分析

#### 冻结的会话二次问答协议

```text
用户查询概算汇总
→ 汇总工作流返回 ReportSchema
→ 前端立即展示概算报告
→ 后端保存通用待追问状态
→ 后端发送独立助手消息：是否继续查询某个概算科目的详细信息
→ 用户输入否定语句：清除待追问状态并结束
→ 用户输入科目名称：继承项目标识并切换到配置的明细工作流
→ 科目唯一匹配：执行明细查询并输出报告
→ 科目零匹配或多匹配：只追问澄清，不误执行工作流
```

待追问状态必须是通用结构，至少包含：来源报告类型、来源 Artifact ID、目标明细工作流编码、继承参数和提示文本。不得把完整业务结果或所有科目明细写入会话状态 JSON。

#### 后台配置边界

普通报告定义后续至少需要配置：

- `reportType`
- `workflowCode`
- 报告标题和区块顺序
- 区块类型
- 字段 ID、字段路径、显示名称、数据类型和格式
- 是否允许聚合及字段业务含义
- 树表的行标识字段和子节点字段
- 是否启用会话追问
- 目标明细工作流编码和追问文本

字段路径必须经过安全字段策略校验。配置不存在、字段路径无效或数据类型不匹配时应返回告警并拒绝展示该字段，禁止回退显示原始 JSON。

#### 本阶段明确不做

- 不新增 `ProjectEstimateReportTemplate`
- 不新增前端概算专用组件
- 不新增报告配置数据库表或 Flyway
- 不修改 SSE 事件
- 不修改会话路由和工作流执行代码
- 不根据页面示意图猜测概算接口字段
- 不运行测试或编译

#### Phase 0 产出与验收结论

- 已完成现有后端报告链路、会话状态和前端渲染能力的静态检查
- 已冻结 `KEY_VALUE`、`METRICS`、`TABLE`、`TREE_TABLE`、`WARNINGS` 五类通用区块协议
- 已确定概算动态科目使用 `children` 树结构，且不包含操作列
- 已确定二次问答通过独立助手消息和通用待追问状态实现，不使用报告内选择控件
- 已确定 `previousWorkflowCode` 不能承担汇总工作流到明细工作流的切换
- 已明确后台配置只能替代重复映射代码，不能替代真实业务字段契约
- Phase 0 已完成；本阶段没有修改后台或前端源码

#### 进入 Phase 1 前必须提供

1. 概算汇总工作流编码及版本
2. 概算科目明细工作流编码及版本
3. 两个工作流的 GraphSpec 和输入参数定义
4. 概算汇总真实安全结果样例
5. 科目明细真实安全结果样例
6. 字段字典中的字段 ID、字段路径、格式、含义和聚合权限
7. 科目唯一标识、父子关系字段和项目标识字段

### 下一阶段

扩展 Phase 1：设计并实现最小后台报告定义模型。先支持配置驱动的 `KEY_VALUE`、`METRICS`、`TABLE` 和 `TREE_TABLE` 投影，不接入会话二次问答，也不修改概算业务工作流。

### 扩展 Phase 1：最小后台报告定义模型

#### 本阶段架构结论

- 报告定义放入现有 `GraphSpec.reportDefinition`，随工作流草稿一起校验并随发布版本一起固化
- 不新增独立报告配置表，避免工作流版本回滚后报告字段配置发生漂移
- `ai_field_dictionary` 继续负责字段中文名称、类型、格式、业务含义和展示权限
- 报告定义只负责区块、数据来源路径、字段字典 ID 和树结构映射，不重复保存字段语义
- 现有专用 `ReportTemplate` 优先级高于配置报告，保证项目结算模板行为不变
- 没有专用模板且存在有效 `reportDefinition` 时才使用通用配置投影
- 配置缺失或校验失败时继续失败关闭，只展示指标和告警，不回退原始 JSON

#### 本阶段代码范围

- 新增 GraphSpec 报告定义模型：`ReportDefinitionSpec`、`ReportSectionSpec`、`ReportFieldBindingSpec`、`ReportSectionType`
- `GraphSpec` 增加可选的 `reportDefinition`
- 新增 `ReportDefinitionValidator`，在工作流发布快照生成前校验配置结构和受限路径
- 新增 `ReportDefinitionResolver`，按工作流发布版本读取配置并校验字段字典归属、发布状态和展示权限
- 新增 `ReportValueReader`，只支持确定性的受限点路径和数组展开，不支持脚本、过滤器或函数
- 新增 `ConfigurableReportSectionBuilder`，生成 `KEY_VALUE`、`METRICS`、`TABLE` 和 `TREE_TABLE`
- 修改 `ReportSchemaBuilder`，接入配置报告并保留专用模板优先级

#### 本阶段明确不做

- 不新增数据库表、字段或 Flyway
- 不新增 Controller、Service CRUD 或后台管理页面
- 不修改前端组件
- 不接入会话二次问答
- 不新增概算专用模板
- 不修改项目结算专用模板
- 不调用大模型生成字段映射或页面结构

#### 当前状态

- 现有字段字典、工作流发布快照、模板注册器和报告构建器已完成静态检查
- 后台详细代码示例已在当前对话给出，等待用户手工应用
- 后台源码尚未由 Codex 直接修改
- 前端源码未修改
- 测试与编译按用户要求不执行
- 用户应用后只检查文件位置、调用关系、中文注释和是否存在重复或无用代码
- Phase 1 尚未完成，必须在用户应用代码并完成静态位置检查后才能进入下一阶段

#### 用户首次应用后的静态检查

- 已确认报告定义模型、GraphSpec 字段、发布校验和 `ReportSchemaBuilder` 主调用位置均已加入
- 未运行测试或编译
- 检查未通过：`ReportValueReader` 使用 Java 21 的 `List.getFirst()`，项目 Java 17 必须改为 `get(0)`
- 检查未通过：`ReportDefinitionResolver`、`ResolvedReportDefinition`、`ReportValueReader` 和 `ConfigurableReportSectionBuilder` 被放入 `graph.model.report.config`，运行期组件和 Mapper 依赖不得放入纯 GraphSpec 模型包，应移动到 `workflow.answer.report.config`
- 检查未通过：字段字典校验尚未验证绑定 `sourcePath` 的末级字段与字段字典 `fieldName` 一致，存在错误标签映射和业务金额含义错配风险
- 可接受调整：`ReportSectionType` 放在 `ai-common` 的稳定枚举包可以保留，不要求移动
- 当前状态：等待用户按本轮最小整改示例修改后再次静态检查，Phase 1 仍未完成

#### 下一阶段预告

Phase 1 静态检查通过后进入扩展 Phase 2：前端通用 `KEY_VALUE` 和 `TREE_TABLE` 渲染。概算表格配置不包含操作列，前端也不自动生成操作列。

#### 用户第二次应用后的静态检查结论

- Java 17 兼容问题已修复：`List.getFirst()` 已替换为 `get(0)`
- `ResolvedReportDefinition`、`ReportDefinitionResolver`、`ReportValueReader` 和 `ConfigurableReportSectionBuilder` 已移动到 `workflow.answer.report.config`
- 旧的 `graph.model.report.config` 包和引用已清理
- 字段绑定已校验 `sourcePath` 末级字段与字段字典机器字段一致，避免展示含义错配
- `ReportSchemaBuilder` 已引用新的运行期配置包
- 专用 `ReportTemplate` 仍然优先于配置报告，项目结算模板不受影响
- 配置缺失、无效或构建失败时仍然只展示指标和告警，不返回原始 JSON
- 未发现 Java 21 集合 API、旧包引用或补丁空白错误
- 按用户要求未运行测试或编译，本结论仅为源码位置和调用关系静态检查
- Phase 1 已完成

### 扩展 Phase 2：前端通用区块渲染

#### 本阶段目标

- 前端支持后端配置报告返回的 `KEY_VALUE` 和 `TREE_TABLE`
- 按后端 `sections` 原始顺序渲染，不再只取每种类型的第一个区块
- 概算动态科目使用 `rowKey + children` 展开，不固定科目名称和层级
- 概算树表不自动增加操作列、查看按钮、下拉框或继续查询表单
- 保留已有项目结算专用组件，不改变结算报告展示

#### 前端实际修改

- 新增 `src/components/AiChat/ReportKeyValueSection.vue`
- 新增 `src/components/AiChat/ReportTreeTableSection.vue`
- 修改 `src/components/AiChat/AiReport.vue`
- `KEY_VALUE` 使用三列响应式键值布局，窄屏自动切换两列和单列
- `TREE_TABLE` 使用 Element Plus 原生树表，默认展开后台返回的动态层级
- 数字和比例列右对齐，汇总行使用固定浅蓝背景
- 表格最大高度为 520px，避免大数据把聊天页面无限撑高
- 单元格只展示标量值，复杂对象和数组不降级为 JSON
- 未识别的区块类型直接忽略，不展示原始结果
- 通用指标区改为三列紧凑布局，与概算参考图的信息密度一致

#### 影响范围与兼容性

- 项目结算仍由 `ProjectSettlementReport.vue` 优先渲染
- 普通 `TABLE` 和 `WARNINGS` 继续使用原组件和协议
- 新组件不包含概算字段、工作流编码或科目名称，可复用于成本、预算、现金流和进度等模块
- 用户已有的 `WorkflowCanvasNode.vue` 和 `vite.config.js` 修改未触碰
- 后台源码、数据库、Flyway、依赖和配置均未修改

#### 检查结论

- 已完成前端源码静态检查和补丁空白检查
- 已确认不存在报告内操作列、查看按钮、下拉框或继续查询区域
- 按用户要求未运行测试、构建、开发服务器或浏览器验收
- Product Design 视觉运行验收因用户规则主动跳过，不能声称已经完成浏览器像素级验证
- Phase 2 已完成

#### 下一阶段

扩展 Phase 3：使用概算汇总工作流的真实发布结构配置首个 `PROJECT_ESTIMATE` 报告。需要提供工作流编码、版本、真实安全结果样例、字段字典 ID，以及科目树的 `rowKey`、`children` 和字段路径。

### 扩展 Phase 3：首个 PROJECT_ESTIMATE 配置报告

#### 已核对的真实配置

- 工作流编码：`project_budget_list`
- 工作流名称：`查询项目概算列表信息`
- 当前活动版本：版本 7，版本记录 ID 为 14
- 工作流已经发布并启用，活动版本已经配置 `PROJECT_ESTIMATE reportDefinition`
- 概算列表能力：`pm_test.queryByPage_31`
- 概算详情能力：`pm_test.info_24`
- 按科目查询已用金额能力：`pm_test.getUsedList`
- 三项能力均已发布并启用，字段字典已经存在

#### 当前阻断问题

- 概算列表和详情查询已经完成调试运行，查询流程本身不再阻断
- 活动版本 5 的 END 已返回 `$vars.project_budget_list.workflowData`
- 活动版本 5 已包含 `PROJECT_ESTIMATE reportDefinition`，共包含项目概况、核心指标和概算明细三个区块
- `detailList` 的真实结果是平铺科目，不包含 `children`
- 当前字段字典只有 `subjectCode` 和 `parentSubjectId`，没有可与 `parentSubjectId` 匹配的科目行 `id` 字段，不能可靠构造父子树
- 概算核心指标中的项目结余、成本率和利润率缺少已确认的业务公式，不能通过父子科目混合列表直接求和推断

#### Phase 3A：先完成工作流结果闭环

- 复用已发布结算工作流的双层 `FOREACH` 结构，不默认选取模糊查询的第一条记录
- 外层 `FOREACH` 遍历 `$input.projectKeys`，当前概算报告限制为单个项目关键字
- 内层概算列表节点把 `queryStr` 改为读取 `$item`
- 第二层 `FOREACH` 遍历 `$vars.project_records.workflowData.records`，概算详情节点从 `$item.id` 取值
- 缺少 `id` 的列表记录按统一跳过策略处理，不调用概算详情接口
- END 节点返回外层 `FOREACH` 的 `workflowData`，不再返回原始 `$input`
- 本阶段真实查询必须使用准确项目编码；项目名称或模糊关键字可能返回多条记录，不能静默选取第一条
- 重新校验、发布并执行一次真实概算查询，取得成功的安全结果样例

#### Phase 3B：成功结果返回后继续

- 根据真实安全结果确认项目基础信息、指标和科目明细路径
- 如果 `detailList` 已有 `children`，直接配置 `TREE_TABLE`
- 如果 `detailList` 只有 `parentSubjectId`，先补充通用的平铺转树协议，不能在概算模块中写专用转换
- 只配置真实存在且业务含义明确的指标，不自行推断利润率、成本率或项目结余公式
- 配置并发布首个 `PROJECT_ESTIMATE` 的 `reportDefinition`

#### Phase 3B-1：当前实施步骤

- 先使用 `TABLE` 展示后台返回的全部动态概算科目，不固定科目名称和数量
- 项目概况只展示已确认的项目编码、名称、类型、经理、部门和公司
- 核心指标暂时只展示真实存在的概算金额和合同金额
- 不计算缺少业务公式的已分配预算、项目结余、成本率和利润率
- 通用路径校验允许标量路径穿过中间数组，但路径末级仍必须是标量
- 读取器在路径命中多个项目时继续抛出异常，不静默选取第一项
- 概算工作流的 `projectKeys.maxItems` 配置为 1，保证固定报告只对应一个项目
- 科目接口补充可与 `parentSubjectId` 匹配的行主键后，再把明细区块切换为 `TREE_TABLE`

#### 当前状态

- Phase 3 已开始
- 概算流程已由用户验证并发布；数据库已确认工作流为 `PUBLISHED`、已启用、草稿无未发布修改
- 已确认活动发布版本为版本 5，版本记录 ID 为 12，共 10 个节点、7 条连线
- 已确认发布快照的 `projectKeys.maxItems` 为 1，END 输出和三个报告区块配置均正确
- 已完成版本 5 发布后的正式 `CHAT` 查询，运行 ID 为 `537a35ad9fb54529860fc8fb042743b3`
- 正式运行绑定工作流版本记录 ID 12、版本号 5，状态为 `SUCCESS`，1 个项目全部成功
- 前端已正常展示项目概算信息、概算核心指标和概算明细，没有展示原始 JSON
- 前端工作流工作台已增加“列表 + 详情模板”，可一键生成开始、双层 FOREACH 和结束节点
- 旧画布正好存在两个能力时，模板按从左到右顺序带入列表能力和详情能力，并根据能力 Binding 自动重建参数
- END 节点连接唯一上游后自动输出 `$vars.<outputKey>.workflowData`
- 打开旧草稿或保存时，如果 END 仍为默认 `$input` 或旧式 `$vars.<outputKey>`，会自动修正；用户已配置的高级表达式不会被覆盖
- 前端 GraphSpec 编辑链路已补充 `reportDefinition` 原样透传，避免打开、应用源码或保存画布时丢失报告配置
- 前端输入协议规范化已改为保留显式的 `projectKeys.maxItems`，概算可限制为单项目，其他工作流仍可配置 1～5 个项目
- Phase 3A 的业务查询流程和 END 规范化已经完成
- Phase 3B-1 的平铺动态明细配置已经完成正式聊天验收，本阶段完成
- Phase 3B-2 已开始，科目行 `id` 字段字典已经补齐；指标业务公式仍待确认
- 上一次正式结果生成时字段字典尚未包含 `id`；当时的 `parentSubjectId` 为 408、415 等业务主键，不能与 `subjectCode` 匹配
- 如果详情接口原始响应已有 `id`，需要为 `$.data.detailList[].id` 生成并发布 `visible=1` 的字段字典，但不把它绑定为表格展示列；如果原始响应没有 `id`，需要由 PM 业务接口补充稳定科目主键
- 不能把树节点 `id` 配置为 `visible=0`，因为当前安全结果策略会在构建 ReportSchema 前删除隐藏字段，导致树构建器无法读取主键
- 合同金额 173 来自业务接口字段 `sumContractAmt=173`，当前未发现前端格式化或报告映射错误，业务单位和数值正确性需由数据源确认
- 概算详情能力已补充科目主键字段字典，字段 ID 为 135，路径为 `$.data.detailList[].id`，类型为 `string`，状态为 `PUBLISHED`，`visible=1`
- 概算详情能力和概算工作流当前均无脏草稿，仅新增字段字典不要求重新发布工作流
- Phase 3B-2 下一步为扩展通用 `TREE_TABLE` 协议：支持 `rowKeyPath + parentKeyPath + rootParentValue` 将平铺数据安全转换为树形数据
- 平铺转树必须校验重复主键、缺失父节点、循环引用、最大层级和最大行数，不允许静默丢弃错误关系
- 用户已完成 `ReportSectionSpec`、`ReportDefinitionValidator` 和 `ConfigurableReportSectionBuilder` 的通用平铺转树代码
- 静态检查确认旧 `childrenPath` 嵌套树保持兼容，新平铺树分支包含重复键、缺父节点、循环、最大 10 层和最大 5000 行保护
- 概算工作流版本 6 已将明细区块更新为 `TREE_TABLE` 并发布，发布快照中的 `rowKeyPath=id`、`parentKeyPath=parentSubjectId`、`rootParentValue=0` 均正确
- 版本 6 已产生正式 `CHAT` 运行 `a1677bd06e1b43a9aa14a309332364ca`，工作流本身执行成功，但 ReportSchema 构建抛出 `IllegalStateException`
- 根因是当前 `id` 为概算明细记录主键（119032～119054），`parentSubjectId` 为科目定义主键（408、415），两者不属于同一主键空间
- 树构建器因此无法在行主键中找到父节点 408、415；不能将缺失父节点静默当作根节点，否则会掩盖错误业务关系
- PM 详情接口需要补充与 `parentSubjectId` 同域的 `subjectId`，字段字典路径建议为 `$.data.detailList[].subjectId`，发布后将 `rowKeyPath` 改为 `subjectId`
- 概算详情字段字典已发布 `subjectId`（字段 ID 136），实际来源路径为 `$.data.detailList[].subjectDetailId`，安全结果字段名为 `subjectId`
- 工作流版本 7 已将 `rowKeyPath` 更新为 `subjectId`，正式 `CHAT` 运行 `3809ced0f7b047ada10709b051bb8020` 状态为 `SUCCESS`
- 已核对父子关系：其他费用 408 对应子科目 409～412，项目组奖励 415 对应子科目 416，主键空间一致
- 前端树表已修正层级视觉：移除导致文字错位的自定义连接线和左侧标志，保留原生展开箭头、父行底色和缩进，并加宽科目列避免长名称错乱换行
- Phase 3B-2 的动态科目树数据和展示链路已经闭环，下一步处理完整指标映射

#### Phase 3C：概算完整指标映射

- 业务系统当前不能调整概算响应结构，也不能提供可靠的独立汇总指标对象
- 暂时取消概算 `METRICS` 区块，不展示无法确认业务口径的合同金额、已用金额、项目结余、成本率和利润率统计卡片
- 不在通用报告构建器中按 `subjectCode` 写死概算指标，也不让前端计算父子科目和合计行
- AI 分析只用于定性摘要、重要科目识别、异常提示和风险建议，不作为精确业务统计结果
- 用户明确要求普通概算查询完成后也自动追加简短 AI 解读，不再只在分析类提问时展示 AI 区域
- 新增工作流报告级 `analysisPolicy`，建议支持 `ON_DEMAND`、`ALWAYS`、`DISABLED`；默认 `ON_DEMAND` 保持已有工作流兼容
- 概算报告配置为 `ALWAYS`：即使查询类型是 `DATA_QUERY`，也先展示基础报告，再异步追加 `summary / highlights / warnings`
- `ANALYSIS_REPORT` 继续按用户明确的“分析、风险、异常、建议”等意图触发；查询类型与是否自动分析分别表达，不能强行把普通查询改写成分析查询
- AI 只能读取字段策略过滤后的安全数据，不得生成 HTML、Markdown、CSS，不得把缺失值当作 0，不得自行汇总父子科目
- Phase 3C 调整为“报告级可配置自动 AI 解读”，复用现有异步分析链路，不新增接口、数据库表或专用分析框架
- 自动分析会增加模型调用、Token 成本和最终完成时间，但基础报告仍必须立即展示，不能等待 AI
- Phase 3C-1 后台实施范围：新增 `ReportAnalysisPolicy`，扩展 `ReportDefinitionSpec`，由 `ReportSchemaBuilder` 计算有效分析策略，编排器根据 `analysis.status=PENDING` 启动现有异步分析
- `ReportQueryType` 继续表示用户意图，不能为了自动分析把 `DATA_QUERY` 改写为 `ANALYSIS_REPORT`
- AI 分块和结构化汇总提示词需要补充空值、父子科目、合计行、事实与推断边界
- Phase 3C-1 后台代码已由用户应用，并完成静态位置与调用链检查：`ReportAnalysisPolicy`、`ReportDefinitionSpec`、`ReportSchemaBuilder`、`ReportSchemaVO.Analysis` 和编排器接入位置符合本阶段要求
- 概算 GraphSpec 的下一步配置已经确定：移除口径不可靠的 `METRICS`，保留 `KEY_VALUE + TREE_TABLE`，并配置 `analysisPolicy=ALWAYS`
- 当前等待用户将完整 GraphSpec 应用、校验并发布；发布后验收“基础报告立即展示，AI 摘要、重要科目、异常和风险提示异步追加”
- 概算工作流已发布版本 8，活动版本记录 ID 为 15，发布快照中 `analysisPolicy=ALWAYS` 且只保留 `KEY_VALUE + TREE_TABLE`
- 正式 `CHAT` 运行 `8d2331865aa14ff2b364f8243a31b6e4` 状态为 `SUCCESS`，最终助手消息的分析状态为 `DONE`
- Phase 3C 已闭环：基础概算报告与自动 AI 定性分析链路均已生效
- 未修改后台 Java 源码、数据库结构、Flyway 或依赖
- 按用户要求未运行测试或编译

### 扩展 Phase 4：报告后的通用业务追问

#### Phase 4A：追问配置与会话状态协议

- 当前真实明细能力为 `pm_test.getUsedList`，已发布并启用，输入参数为 `projectCode`、`subjectCode`、`subjectName` 和 `history`
- 当前仅存在概算汇总工作流 `project_budget_list`，尚未创建概算科目明细工作流，不能直接配置一个不存在的目标工作流编码
- 在 `ReportDefinitionSpec` 增加可选 `followUp`，只描述追问提示、候选行路径、候选键与名称路径、目标工作流和目标输入映射
- 输入映射只允许标量常量、`$source` 安全路径和 `$selected` 安全路径，不允许脚本、函数或模型生成 JSONPath
- 在 `BusinessConversationState` 增加通用 `pendingReportFollowUp`，只保存来源报告、Artifact ID、目标工作流和已解析的继承参数，不保存完整报告或全部科目列表
- 用户否定时只清除待追问状态，保留上一份 Artifact，避免破坏后续“分析上面报告”的上下文
- 科目唯一匹配才允许执行目标工作流；零匹配和多匹配必须继续澄清
- 本阶段不接入编排器、不发送追问消息、不创建数据库表、不修改前端
- 用户已完成 `ReportFollowUpSpec`、`ReportDefinitionSpec.followUp`、发布结构校验、`PendingReportFollowUp` 和 `BusinessConversationState.pendingReportFollowUp`
- 静态检查确认配置模型位于 GraphSpec 纯模型包，校验器只处理通用安全路径和标量映射，会话状态没有保存完整报告或候选科目列表
- 未发现概算工作流编码、概算字段名称或能力编码进入通用校验和状态模型
- Phase 4A 已完成

#### Phase 4B：概算科目明细目标工作流

- 目标工作流编码确定为 `project_budget_subject_detail`
- 目标能力使用已发布的 `pm_test.getUsedList`
- 工作流输入固定为 `projectCode`、`subjectCode`、`subjectName` 和 `history`，与能力真实输入协议保持一致
- 第一小步只创建、校验、发布并调试目标工作流，不提前猜测安全结果外层结构
- 已从最近一次完整 Artifact 核对真实候选科目，例如合同额 `HTE`、人员费用 `RYFY`、项目组奖励 `XMZJL`
- 取得一次真实安全结果后，再根据真实结果路径配置通用 `TABLE` 报告，不回退展示原始 JSON
- 用户已完成 `project_budget_subject_detail` 的验证和发布，但复核发现该工作流只有一个 READ 能力节点
- 项目现有 `DefaultToolExecutor + BusinessCapabilityExecutorImpl` 已能直接执行已发布 READ 能力，并统一处理权限、参数绑定、字段字典投影和运行记录
- 因此单能力场景不再强制创建包装工作流；Phase 4 设计调整为“能力优先，工作流按需”
- `followUp` 目标后续统一使用 `targetType + targetCode`：`CAPABILITY` 直接执行单个只读能力，`WORKFLOW` 用于循环、多节点、条件或组合业务流程
- 已发布的 `project_budget_subject_detail` 暂时保留但不接入追问链路；直接能力链路验收通过后再由用户决定是否禁用，不执行直接删除
- Phase 4B 调整后的第一步：将 `ReportFollowUpSpec` 和 `PendingReportFollowUp` 的 `targetWorkflowCode` 替换为通用 `targetType + targetCode`
- 当前尚无已发布报告配置使用 `followUp`，因此不保留旧字段兼容层，避免长期维护重复目标字段
- `targetType` 当前只允许 `CAPABILITY` 和 `WORKFLOW`，其中单个 READ 能力默认使用 `CAPABILITY`

#### Phase 4 后续步骤

1. Phase 4A：应用并静态检查追问配置和会话状态模型
2. Phase 4B：创建并发布概算科目明细工作流，取得真实安全结果并配置通用 `TABLE` 报告
3. Phase 4C：实现 Artifact 候选匹配、否定处理、唯一匹配和目标工作流确定性切换
4. Phase 4D：在报告完成后发送独立助手追问消息，并完成刷新恢复、失败降级和最终闭环验收

#### Phase 4C-1：追问状态激活与确定性候选匹配

- `targetType + targetCode` 协议已由用户完成，静态检查通过，旧 `targetWorkflowCode` 引用已经清理
- 新增通用 `ReportFollowUpService`，复用已发布工作流快照、完整 Artifact 还原器和受限路径读取器
- 报告成功后只解析 `$source` 和固定输入，生成 `PendingReportFollowUp`；`$selected` 参数等待下一轮唯一匹配后补充
- 否定回答采用确定性本地规则，只清除待追问状态，不清除上一轮 Artifact
- 科目匹配优先完整名称或编码，其次允许当前问题包含完整名称或编码；不进行拼音、编辑距离或模型猜测
- 零匹配和多匹配只返回澄清提示，不调用能力
- 本小步不修改编排器、不执行目标能力、不发送追问消息；完成静态检查后进入 Phase 4C-2
- 用户应用后的静态检查发现两项需同步修正：未配置 `followUp` 的普通报告不能记录准备失败警告；目标类型和候选文本大小写转换必须使用 `Locale.ROOT`

#### Phase 4C-2：编排器确定性执行直接能力

- `ConversationContextResolver` 在上下文重置处理之后发现 `pendingReportFollowUp` 时跳过模型关系分类，由专用追问服务处理用户回答
- `DefaultAgentOrchestrator` 在结果分析和普通意图路由之前处理 `CANCELLED / CLARIFY / READY`
- `READY + CAPABILITY` 通过 `PlanTemplateRegistry` 创建单能力 `RoutePlan`，继续复用现有 `ToolExecutor`、运行步骤记录、字段字典投影和回答保存
- 目标能力执行成功后复用 `recordToolResult` 覆盖会话状态，从而自然清除旧的待追问；执行失败时保留待追问，允许用户重试
- 本小步暂不执行 `READY + WORKFLOW`，当前概算配置只使用 `CAPABILITY`；工作流目标待出现真实多节点需求时再接入
- 用户已完成本小步后台代码；静态检查确认追问拦截位于结果分析和普通路由之前，单能力计划继续复用现有工具执行链
- Phase 4C-2 已完成，尚未运行测试或编译

#### Phase 4D：独立追问消息与最终闭环

- 新增通用 `REPORT_FOLLOW_UP` SSE 事件；该事件只携带服务端已发布配置中的提示文本，不携带候选业务数据
- 前端收到事件后新增独立助手消息，不覆盖报告消息，也不在报告内提供下拉框或操作列
- 追问消息必须单独持久化；刷新页面后继续按照普通助手历史消息恢复
- 无需 AI 分析的报告在 `REPORT_DONE` 后发送追问；需要 AI 分析的报告在分析成功或失败降级完成后发送追问
- 追问消息保存或发送失败不能破坏已经生成的基础报告，服务端只记录告警并正常结束报告响应
- 前端 `src/views/knowledge/AiChat/index.vue` 已完成 `REPORT_FOLLOW_UP` 事件处理和重复事件去重
- 用户已完成 Phase 4D 后台代码；静态检查确认普通报告、AI 分析成功和AI分析失败三条完成路径都会在报告完成后发送独立追问
- 独立追问先保存为普通助手历史消息，再发送 `REPORT_FOLLOW_UP`，且位于报告最终更新之后，不会被同一 `runId` 的报告更新覆盖
- `project_budget_list` 当前已发布启用，活动版本为版本 9、版本记录 ID 为 17，`draftDirty=0`
- 活动发布快照中的 `followUp` 已启用，目标为只读能力 `pm_test.getUsedList`，候选路径和四个输入映射均已核对
- 当前进入 Phase 4D 最终运行验收；通过独立追问、刷新恢复、科目查询、否定结束和异常澄清后才能标记最终闭环
- 按用户要求未运行测试或编译
- 用户已完成独立追问、刷新恢复、科目明细查询、否定结束和异常澄清验收
- Phase 4 通用报告二次问答最终闭环完成

### 扩展 Phase 5：工作流报告可视化配置

#### 现状判断

- 配置驱动报告和通用追问已经形成运行闭环，但 `reportDefinition` 仍主要依赖 GraphSpec 源码维护
- 工作流工作台只负责原样透传报告定义，普通管理员不能通过表单完成报告配置
- 当前 `ReportType` 尚未提供开票、预算和利润的独立类型；这些模块在协议扩展前只能使用 `GENERIC_WORKFLOW_REPORT`，不能当作已有专用类型
- 真正的傻瓜式配置仍需要分步解决基础设置、区块字段映射和追问规则，不能把所有高级路径一次堆进一个表单

#### Phase 5 分步范围

1. Phase 5A：报告基础设置和现有区块总览
2. Phase 5B：区块创建、字段字典选择和安全路径映射
3. Phase 5C：报告后追问规则可视化配置
4. Phase 5D：使用第二个业务模块验证无需新增后台 Java 模板

#### Phase 5A：报告基础设置和区块总览

- 工作流工作台新增“报告配置”入口，不再要求修改标题和 AI 策略时打开 GraphSpec 源码
- 支持修改报告标题、现有报告类型和 `ON_DEMAND / ALWAYS / DISABLED` 分析策略
- 支持修改现有区块标题和上下排序，字段 ID、字段路径、树结构路径及追问配置保持不变
- 显示现有追问是否启用、目标类型、目标编码和助手提示，但本小步不允许修改高级追问映射
- 当前没有 `reportDefinition` 的工作流只显示空状态，不生成无法通过后端校验的空区块
- 新增前端组件 `WorkflowReportConfigDialog.vue`，并接入工作流现有草稿保存、校验和发布链路
- 后台 Java、数据库、Flyway、配置和依赖均未修改
- 已完成前端源码位置、状态传递和补丁空白静态检查；按用户要求未运行构建或测试
- Phase 5A 源码实现和静态检查已完成；用户已指示继续 Phase 5B，未单独声明浏览器验收结果

#### Phase 5B：区块创建、字段字典选择和安全路径映射

- 没有 `reportDefinition` 的工作流可以从可视化入口创建通用报告，并在至少一个合法区块完成后才允许应用
- 支持新增、编辑、删除和排序 `KEY_VALUE / METRICS / TABLE / TREE_TABLE` 四类区块
- 字段候选只读取当前工作流实际引用能力的字段字典，不允许选择其他工作流无关能力字段
- 字段字典必须已经发布且允许展示；选择后使用稳定机器字段名作为 `key`
- `KEY_VALUE / METRICS` 使用绝对标量路径，提供公共对象路径以批量生成完整字段路径
- `TABLE / TREE_TABLE` 使用绝对数组行路径，表格字段自动使用相对机器字段路径
- `TREE_TABLE` 支持后台嵌套 `childrenPath` 和 `parentKeyPath` 平铺转树两种模式，二者不能同时配置
- 前端安全路径格式、区块上限、字段上限、树表保留字段和字段路径末级名称规则与后端校验保持一致
- 字段标签、类型、格式和业务含义继续来自字段字典；没有增加概算、合同或金额字段的专用分支
- 继续复用现有 GraphSpec 草稿保存、服务端校验和发布链路，不新增报告配置接口或数据库表
- 已完成前端源码位置、字段加载范围和补丁空白静态检查；按用户要求未运行构建或测试
- Phase 5B 已实现；用户已指示进入下一阶段

#### Phase 5C：报告后追问规则可视化配置

- 报告配置弹窗支持启用或关闭报告后追问，并可维护助手提问内容
- 目标执行范围只展示后台认定可执行、已启用的只读 `CAPABILITY`，并兼容历史空发布状态；当前运行链尚未支持 `WORKFLOW` 目标，因此不提供保存后无法执行的选项
- 候选数据行路径优先从现有 `TABLE / TREE_TABLE` 区块读取，同时允许录入符合后端安全路径规则的数组路径
- 候选唯一编码和显示名称使用相对标量路径，不增加概算科目、合同编号等模块专用字段
- 目标能力输入根据其 `requestBindingJson` 自动生成，支持来源报告、用户选中行、固定字符串、固定数字和固定布尔值
- 保存前校验必填参数、重复参数、参数数量、至少一个 `$selected` 映射以及后端暂不支持的嵌套参数结构
- 已有追问配置重新打开时保持原映射值，并从目标能力恢复必填参数标识
- 继续写回现有 GraphSpec `reportDefinition.followUp`，未新增后台 Java、数据库、Flyway、配置或依赖
- 已完成前端源码位置、结构保存和补丁空白静态检查；按用户要求未运行构建或测试
- Phase 5C 已实现；用户决定后续自行添加并验证新的业务模块

#### Phase 5D：第二业务模块验证状态

- 用户决定后续自行选择和添加第二个真实业务模块，本阶段暂不实施
- 当前没有第二模块的真实能力、字段字典和返回结构，不能把“设计上可复用”写成“已经跨模块验证通过”
- Phase 5A～5C 的功能开发范围已经完成，Phase 5D 仅保留为上线前跨模块验收项
- 未新增后台 Java、前端模块专用组件、数据库、Flyway、配置或依赖

#### 后续模块自助接入清单

1. 发布业务查询能力，并确认能力为只读、已启用且请求绑定可解析
2. 发布业务字段字典，确认 `fieldName / fieldPath / format / meaning / visible` 与真实安全结果一致
3. 创建或复用工作流；单能力查询不为了包装而额外创建工作流
4. 在“报告配置”中选择现有专用 `ReportType`；没有对应枚举时使用 `GENERIC_WORKFLOW_REPORT`
5. 按真实返回结构配置 `KEY_VALUE / METRICS / TABLE / TREE_TABLE`，不在前端推断业务公式
6. 需要报告后追问时，配置助手提示、候选行路径、目标只读能力和输入映射；至少一个输入来自用户选中行
7. 依次完成 GraphSpec 校验、保存草稿、发布和正式聊天验收
8. 验收基础报告即时展示、AI 异步追加、刷新恢复、否定结束和唯一候选执行

#### 扩展计划收口结论

- 后台可配置 `ReportSchema`、通用前端区块渲染、报告后追问和可视化报告配置的开发链路已经完成
- Phase 5D 跨模块实证由用户延期执行，因此当前属于“功能开发闭环”，不是“全部业务模块最终验收闭环”
- Phase 5 收口时没有继续虚构阶段；当前收到真实文件展示需求后，新增扩展 Phase 6

### 扩展 Phase 6：通用文件字段

#### Phase 6A：文件协议与通用前端渲染

- 新需求已经提供真实目标：业务报告需要展示一个或多个文件，并允许用户点击文件名称访问文件地址
- 不新增 `FILES` 业务区块，不复制结算附件模板；复用现有 `KEY_VALUE / METRICS / TABLE / TREE_TABLE`
- `ReportSchemaVO.Item.valueType` 和 `ReportSchemaVO.Column.dataType` 使用统一值 `FILE_LIST`
- 文件值固定为数组，每项只包含 `name` 和 `url`；单文件也转换为长度为 1 的数组
- `ReportFieldBindingSpec` 计划增加可选 `fileUrlFieldId / fileNamePath / fileUrlPath`，`sourcePath` 指向两个叶子字段的共同文件数组
- 文件名和文件地址必须分别引用已发布、允许展示的叶子字段字典；文件名字典通过 `displayFormat=file_list` 声明展示语义
- 不发布整个文件对象数组：现有安全投影器会拒绝复制对象数组，放宽该限制则可能泄露未授权子字段
- 字段类型继续记录叶子字段的真实类型，不新增数据库字段或迁移
- 前端新增通用 `ReportFileList.vue`，统一支持键值、指标、普通表格和树表文件展示
- 前端只允许 `http://`、`https://` 和非协议相对的站内 `/` 地址，拒绝脚本协议及 `//example.com` 地址
- 报告可视化配置在文件字段下展示文件数组路径、文件名相对路径和文件地址相对路径
- 文件字段不能参与报告后追问的 `$source` 标量路径候选，也不能参与数值聚合
- 后台 Java 已由用户手工应用，涉及 `ReportSchemaVO`、`ReportFieldBindingSpec`、`ReportDefinitionValidator`、`ReportDefinitionResolver` 和 `ConfigurableReportSectionBuilder`
- 前端源码实现和补丁空白静态检查完成；按用户要求未运行构建或测试

#### Phase 6B：后台静态检查

- 已确认文件值协议、文件字段绑定、字段字典收集、文件列表构建、20 个文件数量限制和 URL 白名单逻辑均位于通用报告链路
- 已确认 `KEY_VALUE / METRICS / TABLE / TREE_TABLE` 统一返回 `FILE_LIST`，未新增结算、概算等业务模块专用判断
- 已确认文件名字段与文件地址字段必须属于同一个字典父路径
- 用户已经补充 `ReportDefinitionResolver.validateFileBinding` 的文件数组路径一致性校验
- 静态检查确认校验位于文件名和文件地址父路径校验之后，并复用现有受限路径解析方法
- Phase 6B 已完成；按用户要求未运行测试或编译

#### Phase 6C：结算文件双列展示

- 目标效果：每条结算记录分别展示“已盖章”和“未盖章”两列，每列允许显示多个可点击文件名
- 事实边界：`project_settlements_list` 当前由 `ProjectSettlementReportTemplate` 专用模板优先处理，工作流 `reportDefinition` 不参与该报告区块构建
- 现有专用模板已经返回 `rowKey / fileStatus / fileName / fileUrl`，因此不需要修改后台数据结构或复制附件数据
- 前端 `ProjectSettlementReport.vue` 按 `rowKey + fileStatus` 对附件分组，并复用通用 `ReportFileList.vue` 渲染安全链接
- 原“文件”单列已拆分为“已盖章”和“未盖章”，无文件时显示 `-`，空明细行列数同步调整为 7
- 表格最小宽度调整为 `980px`，窄屏继续使用横向滚动，避免新增文件列挤压金额和日期
- 前端补丁空白静态检查通过；按用户要求未运行测试、构建或浏览器验收
- 当前待用户使用包含 `fileList[]` 和 `notFileList[]` 的真实结算记录进行页面验收

##### 通用报告配置参考：结算附件

- 当前结算工作流存在专用 `ProjectSettlementReportTemplate`，因此以下 `reportDefinition` 只用于说明普通配置报告如何声明文件列，不改变当前结算页面
- 结算记录行路径参考：`$.workflowData.items[].data.items[].data.settlementInfos[].settlements[]`
- 已盖章文件名字段字典路径：`$.data.settlementInfos[].settlements[].fileList[].original`，`displayFormat=file_list`
- 已盖章文件地址字段字典路径：`$.data.settlementInfos[].settlements[].fileList[].url`
- 未盖章文件名字段字典路径：`$.data.settlementInfos[].settlements[].notFileList[].original`，`displayFormat=file_list`
- 未盖章文件地址字段字典路径：`$.data.settlementInfos[].settlements[].notFileList[].url`
- 四个字段字典必须属于结算详情能力、状态为 `PUBLISHED`、`visible=1`；文件字段不可聚合
- 已盖章报告字段绑定：`key=fileList`、`sourcePath=fileList[]`、`fileNamePath=original`、`fileUrlPath=url`
- 未盖章报告字段绑定：`key=notFileList`、`sourcePath=notFileList[]`、`fileNamePath=original`、`fileUrlPath=url`
- `fieldId` 和 `fileUrlFieldId` 必须选择当前环境的真实字段字典 ID，不能复制示例数字或自行猜测
- 配置排查：如果选择文件名字典后“字段标识”仍为 `original` 且没有出现文件地址配置项，说明前端收到的 `displayFormat` 不是 `file_list`，或者报告弹窗仍缓存修改前的字段字典
- 修正顺序：确认文件名字典展示格式为“文件列表”并保存，关闭后重新打开报告配置，删除原普通字段行后重新添加并选择该文件名字典
- 正确结果：已盖章字段自动生成 `key=fileList / sourcePath=fileList[]`，未盖章字段自动生成 `key=notFileList / sourcePath=notFileList[]`

#### Phase 6 后续步骤

1. Phase 6A：文件协议、后台构建和通用前端渲染已完成
2. Phase 6B：后台文件数组路径一致性校验和静态检查已完成
3. Phase 6C：结算文件双列展示已实现，待验收单文件、多文件、空文件和非法地址

#### 附加排查：页面空闲后身份服务偶发 503

- 该 503 由 `HeaderCurrentUserProvider` 调用 PM `/user/info` 时发生 `RestClientException` 触发；Redis Session 缺失或过期会返回 401，不是该错误的直接原因
- 当前 `ToolExecutorConfig` 已使用 `JdkClientHttpRequestFactory` 应用连接超时和读取超时，之前“超时未生效”的判断已经失效
- 当前 `HeaderCurrentUserProvider` 已只对 `/user/info` 网络传输异常立即重试一次，401、403 和其他明确 HTTP 状态不重试
- 重试日志只记录次数、异常类型和消息，不记录 Authorization、Cookie 或 Token
- `BusinessApiProperties` 不再重复注册为两个 Bean，之前的 `ToolExecutorConfig` 启动冲突已经按单 Bean 方式修正
- 开发配置中的超时单位为毫秒；`86400` 实际为 86.4 秒，不代表 24 小时，后续调整时必须按毫秒换算
- 当前只完成源码位置和调用规则静态核对，尚未取得页面长时间空闲后的新运行日志，因此不能声明 503 运行验收已经通过
- 不使用 Redis 中的旧权限快照兜底，避免权限撤销延迟和管理接口越权风险

#### 模型配置并发与审计 Ticket 02B 进度

- 已完成模型配置 `version` 乐观锁字段、更新链路和并发冲突返回
- 已完成模型配置新增、修改、状态切换的成功审计
- 已完成系统授权保存、人员授权保存和人员授权删除审计
- 已完成审计日志分页查询后端接口，支持操作人、动作、对象和时间范围筛选，每页最多 100 条
- 已完成前端模型编辑和启停操作的 `version` 透传，以及 409 并发冲突后的数据刷新
- 后端阶段均只完成源码位置和调用链静态检查，按约定未运行编译、测试或 Flyway
- 前端阶段只完成源码和调用关系静态检查，按约定未运行构建或测试
- 新增开发约定：后续列表分页查询必须提供 `Page<T>`、`@Param` Mapper 方法和对应 Mapper XML SQL
- 已完成前端审计日志页签，支持操作人、动作、对象类型、对象编码和时间范围筛选
- 已完成审计日志服务端分页展示、分页大小切换和延迟加载
- Ticket 02B 代码与调用链静态开发闭环已完成
- 待用户验收：数据库脚本应用、并发修改冲突、审计写入、筛选分页和页面展示
- 未执行范围：编译、测试、Flyway、前端构建和浏览器运行验收

#### 数据库聊天模型唯一数据源 Ticket 03 进度

- 总体目标：移除聊天模型 YAML 迁移兜底，MySQL 成为聊天模型配置唯一数据源
- 保持不变：Redis 只协调多实例缓存失效，本地继续缓存动态创建的模型客户端
- 第一阶段已完成：`ChatModelPolicyService` 的模型列表、会话创建、会话切换和聊天选模只读取数据库启用模型
- 第一阶段静态检查通过：无 YAML 依赖；无授权使用数据库启用模型；已有授权继续严格过滤
- 兼容规则：没有人员或系统授权时允许使用数据库启用模型；已有授权时继续严格按授权过滤
- 错误规则：数据库没有启用模型时返回明确的 `AI_SERVICE_UNAVAILABLE`，但不阻止应用启动
- 第二阶段已完成：`ModelClientRegistry` 已移除 YAML 客户端依赖和回退逻辑，指定模型和默认模型均只从数据库解析
- 第二阶段静态检查通过：指定模型不存在返回明确错误；数据库无可用默认模型时仅在实际调用处返回 `AI_SERVICE_UNAVAILABLE`，不影响应用启动
- 保持不变：60 秒本地客户端缓存、默认客户端并发锁、按模型编码失效，以及事务事件和 Redis 触发的缓存失效入口
- 实施计划：`docs/superpowers/plans/2026-08-12-database-only-chat-model.md`
- 第三阶段已完成：删除 `AgentModelProperties`、聊天 YAML 参数和基于自动装配 Builder 创建的 `ChatClient` Bean
- Spring AI 聊天自动配置已通过 `spring.ai.model.chat: none` 明确关闭；Embedding 继续使用 `spring.ai.model.embedding: openai` 和原有配置
- 第四阶段静态检查通过：模型列表、创建会话、切换模型、聊天选模和模型客户端创建均只使用数据库模型编码及运行配置
- 模型连接测试继续通过 `ModelConfigService.loadRuntimeConfig` 读取数据库配置，并由 `ModelClientFactory` 动态创建客户端
- 模型配置修改仍在事务提交后发布事件，当前实例立即失效本地缓存，并通过 Redis Pub/Sub 通知其他实例
- Redis 临时不可用时不回滚数据库事务，其他实例最迟通过 60 秒本地缓存 TTL 重新读取数据库
- 前端接口路径、请求字段和返回字段保持不变，因此本阶段未修改前端代码
- Ticket 03 代码与调用链静态开发闭环已完成
- 使用技术：Spring Boot、Spring AI 动态 `OpenAiChatModel`、MyBatis Plus、MySQL、Redis Pub/Sub、事务提交事件、`ConcurrentHashMap` 本地缓存和 60 秒 TTL
- 待用户运行验收：无默认模型时应用启动、数据库模型列表、默认模型、人员与系统授权、会话选模、模型连接测试、聊天调用和多实例缓存失效
- 未执行范围：后端编译、测试、Flyway、前端构建和浏览器运行验收

## 20. 扩展计划：工作流与报告傻瓜式配置

### 设计目标

- 在不改变现有 GraphSpec、发布版本、正式运行和报告优先级的前提下，为新增与已有工作流增加全页面四步快速配置向导
- 核心流程为：选择只读能力 -> 真实样例执行 -> 选择报告字段 -> ReportSchema 预览 -> 写入当前前端内存草稿
- 支持单能力查询和“列表 + 明细”两类常用结构；三个及以上能力继续使用高级编辑器
- 使用确定性规则生成工作流和报告配置，AI 不参与关键字段路径、节点映射和 ReportDefinition 生成
- 用户确认前不修改数据库草稿，确认后仍沿用原有校验、保存和发布流程

### 兼容边界

- 不新增数据库表或字段，不修改 GraphSpec 协议，不修改正式发布、聊天路由和报告 SSE 链路
- 专用 Java `ReportTemplate` 继续优先；项目结算等专用模板不被 ReportDefinition 覆盖
- 临时工作流预览保存 `DEBUG` 运行记录，临时报告预览复用该安全结果，不再次调用业务系统
- 报告字段只允许使用当前工作流能力中已发布、允许展示且在真实结果中出现的字段字典
- 节点、能力、输入或映射变化后必须重新执行样例；只修改 ReportDefinition 时允许复用原 `runId`

### 已确认实施阶段

1. Phase 1：后端临时工作流预览
2. Phase 2：后端临时报告预览
3. Phase 3：前端向导骨架、能力选择和样例执行
4. Phase 4：真实结果字段识别与 ReportDefinition 建议
5. Phase 5：ReportSchema 预览、差异确认和写入内存草稿
6. Phase 6：兼容性静态收口、使用文档和最终闭环总结

### 当前状态

- 完整设计已确认并写入 `docs/superpowers/specs/2026-08-12-workflow-report-quick-config-design.md`
- 已静态确认现有调试入口位于 `WorkflowDefinitionController`，调试实现复用 `WorkflowGraphSnapshotFactory`、`CapabilityInputSchemaValidator`、`GraphSpecRuntimeExecutor`、`WorkflowExecutionOutcomeFactory` 和 `WorkflowRunService`
- 已静态确认正式 `ReportSchemaBuilder` 和 `ReportDefinitionResolver` 依赖已发布版本；草稿报告预览需要增加基于临时编译图的独立入口，不能伪造发布版本
- 当前只完成设计固化，未修改任何业务代码，未运行编译、测试、Flyway、前端构建或浏览器验收
- 下一步：对设计文档完成确认后，生成六阶段逐文件实施计划，再进入 Phase 1 后端代码示例
- 六阶段逐文件实施计划已生成：`docs/superpowers/plans/2026-08-12-workflow-report-quick-config.md`
- 实施计划已经锁定后端 3 个新增文件、7 个修改文件，以及前端 7 个新增文件、4 个修改文件；不新增数据库迁移或依赖
- 当前进入 Phase 1：后端临时工作流预览；Codex 提供代码示例和修改位置，由用户手动应用
- Phase 1 已完成：新增 `draft-preview` 接口、临时请求 DTO、临时 GraphSpec 执行入口和排除 ReportDefinition 的执行结构校验值
- Phase 1 完整性检查已完成：旧 `/debug` 行为保持、READ 能力限制继续复用现有编译器、DEBUG 运行记录和失败状态链路完整
- Phase 1 检查时修复 `WorkflowGraphMaterial` 访问器误写问题，正确使用 `normalizedGraphSpecJson()`
- Phase 1 未运行编译、测试或接口请求；下一阶段为 Phase 2 后端临时报告预览
- Phase 2 已完成：新增 `draft-report-preview` 接口、报告预览 DTO 和 `WorkflowDraftReportPreviewService`
- 草稿字段策略和草稿 ReportDefinition 解析复用正式链路的字段字典发布状态、可见性、能力归属、路径和文件绑定校验
- 临时报告预览只接受带 `DRAFT_PREVIEW:` 内部标记、属于当前用户和工作流、状态成功且 30 分钟内的 DEBUG 运行
- 临时报告预览使用排除 ReportDefinition 的执行结构校验值；节点、能力、输入、分页或映射变化后旧 `runId` 会被拒绝
- 报告预览复用数据库中的安全结果，不再次调用业务系统，不触发 AI 分析；专用 Java ReportTemplate 继续优先
- 后端增加真实字段防线：ReportDefinition 引用字段必须实际出现在本次安全结果对应的字段策略中
- Phase 2 完整性检查完成：新增类型、构造注入、方法签名、括号、正式/草稿入口分流和无业务二次调用均已静态核对
- Phase 2 未运行编译、测试或接口请求；下一阶段为 Phase 3 前端向导骨架、能力选择和样例执行
- Phase 3 已完成：新增快速配置路由、工作流列表和编辑器入口、四步向导骨架、能力选择步骤和真实样例执行步骤
- 快速配置只显示已启用、已发布、`sideEffect=READ` 且 RequestBinding 可解析的能力，最多选择两个能力
- 能力目录按服务端 `total` 分页读取全部记录，避免单页限制导致能力缺失
- 单能力直接生成最小 GraphSpec；双能力在 Phase 3 先执行列表能力，待 Phase 4 根据真实数组与明细参数生成完整列表明细图，不使用默认 `records/id` 猜测
- 样例表单只暴露 RequestBinding 明确声明的 `INPUT` 路径，并保留 inputSchema 中对应字段的类型、枚举和中文标题；支持嵌套输入对象
- Phase 3 完整性检查完成：Vue 模板闭合、导入目标、路由、入口、分页加载、嵌套输入和无自动保存/发布均已静态核对
- 前端工作区已有的 `vite.config.js` 改动不属于本需求，已保留且未修改
- Phase 3 未运行前端构建、测试或浏览器验收；下一阶段为 Phase 4 真实结果字段识别与 ReportDefinition 建议
- Phase 4 已完成：新增真实结果路径扫描、字段字典精确匹配、ReportDefinition 确定性建议和字段确认页面
- 双能力模式不猜测 `records`、`id` 等默认字段：先从列表真实结果中选择对象数组，再把明细能力 RequestBinding 参数映射到真实列表行字段，最后执行完整“列表 -> FOREACH -> 明细”临时图
- 单能力模式直接使用上一步最终安全结果；双能力模式仅在完整列表明细图成功或部分成功后识别报告字段，避免将列表样例误作为最终报告
- 报告字段只保留当前临时图能力中已发布、允许展示且真实命中的字段字典；同名不同路径分别展示，不静默合并
- ReportDefinition 使用现有 `GENERIC_WORKFLOW_REPORT`、`ON_DEMAND`、`KEY_VALUE`、`METRICS` 和 `TABLE` 协议；多个对象数组要求人工选择，TABLE 默认最多勾选 12 列
- 文件字段只在同父数组下唯一匹配到 URL 字段时生成完整文件绑定，否则默认不选中并由配置人员处理
- Phase 4 完整性检查完成：临时图输入映射、对象数组识别、字段 ID、可见性、发布状态、文件绑定、区块路径、Vue 模板闭合和无自动保存/发布均已静态核对
- Phase 4 未运行前端构建、测试、接口请求或浏览器验收；下一阶段为 Phase 5 ReportSchema 预览、差异确认和写入编辑器内存草稿
- Phase 5 已完成：新增 ReportSchema 预览页面，复用 `draft-report-preview` 和步骤二的 `runId`，不再次调用业务系统
- 报告预览继续复用现有 `AiReport.vue` 固定组件；后端返回非通用报告类型时明确提示专用 Java 模板优先，不宣称 ReportDefinition 会覆盖模板
- 预览页面展示节点数量、能力数量、输入字段、报告区块和报告字段的新旧差异
- 报告字段或主要数组变化后旧 ReportSchema 立即失效，必须重新预览，避免把旧预览应用到新草稿
- 点击“应用到编辑器”仅通过模块内存交接 GraphSpec；原编辑器加载数据库草稿后接收一次性快速草稿并标记“有未保存修改”
- 快速配置没有调用保存、校验或发布接口；用户仍需在原编辑器使用既有校验、保存和发布按钮完成正式变更
- Phase 5 完整性检查完成：预览接口参数、旧预览失效、差异字段、一次性内存读取、编辑器 dirty 状态、Vue 模板闭合、括号和无浏览器存储均已静态核对
- Phase 5 未运行前端构建、测试、接口请求或浏览器验收；下一阶段为 Phase 6 兼容性静态收口、使用文档和最终闭环总结
- Phase 6 已完成：对六阶段新增和修改文件完成后端正式链路、临时链路、前端高级编辑链路和范围边界静态收口
- 正式 `/debug` 仍读取数据库草稿；发布仍重新读取数据库草稿并生成不可变版本；Agent 正式运行仍只读取已发布活动版本
- 正式报告仍保持“专用 Java ReportTemplate 优先、已发布版本 ReportDefinition 次之”；草稿入口只在临时预览服务中使用，不会进入正式聊天链路
- 临时执行只新增 DEBUG 运行记录及安全结果，不更新工作流定义表和版本表；临时报告预览只读取既有运行结果
- 原画布、列表明细模板、报告高级配置弹窗、调试面板、校验、保存和发布接口均保留；快速配置只是新增入口
- 本需求未新增数据库迁移、Redis、消息队列、模型调用、第三方依赖或持久化浏览器缓存
- 已新增配置人员使用说明：`docs/requirements/workflow-report-quick-config-user-guide.md`
- 最终静态检查完成：后端类型和调用点唯一、Java 括号平衡、前端模板闭合、JS 括号平衡、导入目标存在、`git diff --check` 无错误
- 按用户要求未运行后端编译、测试、Flyway、前端构建、接口请求或浏览器验收；这些运行结果不宣称已经通过
- 工作流与报告傻瓜式配置六阶段开发已最终闭环
