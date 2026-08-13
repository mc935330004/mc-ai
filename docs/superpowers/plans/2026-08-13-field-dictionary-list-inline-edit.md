# 字段字典列表快捷编辑实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 删除字段字典列表中的路径列，支持四个字段开关列表内即时保存，统一空值默认规则，并在编辑或详情返回时恢复筛选和分页状态。

**Architecture:** 后端新增只更新四个布尔配置的轻量接口，完整保存接口继续负责新增和编辑字段；前端列表使用现有 `el-switch` 和请求工具完成乐观切换、失败回滚。列表状态通过 Vue Router 查询参数传递，空值规则通过前后端同名规则保持一致，历史数据使用一条明确 SQL 收口。

**Tech Stack:** Java 17、Spring Boot、MyBatis Plus、Vue 3、Vue Router、Element Plus、MySQL

---

## 文件结构

后端：

- 新建 `ai-agent/src/main/java/org/example/ai/agent/capability/dto/FieldDictionaryDisplayOptionsDTO.java`：四个开关的专用请求模型。
- 修改 `ai-agent/src/main/java/org/example/ai/agent/capability/controller/FieldDictionaryController.java`：暴露轻量更新接口。
- 修改 `ai-agent/src/main/java/org/example/ai/agent/capability/service/FieldDictionaryService.java`：声明更新方法。
- 修改 `ai-agent/src/main/java/org/example/ai/agent/capability/service/impl/FieldDictionaryServiceImpl.java`：校验 0/1、维护显示约束和空值默认规则。
- 修改 `ai-agent/src/main/resources/db/migration/V11__create_action_audit_log.sql`：追加历史空值显示文本收口 SQL。

前端：

- 修改 `D:/TraeProject/enterprise-vue-admin/src/api/knowledgeFieldDictionary.js`：新增四开关更新请求。
- 修改 `D:/TraeProject/enterprise-vue-admin/src/utils/fieldDictionaryDisplay.js`：集中维护数字类型和空值默认值规则。
- 修改 `D:/TraeProject/enterprise-vue-admin/src/views/knowledge/field-dictionary/index.vue`：删路径列、增加开关、即时保存和路由状态。
- 修改 `D:/TraeProject/enterprise-vue-admin/src/views/knowledge/field-dictionary/save.vue`：空值只读自动值和返回状态。
- 修改 `D:/TraeProject/enterprise-vue-admin/src/views/knowledge/field-dictionary/detail.vue`：返回状态。
- 新建 `D:/TraeProject/enterprise-vue-admin/test/fieldDictionaryDisplay.test.js`：最小纯函数回归用例，本次不执行。

文档：

- 修改 `docs/requirements/pm-agent-ai-report-refactor-plan.md`：同步阶段进度。

---

### Task 1: 增加字段开关轻量更新接口

**Files:**
- Create: `ai-agent/src/main/java/org/example/ai/agent/capability/dto/FieldDictionaryDisplayOptionsDTO.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/capability/controller/FieldDictionaryController.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/capability/service/FieldDictionaryService.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/capability/service/impl/FieldDictionaryServiceImpl.java`

- [ ] **Step 1: 新增专用 DTO**

```java
@Data
public class FieldDictionaryDisplayOptionsDTO {
    private Integer visible;
    private Integer requiredOutput;
    private Integer searchable;
    private Integer aggregatable;
}
```

- [ ] **Step 2: 在 Service 声明轻量更新方法**

```java
Boolean updateDisplayOptions(
        Long id,
        FieldDictionaryDisplayOptionsDTO dto
);
```

- [ ] **Step 3: 在 Controller 增加接口**

```java
@PostMapping("/{id}/display-options")
public Result<Boolean> updateDisplayOptions(
        @PathVariable Long id,
        @RequestBody FieldDictionaryDisplayOptionsDTO dto) {
    return Result.success(
            fieldDictionaryService.updateDisplayOptions(id, dto)
    );
}
```

- [ ] **Step 4: 在 ServiceImpl 只更新四个字段**

实现要求：

- `id` 不存在时抛出 `BusinessException(404, "字段字典不存在：" + id)`。
- DTO 或任一字段为空、不是 `0/1` 时抛出 400。
- `visible=0` 时强制 `requiredOutput=0`。
- 使用 `lambdaUpdate()` 只 set 四个配置列，不调用 `saveField()`，不修改 `createdAt`。

```java
return lambdaUpdate()
        .eq(FieldDictionary::getId, id)
        .set(FieldDictionary::getVisible, visible)
        .set(FieldDictionary::getRequiredOutput, requiredOutput)
        .set(FieldDictionary::getSearchable, dto.getSearchable())
        .set(FieldDictionary::getAggregatable, dto.getAggregatable())
        .update();
```

- [ ] **Step 5: 静态核对调用边界**

```powershell
rg -n "display-options|updateDisplayOptions" `
  D:\IdeaProjects\mc-ai\ai-agent\src\main\java
```

预期：Controller、Service、ServiceImpl 和 DTO 形成完整调用链，Mapper 无新增 XML。

---

### Task 2: 统一空值显示规则并清理历史数据

**Files:**
- Modify: `ai-agent/src/main/java/org/example/ai/agent/capability/service/impl/FieldDictionaryServiceImpl.java`
- Modify: `ai-agent/src/main/resources/db/migration/V11__create_action_audit_log.sql`

- [ ] **Step 1: 在 ServiceImpl 增加数字类型集合和默认值方法**

```java
private static final Set<String> NUMBER_FIELD_TYPES = Set.of(
        "number", "integer", "int", "long", "float",
        "double", "decimal", "bigdecimal", "numeric"
);

private String resolveNullDisplayText(String fieldType) {
    String normalized = fieldType == null
            ? ""
            : fieldType.trim().toLowerCase(Locale.ROOT);
    return NUMBER_FIELD_TYPES.contains(normalized) ? "0" : null;
}
```

- [ ] **Step 2: 完整保存统一使用类型默认值**

用下列赋值替换“当前数据中未提供”兜底：

```java
entity.setNullDisplayText(
        resolveNullDisplayText(dto.getFieldType())
);
```

该规则由后台强制执行，不接受旧前端传入的自定义默认文字。

- [ ] **Step 3: 在 V11 末尾追加历史数据更新 SQL**

```sql
UPDATE ai_field_dictionary
SET null_display_text = CASE
    WHEN LOWER(TRIM(field_type)) IN (
        'number', 'integer', 'int', 'long', 'float',
        'double', 'decimal', 'bigdecimal', 'numeric'
    ) THEN '0'
    ELSE NULL
END;
```

风险提示：这是用户明确要求的全量覆盖；不修改表结构，不新建 Flyway 文件，因为用户已说明 Flyway 未启用且本项目 SQL 统一维护在 V11。

- [ ] **Step 4: 准备只读验收 SQL，本次不执行更新 SQL**

```sql
SELECT field_type, null_display_text, COUNT(*)
FROM ai_field_dictionary
GROUP BY field_type, null_display_text
ORDER BY field_type, null_display_text;
```

预期：由用户执行更新 SQL 后，数字类型只有 `0`，其他类型为 `NULL`。

---

### Task 3: 增加前端空值默认规则纯函数

**Files:**
- Modify: `D:/TraeProject/enterprise-vue-admin/src/utils/fieldDictionaryDisplay.js`
- Create: `D:/TraeProject/enterprise-vue-admin/test/fieldDictionaryDisplay.test.js`

- [ ] **Step 1: 增加最小回归用例**

```javascript
import test from 'node:test'
import assert from 'node:assert/strict'
import { resolveNullDisplayText } from '../src/utils/fieldDictionaryDisplay.js'

test('数字类型空值默认显示0', () => {
  assert.equal(resolveNullDisplayText('number'), '0')
  assert.equal(resolveNullDisplayText('INTEGER'), '0')
})

test('非数字类型空值默认留空', () => {
  assert.equal(resolveNullDisplayText('string'), '')
  assert.equal(resolveNullDisplayText('date'), '')
})
```

- [ ] **Step 2: 实现纯函数**

```javascript
const NUMBER_FIELD_TYPES = new Set([
  'number', 'integer', 'int', 'long', 'float',
  'double', 'decimal', 'bigdecimal', 'numeric'
])

export function resolveNullDisplayText(fieldType) {
  return NUMBER_FIELD_TYPES.has(String(fieldType || '').trim().toLowerCase())
    ? '0'
    : ''
}
```

- [ ] **Step 3: 只做语法检查，不执行测试**

```powershell
node --check D:\TraeProject\enterprise-vue-admin\src\utils\fieldDictionaryDisplay.js
node --check D:\TraeProject\enterprise-vue-admin\test\fieldDictionaryDisplay.test.js
```

预期：无语法错误。

---

### Task 4: 优化列表列和四个即时保存开关

**Files:**
- Modify: `D:/TraeProject/enterprise-vue-admin/src/api/knowledgeFieldDictionary.js`
- Modify: `D:/TraeProject/enterprise-vue-admin/src/views/knowledge/field-dictionary/index.vue`

- [ ] **Step 1: 新增前端请求函数**

```javascript
export function updateFieldDictionaryDisplayOptions(id, data) {
  return request({
    url: `/api/agent/dictionaries/${id}/display-options`,
    method: 'post',
    data
  })
}
```

- [ ] **Step 2: 删除字段路径列**

删除：

```vue
<el-table-column prop="fieldPath" label="字段路径" min-width="220" />
```

- [ ] **Step 3: 四列改为开关**

每个开关使用当前项目真实 0/1 语义；`visible/requiredOutput` 的是值为 `1`，`searchable/aggregatable` 的是值为 `0`。

```vue
<el-switch
  v-model="row.visible"
  :active-value="1"
  :inactive-value="0"
  :loading="savingRowId === row.id"
  @change="saveDisplayOptions(row, snapshot)"
/>
```

回答规则增加：

```vue
:disabled="normalizeDisplayFlag(row.visible, 1) === 0 || savingRowId === row.id"
```

- [ ] **Step 4: 实现失败回滚**

在切换前保存当前行四个值；保存时组装：

```javascript
const payload = {
  visible: normalizeDisplayFlag(row.visible, 1),
  requiredOutput: normalizeDisplayFlag(row.visible, 1) === 1
    ? normalizeDisplayFlag(row.requiredOutput, 0)
    : 0,
  searchable: row.searchable === 0 ? 0 : 1,
  aggregatable: row.aggregatable === 0 ? 0 : 1
}
```

请求失败恢复快照；请求成功把后台约束后的 `requiredOutput` 写回当前行。

- [ ] **Step 5: 保持列表不整页刷新**

成功仅提示“字段配置已更新”，不调用 `loadList()`；删除流程仍按原逻辑重新加载。

---

### Task 5: 通过路由参数保存筛选和分页状态

**Files:**
- Modify: `D:/TraeProject/enterprise-vue-admin/src/views/knowledge/field-dictionary/index.vue`
- Modify: `D:/TraeProject/enterprise-vue-admin/src/views/knowledge/field-dictionary/save.vue`
- Modify: `D:/TraeProject/enterprise-vue-admin/src/views/knowledge/field-dictionary/detail.vue`

- [ ] **Step 1: 列表页接入 `useRoute` 并恢复参数**

解析规则：

- `capabilityCode`：字符串，默认空。
- `current`：正整数，默认 1。
- `size`：只允许 10、20、50、100，默认 10。

- [ ] **Step 2: 列表操作同步查询参数**

统一使用：

```javascript
router.replace({
  name: 'KnowledgeFieldDictionaryIndex',
  query: {
    capabilityCode: searchForm.capabilityCode || undefined,
    current: String(pagination.current),
    size: String(pagination.size)
  }
})
```

查询、重置、翻页和切换每页条数先更新本地状态，再同步 URL 和加载数据。

- [ ] **Step 3: 新增、编辑、详情携带当前查询参数**

```javascript
router.push({
  name: 'KnowledgeFieldDictionarySave',
  params: row?.id ? { id: row.id } : {},
  query: route.query
})
```

详情同样携带 `route.query`。

- [ ] **Step 4: 保存页与详情页显式返回列表**

```javascript
router.push({
  name: 'KnowledgeFieldDictionaryIndex',
  query: route.query
})
```

“返回列表”“取消”和保存成功都使用该函数，不再依赖浏览器历史栈。

---

### Task 6: 编辑页空值显示自动化

**Files:**
- Modify: `D:/TraeProject/enterprise-vue-admin/src/views/knowledge/field-dictionary/save.vue`

- [ ] **Step 1: 导入空值默认规则**

```javascript
import {
  enforceFieldDisplayRules,
  normalizeDisplayFlag,
  resolveNullDisplayText
} from '@/utils/fieldDictionaryDisplay'
```

- [ ] **Step 2: 字段类型切换时自动赋值**

```vue
<el-select
  v-model="formData.fieldType"
  @change="handleFieldTypeChange"
>
```

```javascript
function handleFieldTypeChange(fieldType) {
  formData.nullDisplayText = resolveNullDisplayText(fieldType)
}
```

- [ ] **Step 3: 空值显示输入框改为只读**

```vue
<el-input
  v-model="formData.nullDisplayText"
  readonly
  placeholder="非数字类型不设置默认值"
/>
```

- [ ] **Step 4: 详情加载和保存使用同一规则**

加载详情后：

```javascript
formData.nullDisplayText = resolveNullDisplayText(formData.fieldType)
```

保存 payload：

```javascript
nullDisplayText: resolveNullDisplayText(formData.fieldType) || undefined
```

---

### Task 7: 同步进度并静态检查

**Files:**
- Modify: `docs/requirements/pm-agent-ai-report-refactor-plan.md`

- [ ] **Step 1: 同步阶段功能**

记录：

- 列表路径列已移除，但完整编辑页继续保留路径。
- 四个开关支持列表内直接保存和失败回滚。
- 数字类型空值默认 `0`，其他类型为空。
- 历史 SQL 已准备。
- 筛选和分页通过路由恢复。
- 本次未修改表结构、工作流和报告协议。

- [ ] **Step 2: JavaScript 和 Vue 静态语法检查**

```powershell
node --check D:\TraeProject\enterprise-vue-admin\src\api\knowledgeFieldDictionary.js
node --check D:\TraeProject\enterprise-vue-admin\src\utils\fieldDictionaryDisplay.js
node --check D:\TraeProject\enterprise-vue-admin\test\fieldDictionaryDisplay.test.js
```

对三个 Vue 文件抽取 `<script setup>` 后执行 `node --check --input-type=module -`，并核对 `<template>` 标签数量闭合。

- [ ] **Step 3: 引用和差异检查**

```powershell
rg -n "display-options|updateDisplayOptions|resolveNullDisplayText" `
  D:\IdeaProjects\mc-ai\ai-agent\src\main\java `
  D:\TraeProject\enterprise-vue-admin\src

git -C D:\IdeaProjects\mc-ai diff --check
git -C D:\TraeProject\enterprise-vue-admin diff --check
```

- [ ] **Step 4: 用户验收命令仅记录、不执行**

```powershell
Set-Location D:\IdeaProjects\mc-ai
mvn -pl ai-agent -am -DskipTests compile

Set-Location D:\TraeProject\enterprise-vue-admin
npm test -- fieldDictionaryDisplay.test.js
npm run build
```

预期：用户执行后编译、测试和构建通过。

- [ ] **Step 5: 页面验收清单**

1. 输入能力编码并跳到非第一页，进入编辑后返回，查询条件、页码和每页条数不变。
2. 四个开关切换成功后刷新页面，值保持不变。
3. 模拟接口失败时开关恢复原值。
4. 关闭字段用途时回答规则同步关闭且不能再次开启。
5. 数字字段空值显示为 `0`，字符串、日期、布尔、对象和数组字段为空。
6. 列表不显示字段路径，编辑和详情仍可查看字段路径。
