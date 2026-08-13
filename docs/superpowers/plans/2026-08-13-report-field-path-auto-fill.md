# Report Field Path Auto Fill Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有报告配置弹窗中，根据区块类型、当前 GraphSpec 和所选字段字典自动生成只读的对象路径、数据行路径和字段取值路径。

**Architecture:** 新增一个无 Vue 依赖的纯路径解析工具，负责从 GraphSpec 识别能力所在的主流程或 FOREACH 层级，并把能力字段字典路径转换为工作流最终结果路径。报告配置组件仅调用解析工具、展示只读路径、限制同对象字段混选；工作流编辑器只负责把当前 GraphSpec 传入弹窗。

**Tech Stack:** Vue 3、Element Plus、JavaScript ES Modules、Node.js 内置测试运行器

---

## File Structure

- Create: `D:/TraeProject/enterprise-vue-admin/src/utils/workflowReportPathResolver.js`
  - 解析 GraphSpec、定位能力结果层级、转换字段字典路径、计算共同对象/数组路径。
- Create: `D:/TraeProject/enterprise-vue-admin/test/workflowReportPathResolver.test.js`
  - 覆盖主流程、单层 FOREACH、双层列表详情、不同对象冲突和重复能力歧义。
- Modify: `D:/TraeProject/enterprise-vue-admin/src/views/knowledge/workflow/studio.vue`
  - 把当前 GraphSpec 传入报告配置弹窗。
- Modify: `D:/TraeProject/enterprise-vue-admin/src/views/knowledge/workflow/components/WorkflowReportConfigDialog.vue`
  - 将路径改为只读展示，选择字段或区块类型时自动刷新路径，并保留历史配置兼容。
- Modify: `D:/IdeaProjects/mc-ai/docs/requirements/pm-agent-ai-report-refactor-plan.md`
  - 同步阶段完成内容和下一阶段状态。

### Task 1: GraphSpec 路径解析工具

**Files:**
- Create: `D:/TraeProject/enterprise-vue-admin/src/utils/workflowReportPathResolver.js`
- Create: `D:/TraeProject/enterprise-vue-admin/test/workflowReportPathResolver.test.js`

- [ ] **Step 1: 编写纯函数测试用例**

测试数据必须覆盖：

```js
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  resolveReportFieldPath,
  resolveReportSectionPaths
} from '../src/utils/workflowReportPathResolver.js'

test('双层FOREACH详情字段生成完整安全结果路径', () => {
  const resolved = resolveReportFieldPath(graphSpec, {
    capabilityCode: 'project_detail',
    fieldPath: '$.data.records[].projectCode',
    fieldName: 'projectCode'
  })
  assert.equal(resolved.absolutePath, '$.items[].data.items[].data.records[].projectCode')
})

test('TABLE字段生成共同数据行路径和相对字段路径', () => {
  const resolved = resolveReportSectionPaths(graphSpec, 'TABLE', fields)
  assert.equal(resolved.rowPath, '$.items[].data.items[].data.records[]')
  assert.deepEqual(resolved.fields.map(field => field.sourcePath), ['projectCode', 'projectName'])
})
```

- [ ] **Step 2: 实现最小路径解析接口**

工具只导出以下稳定接口：

```js
export function resolveReportFieldPath(graphSpec, field) {
  // 返回 { absolutePath, objectPath, rowPath, relativePath, capabilityCode }
}

export function resolveReportSectionPaths(graphSpec, sectionType, fields) {
  // 返回 { objectPath, rowPath, fields: [{ fieldId, sourcePath }], error }
}
```

实现规则：

- 遍历主图和 `FOREACH.config.body`，记录能力节点及其循环深度。
- 能力必须能通过所在图的 `END.resultExpression` 进入父级结果。
- 字段字典路径先去除能力响应固定信封 `$.data`，再为每层 FOREACH 前置 `items[].data`。
- `KEY_VALUE/METRICS` 返回绝对字段路径和共同对象父路径。
- `TABLE/TREE_TABLE` 返回最近共同数组父路径和行内相对字段路径。
- 同能力多位置、不同能力、不同对象或不同数组返回明确 `error`，不猜测。
- 所有新增代码写中文注释，但注释中不添加“中文注释”字样。

- [ ] **Step 3: 保留用户验收命令但本次不执行**

```powershell
Set-Location D:\TraeProject\enterprise-vue-admin
npm test -- workflowReportPathResolver.test.js
```

预期：主流程、单层循环、双层循环和冲突用例全部通过。

### Task 2: 将当前 GraphSpec 注入报告配置组件

**Files:**
- Modify: `D:/TraeProject/enterprise-vue-admin/src/views/knowledge/workflow/studio.vue`

- [ ] **Step 1: 增加组件属性**

在 `WorkflowReportConfigDialog` 上增加：

```vue
:graph-spec="currentGraphSpec()"
```

只传递当前内存草稿，不发起接口请求，不保存工作流。

- [ ] **Step 2: 静态核对数据流**

确认 `currentGraphSpec()` 仍包含当前节点、连线、输入 Schema 和报告定义，且没有产生递归修改。

### Task 3: 报告字段路径只读自动补全

**Files:**
- Modify: `D:/TraeProject/enterprise-vue-admin/src/views/knowledge/workflow/components/WorkflowReportConfigDialog.vue`

- [ ] **Step 1: 接收 GraphSpec 并接入解析工具**

新增属性和导入：

```js
import { resolveReportSectionPaths } from '@/utils/workflowReportPathResolver'

graphSpec: {
  type: Object,
  default: null
}
```

- [ ] **Step 2: 路径控件改为只读**

- “字段所在对象路径”保留显示并增加 `readonly`。
- “数据行路径”保留显示并增加 `readonly`。
- 每个字段的“结果取值路径”增加 `readonly`。
- 文件名和文件地址相对路径增加 `readonly`；文件地址字段仍使用受限下拉选择。
- 帮助文字改为“路径由区块类型、工作流结构和字段字典自动生成”。

- [ ] **Step 3: 选择字段后自动更新整个区块路径**

新增一个职责单一的方法：

```js
function refreshSectionPaths({ preserveHistory = false } = {}) {
  const selectedFields = sectionDraft.value.fields
    .map(binding => availableFields.value.find(field => Number(field.id) === Number(binding.fieldId)))
    .filter(Boolean)
  const resolved = resolveReportSectionPaths(props.graphSpec, sectionDraft.value.type, selectedFields)
  if (resolved.error) return resolved.error
  // 根据区块类型写入只读展示值和现有 ReportDefinition 字段。
  return ''
}
```

约束：

- 新增或更换字段时先解析，失败则恢复该行上一次字段选择并提示原因。
- 删除字段后重新计算剩余字段路径。
- 切换区块类型后重新计算绝对路径或相对路径。
- 打开历史区块时只回显原路径，不主动重写；用户修改字段或类型后才重新生成。
- 第一个字段确定区块对象，后续候选字段过滤到同一能力与同一对象。

- [ ] **Step 4: 文件字段路径自动维护**

- 根据文件名字段和用户选择的同父级 URL 字段计算文件数组路径。
- 自动写入 `fileNamePath`、`fileUrlPath`、`sourcePath`。
- URL 字段不在同一个文件数组时保持现有错误提示。

- [ ] **Step 5: 收紧应用前校验**

- 不再接受用户自由输入路径。
- 应用区块前重新调用解析工具，并与展示路径一致。
- 历史区块未修改时继续使用历史路径通过现有格式校验。
- 修改后的区块如果无法解析则阻止应用并显示具体原因。

### Task 4: 静态完整性检查与进度同步

**Files:**
- Modify: `D:/IdeaProjects/mc-ai/docs/requirements/pm-agent-ai-report-refactor-plan.md`

- [ ] **Step 1: 全局引用检查**

```powershell
rg -n "workflowReportPathResolver|graph-spec|refreshSectionPaths" D:\TraeProject\enterprise-vue-admin\src D:\TraeProject\enterprise-vue-admin\test
```

确认导入目标存在、组件属性名称一致、没有旧的可编辑路径事件残留。

- [ ] **Step 2: 差异格式检查**

```powershell
git -C D:\TraeProject\enterprise-vue-admin diff --check
git -C D:\IdeaProjects\mc-ai diff --check
```

- [ ] **Step 3: 同步项目进度**

记录：

- 路径自动补全已实现的区块类型和 GraphSpec 结构。
- 历史配置兼容规则。
- 本次没有修改后端、数据库、Flyway、Redis 和正式报告协议。
- 按用户约定未运行测试、构建和浏览器验收。
- 提醒下一阶段由用户进行页面验收。

- [ ] **Step 4: 用户验收命令仅提供、不执行**

```powershell
Set-Location D:\TraeProject\enterprise-vue-admin
npm test -- workflowReportPathResolver.test.js
npm run build
```

预期：测试通过且 Vite 构建成功。
