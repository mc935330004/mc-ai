# Workflow Condition Routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增可视化通用条件分支，移除项目分析工作流的多项目循环，并把业务能力列表收敛为一个可搜索选择入口。

**Architecture:** 项目主体定位继续在工作流之前完成，只有唯一且已授权的项目才能进入项目分析。GraphSpec 1.1 使用 `CONDITION` 节点和带 `branchKey` 的边执行结构化分支，未命中分支以稳定的 `SKIPPED` 原因传播；前端通过 Vue Flow 多输出锚点编辑分支。

**Tech Stack:** Java 17、Spring Boot 4、Jackson、Maven、JUnit 5、Mockito、Vue 3、Element Plus、Vue Flow、Node Test Runner

---

## 执行边界

- 后端生产代码只在聊天中输出，用户复制到指定路径。
- 后端测试代码由助手直接写入 `ai-agent/src/test/java`。
- 前端代码由助手直接写入 `D:/TraeProject/enterprise-vue-admin`。
- 所有新增业务注释使用中文。
- 本功能不需要 SQL、Mapper、Mapper XML 或 Flyway。
- 用户自行执行 Maven、前端构建和功能验证。

### Task 1: GraphSpec 1.1 条件协议

**Files:**
- Modify: `ai-common/src/main/java/org/example/ai/agent/common/enums/GraphNodeType.java`
- Create: `ai-agent/src/main/java/org/example/ai/agent/graph/config/ConditionOperator.java`
- Create: `ai-agent/src/main/java/org/example/ai/agent/graph/config/ConditionRightType.java`
- Create: `ai-agent/src/main/java/org/example/ai/agent/graph/config/ConditionClauseConfig.java`
- Create: `ai-agent/src/main/java/org/example/ai/agent/graph/config/ConditionBranchConfig.java`
- Create: `ai-agent/src/main/java/org/example/ai/agent/graph/config/ConditionNodeConfig.java`
- Create: `ai-agent/src/main/java/org/example/ai/agent/graph/runtime/ConditionDecision.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/graph/model/GraphEdgeSpec.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/graph/model/GraphSpec.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/graph/config/EndNodeConfig.java`
- Test: `ai-agent/src/test/java/org/example/ai/agent/graph/GraphSpecParserConditionTest.java`

- [ ] 新增解析测试，覆盖合法条件节点、`branchKey` 和多个结束表达式。
- [ ] 用户复制条件协议生产代码。
- [ ] 检查记录类的空集合不可变性、默认值和中文注释。
- [ ] 用户运行 `mvn -pl ai-agent -am -Dtest=GraphSpecParserConditionTest test`。

### Task 2: 编译期条件校验

**Files:**
- Modify: `ai-agent/src/main/java/org/example/ai/agent/graph/compiler/GraphSpecCompiler.java`
- Test: `ai-agent/src/test/java/org/example/ai/agent/graph/compiler/GraphSpecCompilerConditionTest.java`

- [ ] 新增失败测试：默认分支缺失、重复、位置错误。
- [ ] 新增失败测试：分支编码重复、条件缺失、非法表达式。
- [ ] 新增失败测试：条件边缺少或错误配置 `branchKey`。
- [ ] 新增成功测试：两个分支、一个默认分支、两条有效出边。
- [ ] 用户复制 `GraphSpecCompiler` 的精确修改代码。
- [ ] 用户运行指定编译器测试。

### Task 3: 条件计算执行器

**Files:**
- Create: `ai-agent/src/main/java/org/example/ai/agent/graph/runtime/executor/ConditionGraphNodeExecutor.java`
- Test: `ai-agent/src/test/java/org/example/ai/agent/graph/runtime/executor/ConditionGraphNodeExecutorTest.java`

- [ ] 新增 `EQ`、`NE`、`EMPTY`、`NOT_EMPTY` 测试。
- [ ] 新增数值比较、`CONTAINS`、`IN` 测试。
- [ ] 新增 `AND`、`OR`、首个匹配分支和默认分支测试。
- [ ] 新增数值类型错误失败测试。
- [ ] 用户复制完整执行器代码。
- [ ] 用户运行指定执行器测试。

### Task 4: 运行器分支激活与结束结果

**Files:**
- Modify: `ai-agent/src/main/java/org/example/ai/agent/graph/runtime/GraphNodeResult.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/graph/runtime/GraphSpecRuntimeExecutor.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/graph/runtime/executor/EndGraphNodeExecutor.java`
- Test: `ai-agent/src/test/java/org/example/ai/agent/graph/runtime/GraphSpecRuntimeConditionTest.java`

- [ ] 新增未命中分支不调用执行器的测试。
- [ ] 新增未命中状态向分支下游传播的测试。
- [ ] 新增两个分支汇入同一个结束节点的测试。
- [ ] 新增激活分支失败时工作流失败的测试。
- [ ] 新增所有结束表达式缺失时失败的测试。
- [ ] 用户复制三个生产文件的精确修改代码。
- [ ] 用户运行指定运行器测试和 `mvn -pl ai-agent -am test`。

### Task 5: 前端 GraphSpec 1.1 数据模型

**Files:**
- Modify: `D:/TraeProject/enterprise-vue-admin/src/constants/workflow.js`
- Modify: `D:/TraeProject/enterprise-vue-admin/src/utils/workflowGraph.js`
- Test: `D:/TraeProject/enterprise-vue-admin/test/workflow-condition.test.js`

- [ ] 先新增序列化、反序列化和本地校验测试。
- [ ] 增加 `CONDITION` 节点元数据和默认配置。
- [ ] 保留边的 `sourceHandle` 并序列化为 `branchKey`。
- [ ] 将结束节点升级为 `resultExpressions`。
- [ ] 增加与后台一致的分支校验。
- [ ] 运行 `npm test -- workflow-condition.test.js`。

### Task 6: 条件节点画布与属性编辑

**Files:**
- Modify: `D:/TraeProject/enterprise-vue-admin/src/views/knowledge/workflow/components/WorkflowCanvasNode.vue`
- Create: `D:/TraeProject/enterprise-vue-admin/src/views/knowledge/workflow/components/WorkflowConditionEditor.vue`
- Modify: `D:/TraeProject/enterprise-vue-admin/src/views/knowledge/workflow/components/WorkflowNodeInspector.vue`
- Modify: `D:/TraeProject/enterprise-vue-admin/src/views/knowledge/workflow/studio.vue`
- Modify: `D:/TraeProject/enterprise-vue-admin/src/views/knowledge/workflow/workflow.css`

- [ ] 在画布节点中按分支生成右侧输出锚点。
- [ ] 连线时将输出锚点编码保存为 `branchKey`。
- [ ] 连线上显示分支名称。
- [ ] 完成条件分支、逻辑、运算符和右值编辑。
- [ ] 删除分支时阻止遗留无效连线。
- [ ] 确认节点和表单均包含必要中文说明。

### Task 7: 单一业务能力选择入口

**Files:**
- Modify: `D:/TraeProject/enterprise-vue-admin/src/views/knowledge/workflow/components/WorkflowNodePalette.vue`
- Modify: `D:/TraeProject/enterprise-vue-admin/src/views/knowledge/workflow/studio.vue`
- Modify: `D:/TraeProject/enterprise-vue-admin/src/views/knowledge/workflow/workflow.css`
- Test: `D:/TraeProject/enterprise-vue-admin/test/workflow-capability-selector.test.js`

- [ ] 把长能力列表替换为一个“添加业务能力”入口。
- [ ] 使用现有 `pageCapabilities` 接口执行防抖搜索。
- [ ] 按 `domain` 分组并过滤 `OPTION_SOURCE`。
- [ ] 保留选中能力及画布已用能力的本地缓存。
- [ ] 选中后直接向画布添加 `CAPABILITY` 节点。
- [ ] 运行前端单元测试。

### Task 8: 项目工作流调整与回归检查

**Files:**
- No SQL or migration files
- Manual configuration: existing project query workflow draft
- Test: relevant business assistant and graph regression tests

- [ ] 删除项目分析草稿中的多项目 `FOREACH`。
- [ ] 保留“我的项目”候选列表和“分析此项目”选择动作。
- [ ] 确认项目选择令牌不进入 GraphSpec 输入或运行记录。
- [ ] 配置单项目详情与分析链路。
- [ ] 用户运行 `mvn test`。
- [ ] 用户运行 `npm run check`。
- [ ] 用户验证“我的项目”“模糊项目名称”“具体项目编码”“点击分析此项目”四条路径。

## 完成标准

- “我的项目”只返回项目列表。
- 未选择具体项目时不执行项目详情或项目分析能力。
- 条件节点只激活一个分支。
- 未激活分支不产生业务调用和失败状态。
- 业务能力节点通过单一可搜索入口添加。
- 前后端 GraphSpec 字段一致。
- 不新增 SQL 和数据库迁移。
