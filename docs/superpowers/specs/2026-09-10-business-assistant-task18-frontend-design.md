# Task18 业务助手前端视图设计

## 1. 结论

Task18 采用已经确认的“方案 A：会话式业务驾驶舱”。保留现有 AI 聊天页的左侧会话栏、消息流、底部输入框和流式回答机制，只增强结构化业务回答区域。

第一版以简单、易懂、容易维护为优先，不重做聊天页面，不引入新的状态管理库、组件库或通用渲染框架。前端只解释后端已经定义的回答协议，不推测合同、概算、出差、打卡、请假、报销等业务字段。

## 2. 已确认的用户体验

1. 项目首次查询仍是一条独立 AI 回答，展示项目上下文、数据集状态、核心指标、风险和明细。
2. 后续追问形成新的 AI 消息，继承后端确定的项目、人员和时间范围，不修改已完成的上一条回答。
3. 出差、打卡、请假和报销的关联统计由后端完成；前端只展示结论、指标、核对明细和数据完整性。
4. 用户明确提出导出格式后，前端展示报告任务状态；任务完成后提供下载。
5. 桌面端使用三列指标卡，窄屏自动降为两列或一列。

## 3. 当前代码事实

前端项目位于 `D:/TraeProject/enterprise-vue-admin`，技术栈为 Vue 3、Element Plus 和 Vite。

现有能力：

- `src/views/knowledge/AiChat/index.vue` 已具备会话、历史消息、SSE、断流恢复和输入区。
- `src/components/AiChat/AiReport.vue` 已具备报告标题、状态和章节编排。
- `src/components/AiChat/BlockRenderer.vue` 已支持 `TEXT`、`METRICS`、`KEY_VALUE`、`TABLE`、`TREE_TABLE`、`GROUP_TABLE`、`CALLOUT`、`STATUS` 和 `WARNINGS`。
- `src/utils/responseStream.js` 已能累计统一回答事件和最终快照。
- 后端已定义 `STATUS_LIST` 和 `ARTIFACT`，但前端尚未展示这两类区块。
- 后端 `AiResponse` 的 CHAT 协议版本为 2；现有前端快照校验只接受版本 1，需要补充兼容规则。
- 后端 CHAT 回答包含 `ResponseContext`，但现有前端没有保存该上下文，因此无法稳定展示项目、人员、范围和快照时间。

前端工作区已有与 Task18 无关的修改：`vite.config.js` 已修改，`src/api/mdaes.js` 未跟踪。Task18 不修改、不清理这两个文件。

## 4. 最小实现方案

### 4.1 协议接收

调整 `src/utils/responseStream.js`：

- REPORT 继续只接受协议版本 1。
- CHAT 接受协议版本 1 和 2，避免破坏历史消息；版本 2 按当前后端协议展示。
- 将后端 `document.context` 保存到消息的 `responseContext`。
- 不从 Markdown、会话标题或用户问题中猜测业务对象。

### 4.2 业务回答外壳

在 `src/views/knowledge/AiChat/index.vue` 中，对包含结构化 Blocks 的 CHAT 回答增加轻量业务卡片外壳：

- 标题优先使用 `responseContext.subjectLabel`；没有上下文时显示“业务查询结果”。
- 展示 `scopeLabel`、起止日期、快照时间和回答完成状态。
- `dataComplete=false` 或回答状态为 `PARTIAL` 时明确显示“部分完成”。
- 普通知识库 Markdown 回答、写操作表单和旧 REPORT 页面保持原逻辑。

不新增独立业务页面，也不在前端硬编码“合同、概算、现金流、产值”四个固定模块。

### 4.3 Block 展示

继续使用一个 `BlockRenderer.vue`，不为每个业务模块创建组件：

- `STATUS_LIST`：紧凑展示数据集名称、状态和安全提示；颜色只由 `tone` 映射。
- `METRICS`：桌面三列指标卡，显示 label、displayValue 和 unit。
- `KEY_VALUE`：展示基础信息和查询口径。
- `WARNINGS` / `CALLOUT`：展示风险和数据完整性说明。
- `TABLE` / `GROUP_TABLE` / `TREE_TABLE`：复用现有表格和分页逻辑。
- `ARTIFACT`：展示格式、文件名、任务状态、安全提示和过期时间；成功时提供下载，运行中提供刷新状态，失败时只显示安全错误。

未知 Block 继续安全降级为“不支持的内容类型”，不得直接渲染任意 HTML。

### 4.4 报告任务

新增一个聚焦的 API 文件 `src/api/agentReportTask.js`，只包含：

- 查询当前用户报告任务状态；
- 生成当前用户报告下载地址；
- 取消仍可取消的任务。

第一版不实现后台定时轮询中心。用户点击“刷新状态”时查询一次；任务完成后显示“下载报告”。这样逻辑最少，也不会产生页面切换后遗留定时器的问题。

报告下载必须使用后端 `/api/agent/report-tasks/{taskId}/download`，不能使用后端返回的本地路径，也不能自行拼接存储地址。

### 4.5 导出入口

- 已存在的旧 REPORT 本地 PDF/Excel 导出继续保留，不在本阶段重写。
- 新业务 CHAT 回答只有在后端返回 `ARTIFACT` 时展示真实报告任务和下载入口。
- 第一版不额外增加“点击按钮自动伪造一句导出提问”的逻辑，用户仍通过自然语言提出 PDF、DOCX 或 XLSX 格式，后端负责创建任务。

这与后端当前“根据用户需求导出对应格式”的边界一致，也避免前端维护另一套导出规则。

## 5. 预计修改文件

前端仓库 `D:/TraeProject/enterprise-vue-admin`：

1. `src/utils/responseStream.js`
   - 兼容 CHAT v2；保存安全回答上下文。
2. `src/views/knowledge/AiChat/index.vue`
   - 给结构化 CHAT 回答增加标题、范围、状态和卡片外壳；保持现有会话交互。
3. `src/components/AiChat/BlockRenderer.vue`
   - 增加 `STATUS_LIST`、`ARTIFACT`；调整指标、状态和风险的响应式样式。
4. `src/api/agentReportTask.js`
   - 报告状态、取消和下载路径。
5. `test/responseStream.test.js`
   - 覆盖 CHAT v2、REPORT v1、非法版本和 `ResponseContext` 保存。
6. `test/businessAssistantPresentation.test.js`
   - 覆盖状态列表和报告任务的安全展示映射；若实现时无需独立映射函数，则合并进现有协议测试，不为测试强行增加抽象。

后端仓库只新增本设计文档，不修改 Java、Mapper、XML、Flyway、配置或依赖。

## 6. 明确不做

- 不重做左侧会话栏和底部输入框。
- 不新增 Pinia、Vuex、Tailwind、图表库或新的 UI 组件库。
- 不把每个业务模块写成固定组件。
- 不在前端计算出差总额、缺卡日期、请假覆盖或报销金额。
- 不实现部门报告。
- 不实现超过 100 人的前端批次管理页。
- 不修改无关前端文件和现有脏工作区内容。
- 不新增数据库 SQL，因此不存在 Mapper XML 或 Flyway 修改。

## 7. 异常与安全

- 部分数据失败时保留成功区块，并显示 `PARTIAL` 或数据集安全提示。
- `ARTIFACT` 未完成时不显示下载按钮。
- 下载和状态查询仍由后端按当前登录人重新授权。
- 前端不展示存储路径、校验值、原始接口错误或未脱敏业务数据。
- 组件只使用 Vue 文本插值、现有 Markdown 安全渲染和 Element Plus，不使用 `v-html` 展示业务字段。

## 8. 验收标准

1. CHAT v2 最终快照可以正常展示，不再被误判为非法版本。
2. 项目、人员和追问回答均使用同一套结构化卡片，不依赖固定模块名称。
3. `STATUS_LIST`、`METRICS`、`WARNINGS`、`TABLE` 和 `ARTIFACT` 都有明确展示。
4. 追问结果形成新消息，上一条报告不被覆盖。
5. 报告完成前不能下载；完成后可以通过后端安全接口下载。
6. 普通 Markdown 问答、旧 REPORT、写操作确认和 SSE 恢复不回归。
7. 1366px 桌面宽度无横向溢出；760px 以下指标自动两列，480px 以下单列。
8. `npm test` 与 `npm run build` 通过。

## 9. 风险与控制

- `index.vue` 当前较大：本阶段只增加展示所需的少量计算和模板，不顺手拆页或重构会话逻辑。
- 前后端位于两个仓库：设计文档保存在后端任务分支，实际 UI 修改只发生在前端仓库，并分别报告验证结果。
- 设计效果图中的示例金额、日期和项目经理只是演示数据；正式界面全部以后端安全返回值为准。
