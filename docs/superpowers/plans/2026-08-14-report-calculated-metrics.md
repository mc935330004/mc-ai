# 报告核心指标计算公式 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在核心指标区块中配置多个字段的列表汇总和四则运算，并以自定义名称展示计算结果。

**Architecture:** 计算配置作为 `ReportSectionSpec.calculations` 独立保存，不伪装为字段字典。后台只解析结构化配置并使用 `BigDecimal` 计算，前端提供选择式公式配置器，不开放任意脚本。

**Tech Stack:** Java 17、Spring Boot、Jackson、BigDecimal、Vue 3、Element Plus

---

### Task 1: 后台配置模型和发布校验（用户操作）

**Files:**
- Create: `ai-agent/src/main/java/org/example/ai/agent/graph/model/report/ReportAggregationType.java`
- Create: `ai-agent/src/main/java/org/example/ai/agent/graph/model/report/ReportCalculationOperator.java`
- Create: `ai-agent/src/main/java/org/example/ai/agent/graph/model/report/ReportCalculationTermSpec.java`
- Create: `ai-agent/src/main/java/org/example/ai/agent/graph/model/report/ReportCalculationSpec.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/graph/model/report/ReportSectionSpec.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/graph/compiler/ReportDefinitionValidator.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/workflow/answer/report/config/ReportDefinitionResolver.java`

- [ ] 新增结构化计算配置和枚举。
- [ ] 旧配置缺少 `calculations` 时默认空集合。
- [ ] 仅 METRICS 允许计算指标，并校验 key、名称、格式、项数、路径和运算符。
- [ ] Resolver 收集计算字段字典 ID，并校验字段已发布、允许展示、属于工作流且为数字类型。

### Task 2: 后台计算和报告构建（用户操作）

**Files:**
- Create: `ai-agent/src/main/java/org/example/ai/agent/workflow/answer/report/config/ReportMetricCalculationService.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/workflow/answer/report/config/ConfigurableReportSectionBuilder.java`

- [ ] 使用 `ReportValueReader.readMany()` 读取标量或列表值。
- [ ] 实现 SUM、AVG、COUNT、MAX、MIN。
- [ ] 实现乘除优先、加减后算和除零保护。
- [ ] 单个计算指标失败时返回空值并记录不含业务值的 warn 日志。
- [ ] 计算结果追加为现有 `ReportSchemaVO.Item`，不修改 ReportSchema 协议。

### Task 3: 前端计算指标配置器（Codex 操作）

**Files:**
- Modify: `D:/TraeProject/enterprise-vue-admin/src/views/knowledge/workflow/components/WorkflowReportConfigDialog.vue`
- Modify: `D:/TraeProject/enterprise-vue-admin/src/utils/workflowReportPathResolver.js`
- Create: `D:/TraeProject/enterprise-vue-admin/src/utils/reportMetricCalculation.js`
- Create: `D:/TraeProject/enterprise-vue-admin/test/reportMetricCalculation.test.js`

- [ ] 核心指标编辑器区分直接字段和计算指标。
- [ ] 支持自定义名称、展示格式、字段、汇总方式和四则运算符。
- [ ] 自动生成每个计算项的字段绝对路径。
- [ ] 保存前校验至少两个计算项、第一项无运算符、后续项必须有运算符。
- [ ] 其他区块不展示计算入口。

### Task 4: 联调和进度闭环

**Files:**
- Modify: `docs/requirements/pm-agent-ai-report-refactor-plan.md`

- [ ] Codex 检查用户后台代码是否位于正确位置且调用链完整。
- [ ] 完成前端静态检查，不运行编译、测试或构建。
- [ ] 用户验收旧报告兼容、数组汇总、运算优先级和异常结果。
- [ ] 用户确认后输出最终闭环总结和使用技术。
