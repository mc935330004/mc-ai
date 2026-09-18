# 工作流条件编排与业务能力选择器优化设计

## 1. 设计结论

本次优化采用“主体定位负责项目选择，GraphSpec 负责结构化条件分支”的分层方案。

- 用户只说“我的项目”时，主体定位层返回有权限访问的项目列表，不执行项目分析。
- 用户给出唯一项目名称、项目编码，或点击“分析此项目”携带有效选择令牌后，才执行项目详情与分析工作流。
- 项目分析工作流删除多项目 `FOREACH`，不再自动分析多个项目。
- 保留系统通用 `FOREACH` 节点以及并发保护，用于合同明细、人员记录等真实集合处理。
- GraphSpec 新增通用 `CONDITION` 节点，条件只读取结构化的 `$input` 和 `$vars`。
- 工作流节点库中的业务能力列表收敛为一个“添加业务能力”入口，通过可搜索下拉选择能力。

项目尚未正式上线，本次不保留旧 GraphSpec 结构的兼容代码，GraphSpec 版本从 `1.0` 调整为 `1.1`。

## 2. 目标与非目标

### 2.1 目标

1. 避免“我的项目”触发最多五个项目的循环详情查询。
2. 要求用户精确选择一个项目后再执行项目分析。
3. 提供可在其他工作流复用的通用条件节点。
4. 在画布上显示分支连线和分支名称。
5. 未命中的分支不执行、不报错、不调用业务接口。
6. 业务能力较多时能够通过搜索快速添加节点。
7. 复用现有能力分页接口，不新增数据库表和后台查询接口。

### 2.2 非目标

1. 不让条件节点直接解析用户自然语言。
2. 不在条件节点中调用大模型。
3. 不支持 JavaScript、SpEL、动态方法或自定义脚本。
4. 不提供“分析全部项目”。
5. 不取消系统通用循环节点的并发保护。
6. 不引入消息队列、规则引擎或新的工作流框架。

## 3. 项目查询流程

```text
用户提问
  ↓
业务意图解析
  ↓
项目主体定位
  ├─ 未指定项目
  │    ↓
  │  返回“我的项目”列表
  │    ↓
  │  用户点击“分析此项目”
  │
  ├─ 名称或编码无法唯一定位
  │    ↓
  │  返回候选项目列表
  │    ↓
  │  用户选择具体项目
  │
  └─ 已定位唯一项目
       ↓
     校验项目访问权限
       ↓
     执行项目详情与分析工作流
       ↓
     返回分析结果
```

项目分析工作流由原来的批量结构：

```text
项目列表 → FOREACH 最多五个项目 → 查询详情 → 合并结果
```

调整为单项目结构：

```text
唯一项目 → 查询项目详情 → 查询分析数据 → 返回结果
```

主体定位继续复用 `SubjectResolutionService`、`SubjectResolutionState` 和后端签发的项目选择令牌。工作流不得使用前端提交的项目 ID 绕过该链路。

项目选择令牌只在主体定位和权限校验层使用，不写入 GraphSpec 输入。需要进入工作流的路由信息转换为非敏感结构化字段，例如 `queryMode=PROJECT_DETAIL`；该字段只控制流程分支，不能替代项目访问权限校验。

## 4. GraphSpec 1.1 协议

### 4.1 条件节点

```json
{
  "id": "condition_project_selected",
  "type": "CONDITION",
  "name": "判断是否已选择项目",
  "config": {
    "branches": [
      {
        "key": "selected_project",
        "name": "已选择项目",
        "logic": "AND",
        "conditions": [
          {
            "leftExpression": "$input.queryMode",
            "operator": "EQ",
            "rightType": "CONSTANT",
            "rightValue": "PROJECT_DETAIL"
          }
        ]
      },
      {
        "key": "unselected_project",
        "name": "未选择项目",
        "defaultBranch": true,
        "conditions": []
      }
    ]
  }
}
```

### 4.2 条件分支连线

```json
{
  "id": "edge_condition_to_detail",
  "source": "condition_project_selected",
  "target": "capability_project_detail",
  "branchKey": "selected_project"
}
```

规则如下：

- `CONDITION` 出边必须配置 `branchKey`。
- `branchKey` 必须对应节点中已经定义的分支。
- 同一个条件节点的出边不能重复使用 `branchKey`。
- 每个分支必须且只能有一条直接出边。
- 非条件节点的出边不得配置 `branchKey`。
- 每个条件节点最多配置 10 个分支。
- 必须且只能配置一个默认分支。
- 默认分支必须位于最后且不能配置判断条件。

### 4.3 条件结构

每个普通分支支持 `AND` 或 `OR`，条件项包含：

| 字段 | 说明 |
|---|---|
| `leftExpression` | 左值，只允许 `$input` 或 `$vars` |
| `operator` | 确定性运算符 |
| `rightType` | `CONSTANT` 或 `EXPRESSION` |
| `rightValue` | 固定右值 |
| `rightExpression` | 右侧受限表达式 |

首版支持以下运算符：

| 运算符 | 说明 | 需要右值 |
|---|---|---:|
| `EQ` | 等于 | 是 |
| `NE` | 不等于 | 是 |
| `EMPTY` | 空值、空字符串或空集合 | 否 |
| `NOT_EMPTY` | 非空 | 否 |
| `GT` | 大于 | 是 |
| `GTE` | 大于等于 | 是 |
| `LT` | 小于 | 是 |
| `LTE` | 小于等于 | 是 |
| `CONTAINS` | 字符串或集合包含 | 是 |
| `IN` | 左值存在于右侧集合 | 是 |

数值比较只接受数值。类型不匹配时条件节点失败，避免把配置错误静默导向默认分支。

## 5. 编译校验

`GraphSpecCompiler` 在发布或调试前完成以下校验：

1. GraphSpec 版本必须为 `1.1`。
2. `CONDITION` 节点不配置 `outputKey`。
3. 普通分支至少包含一个条件。
4. 分支编码符合标识符规则且不能重复。
5. 默认分支唯一并位于最后。
6. 条件表达式只允许 `$input` 和 `$vars`。
7. `$secure` 和 `$item` 不允许出现在条件节点中。
8. 运算符与右值配置匹配。
9. 条件节点至少有两条出边。
10. 每个分支与一条出边精确对应。
11. 普通出边不能携带 `branchKey`。
12. 原有 DAG、环路、节点数量和边数量校验继续生效。

## 6. 运行语义

### 6.1 条件执行

1. 按界面顺序读取普通分支。
2. 按分支的 `AND` 或 `OR` 规则计算条件。
3. 第一条匹配成功的分支生效。
4. 普通分支都不匹配时选择默认分支。
5. 条件节点返回稳定的分支编码和名称。
6. 只有 `branchKey` 相同的出边被激活。

### 6.2 未命中分支

未命中的分支继续使用 `GraphNodeStatus.SKIPPED`，通过错误码区分原因：

```text
GRAPH_BRANCH_NOT_SELECTED
GRAPH_NODE_UPSTREAM_FAILED
```

运行器增加 `isBranchInactive()` 判断：

- 分支未命中的节点不调用节点执行器。
- 分支未命中状态沿当前分支继续向下传播。
- 分支未命中不计入工作流失败。
- 上游真实失败仍按原规则传播失败。
- 多条分支汇入一个节点时，只等待并校验实际激活的入边。

### 6.3 结束节点

结束节点由单个 `resultExpression` 调整为有序的 `resultExpressions`：

```json
{
  "resultExpressions": [
    "$vars.project_list",
    "$vars.project_analysis"
  ]
}
```

结束节点返回第一个实际存在的分支结果。所有表达式都不存在时返回 `GRAPH_END_RESULT_NOT_FOUND`。线性工作流只配置一个表达式。

## 7. Trace 与安全

条件节点只记录：

```json
{
  "selectedBranchKey": "selected_project",
  "selectedBranchName": "已选择项目",
  "evaluatedRuleCount": 1
}
```

禁止记录用户原始问题、选择令牌内容、Authorization、Cookie、请求头以及条件字段的敏感原始值。

## 8. 前端交互

### 8.1 条件节点

- 节点库增加紫色“条件判定”节点。
- 条件节点在画布上显示多个输出锚点。
- 锚点旁显示分支名称。
- 条件分支连线显示对应分支标签。
- 右侧属性面板编辑分支、逻辑和条件。
- 默认分支固定显示在最后。
- 删除分支前检查对应连线并给出提示。
- 保存前执行与后台一致的基础校验。

### 8.2 业务能力入口

左侧业务能力区域只保留一个“添加业务能力”入口：

- 点击后打开可搜索下拉层。
- 复用 `/api/agent/capabilities/pageList`。
- 使用 `keyword`、`domain`、`current` 和 `size` 查询。
- 只显示已启用、已发布的能力。
- `OPTION_SOURCE` 能力继续隐藏。
- `WRITE` 能力显示风险标记。
- 按 `domain` 分组展示。
- 选中能力后直接创建 `CAPABILITY` 节点。
- 能力详情、报告配置等现有场景继续维护所需的能力缓存。

## 9. 异常处理

| 场景 | 处理结果 |
|---|---|
| 没有默认分支 | 编译失败 |
| 多个默认分支 | 编译失败 |
| 分支编码重复 | 编译失败 |
| 条件出边缺少 `branchKey` | 编译失败 |
| `branchKey` 不存在 | 编译失败 |
| 普通出边配置 `branchKey` | 编译失败 |
| 表达式读取不到字段 | 按 `null` 参与判断 |
| 数值运算遇到非数值 | 条件节点失败 |
| 普通条件均不匹配 | 执行默认分支 |
| 激活分支调用失败 | 工作流失败 |
| 未激活分支 | `SKIPPED`，不算失败 |
| 所有结束表达式均不存在 | 结束节点失败 |

## 10. 影响范围

### 10.1 后端生产代码

主要影响：

```text
ai-common/src/main/java/org/example/ai/agent/common/enums/GraphNodeType.java
ai-agent/src/main/java/org/example/ai/agent/graph/model/GraphEdgeSpec.java
ai-agent/src/main/java/org/example/ai/agent/graph/model/GraphSpec.java
ai-agent/src/main/java/org/example/ai/agent/graph/config/
ai-agent/src/main/java/org/example/ai/agent/graph/compiler/GraphSpecCompiler.java
ai-agent/src/main/java/org/example/ai/agent/graph/runtime/GraphSpecRuntimeExecutor.java
ai-agent/src/main/java/org/example/ai/agent/graph/runtime/GraphNodeResult.java
ai-agent/src/main/java/org/example/ai/agent/graph/runtime/executor/
```

不新增数据表、Mapper、Mapper XML 或 Flyway 迁移。

### 10.2 前端代码

主要影响：

```text
D:/TraeProject/enterprise-vue-admin/src/constants/workflow.js
D:/TraeProject/enterprise-vue-admin/src/utils/workflowGraph.js
D:/TraeProject/enterprise-vue-admin/src/views/knowledge/workflow/studio.vue
D:/TraeProject/enterprise-vue-admin/src/views/knowledge/workflow/components/WorkflowNodePalette.vue
D:/TraeProject/enterprise-vue-admin/src/views/knowledge/workflow/components/WorkflowNodeInspector.vue
D:/TraeProject/enterprise-vue-admin/src/views/knowledge/workflow/components/WorkflowCanvasNode.vue
D:/TraeProject/enterprise-vue-admin/src/views/knowledge/workflow/workflow.css
```

允许新增一个职责单一的条件配置组件，避免继续扩大 `WorkflowNodeInspector.vue`。

## 11. 测试范围

后端至少覆盖：

1. 条件节点编译成功。
2. 默认分支缺失、重复和位置错误。
3. 分支编码与出边不一致。
4. 普通边错误携带 `branchKey`。
5. `AND`、`OR` 和各运算符。
6. 默认分支执行。
7. 未命中分支不调用能力执行器。
8. 激活分支失败正确终止工作流。
9. 两条分支汇入同一个结束节点。
10. Trace 不保存条件原始值。
11. 原有线性工作流和 `FOREACH` 工作流回归。

前端至少覆盖本地结构校验、条件节点序列化、分支连线生成和能力选择器搜索。具体构建和功能验证由用户执行。

## 12. 协作边界

- 后端生产代码由助手按文件路径和具体方法输出完整示例，用户复制修改。
- 后端测试代码由助手直接写入项目。
- 前端代码由助手直接修改。
- 每段代码包含必要的中文注释，保持当前紧凑编码风格。
- 如需手写业务 SQL，必须放入对应 `Mapper.xml`；本次设计不需要 SQL。
- 每个阶段结束后检查需求符合性和代码范围，并列出用户需要执行的验证命令。
