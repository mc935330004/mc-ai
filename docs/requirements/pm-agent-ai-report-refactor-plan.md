# PM Agent AI Report 重构方案与跨设备协作计划

> 项目：`mc-ai`
>
> 当前阶段：Phase 6 项目结算最终业务数据核对与闭环确认
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

- Phase 6 已启动
- 后台代码：暂无新增修改
- 前端代码：暂无新增修改
- 数据库、Flyway、配置和依赖：不修改
- 测试与编译：按用户要求不执行
- 最终闭环：待用户完成本阶段业务数据核对并明确确认

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
