# 字段字典列表快捷编辑设计

## 1. 目标

在不改变字段字典业务含义和现有完整编辑流程的前提下，降低高频配置成本：

- 列表页不再展示“字段路径”列，编辑页继续保留字段路径配置。
- “字段用途”“回答规则”“可搜索”“聚合统计”在列表中使用开关直接编辑。
- 空值显示规则统一为：数字类型默认 `0`，其他类型为空。
- 从新增、编辑或详情页返回列表时，保留能力编码、当前页和每页条数。

## 2. 范围与边界

### 2.1 本次修改

- 前端字段字典列表与编辑页。
- 字段字典轻量开关更新接口。
- 字段空值显示规则。
- 历史 `null_display_text` 数据清理 SQL。

### 2.2 本次不修改

- 不删除数据库中的 `field_path` 和 `null_display_text` 字段。
- 不改变工作流、报告、RAG 或模型调用链。
- 不新增表、字段、依赖、缓存或全局状态管理。
- 不用完整字段保存接口处理列表开关，避免覆盖其他字段和创建时间。

## 3. 列表页设计

### 3.1 表格列

- 删除列表中的“字段路径”列。
- 保留能力编码、字段英文名、中文名称、字段类型、展示格式、展示顺序、创建时间和操作列。
- 以下四列改为 `el-switch`：
  - 字段用途：`visible`，`1=允许展示`，`0=仅内部使用`。
  - 回答规则：`requiredOutput`，`1=必答`，`0=按需回答`。
  - 可搜索：`searchable`，沿用项目现有约定，`0=是`，`1=否`。
  - 聚合统计：`aggregatable`，沿用项目现有约定，`0=是`，`1=否`。

### 3.2 保存行为

- 开关切换后立即调用轻量更新接口。
- 保存期间只禁用当前行的四个开关，防止重复提交。
- 保存成功后保留当前列表，不整页刷新。
- 保存失败时恢复该行切换前的值并显示错误提示。
- 当字段用途关闭时，同一次请求把回答规则同步改为“按需回答”。
- 字段用途为“仅内部使用”时，回答规则开关禁用。

## 4. 后端接口设计

新增轻量接口：

```text
POST /api/agent/dictionaries/{id}/display-options
```

请求只包含：

```json
{
  "visible": 1,
  "requiredOutput": 0,
  "searchable": 0,
  "aggregatable": 1
}
```

处理规则：

- ID 不存在时返回字段字典不存在。
- 四个字段只能为 `0` 或 `1`。
- `visible=0` 时后台强制 `requiredOutput=0`，不能依赖前端保证。
- 只更新四个开关字段，不修改路径、语义、展示格式、创建时间等其他数据。
- 使用现有 MyBatis Plus 更新能力，不新增 Mapper XML。

## 5. 空值显示规则

### 5.1 统一规则

数字类型包括：

```text
number、integer、int、long、float、double、decimal、bigdecimal、numeric
```

- 数字类型：`nullDisplayText = "0"`。
- 其他类型：`nullDisplayText = null`。

### 5.2 前后端一致性

- 新建页面初始为空；选择数字类型后自动显示 `0`。
- 编辑页面按字段类型展示规则值，不再回退“当前数据中未提供”。
- 字段类型发生变化时立即同步空值显示值。
- “空值显示文本”保留只读展示，用户不手工维护。
- 后台保存时根据 `fieldType` 再计算一次，禁止旧前端或直接请求写回任意默认文字。

### 5.3 历史数据清理

执行以下数据更新，不修改表结构：

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

风险：该 SQL 会覆盖所有历史自定义空值显示文本；这是用户已确认的目标。

## 6. 返回列表保留条件

列表状态写入路由查询参数：

```text
capabilityCode、current、size
```

- 查询、重置、翻页和切换每页条数时同步更新当前列表 URL。
- 进入新增、编辑、详情页时携带当前查询参数。
- “返回列表”和保存成功都显式返回字段字典列表路由，并携带原参数。
- 页面重新挂载时从路由恢复筛选条件和分页，再加载列表。
- 不使用 `localStorage`、全局 Store 或新增缓存。

## 7. 预计修改文件

前端：

- `src/views/knowledge/field-dictionary/index.vue`
- `src/views/knowledge/field-dictionary/save.vue`
- `src/views/knowledge/field-dictionary/detail.vue`
- `src/api/knowledgeFieldDictionary.js`
- `src/utils/fieldDictionaryDisplay.js`

后端：

- `ai-agent/src/main/java/org/example/ai/agent/capability/dto/FieldDictionaryDisplayOptionsDTO.java`
- `ai-agent/src/main/java/org/example/ai/agent/capability/controller/FieldDictionaryController.java`
- `ai-agent/src/main/java/org/example/ai/agent/capability/service/FieldDictionaryService.java`
- `ai-agent/src/main/java/org/example/ai/agent/capability/service/impl/FieldDictionaryServiceImpl.java`
- `ai-agent/src/main/resources/db/migration/V11__create_action_audit_log.sql`

文档：

- `docs/requirements/pm-agent-ai-report-refactor-plan.md`

## 8. 验收标准

- 列表页不显示字段路径。
- 四个开关可以直接保存，失败能恢复原状态。
- 关闭字段用途时回答规则自动关闭并禁用。
- 数字类型空值显示为 `0`，其他类型为空。
- 历史数据按相同规则完成更新。
- 编辑或详情返回后，筛选条件、当前页和每页条数不丢失。
- 原完整新增、编辑、删除、详情和分页能力不受影响。

## 9. 验证边界

按照用户约定，本阶段只进行代码结构、调用关系、语法和差异检查，不运行测试、编译或浏览器验收；最终页面和接口行为由用户验收。
