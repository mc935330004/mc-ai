# 报告核心指标计算公式设计

## 1. 目标

在报告配置的“核心指标”区块中，同时支持直接字段和计算指标。计算指标可以自定义名称，并对多个字段字典命中的标量或列表值进行汇总和四则运算。

示例：

```text
项目总和 = SUM(合同金额) + SUM(结算金额) - SUM(质保金额)
```

## 2. 第一版范围

- 仅 `METRICS` 核心指标区块允许配置计算指标。
- 每个计算指标包含稳定 key、自定义名称、展示格式和多个计算项。
- 每个计算项选择一个字段字典、自动生成绝对取值路径、选择汇总方式，并从第二项开始选择运算符。
- 汇总方式：`SUM`、`AVG`、`COUNT`、`MAX`、`MIN`。
- 运算符：`ADD`、`SUBTRACT`、`MULTIPLY`、`DIVIDE`。
- 运算遵循先乘除、后加减。
- 后台使用 `BigDecimal`，除法固定保留 8 位中间精度并去除无意义尾零。
- 不支持括号、手工常量、JavaScript、SQL、SpEL 或任意文本表达式。

## 3. 配置模型

在 `ReportSectionSpec` 中新增独立的 `calculations` 集合，不把计算指标伪装为字段字典：

- `ReportCalculationSpec`：`key`、`label`、`displayFormat`、`terms`。
- `ReportCalculationTermSpec`：`fieldId`、`sourcePath`、`aggregation`、`operator`。
- 第一项 `operator` 必须为空；后续项必须有运算符。

旧工作流没有 `calculations` 时默认空集合，继续兼容现有发布快照。

## 4. 数据流

```text
报告配置选择字段
→ 前端自动生成 sourcePath
→ 工作流发布时后台校验计算配置
→ ReportDefinition 随版本保存
→ ConfigurableReportSectionBuilder 读取安全结果
→ ReportMetricCalculationService 聚合各计算项
→ 按优先级执行四则运算
→ 生成 ReportSchema Item
→ 现有 METRICS 组件渲染
```

计算只读取经过字段可见性策略处理后的安全结果，不绕过字段字典和报告安全边界。

## 5. 失败规则

- 缺少字段、路径无效、重复 key 或计算项不足，在工作流保存/发布校验阶段阻止通过。
- 路径运行时无数据：该计算项按 `0` 参与计算。
- 非空非数字值：该计算指标返回空值，后台记录不包含业务值的 `warn` 日志，不影响其他报告区块。
- 除数为零：该计算指标返回空值，后台记录不包含业务值的 `warn` 日志，不让整个报告失败。
- 单个计算指标最多 10 项；单个核心指标区块最多 10 个计算指标。

## 6. 展示格式

第一版允许：

- `NUMBER`：普通数字。
- `AMOUNT`：金额。
- `PERCENT`：百分比。

默认 `NUMBER`。计算结果不依赖某一个字段字典的展示格式。

## 7. 修改边界

后台由用户手工修改：

- 新增计算配置 record、枚举和计算服务。
- 扩展 `ReportSectionSpec`、发布校验及 `ConfigurableReportSectionBuilder`。

前端由 Codex 修改：

- 核心指标区块增加“直接字段/计算指标”配置。
- 计算指标使用字段选择器、汇总选择器和运算符选择器。
- 字段路径继续自动生成且只读。

不修改字段字典表、数据库结构、ReportSchema 协议、AI 分析逻辑和其他区块类型。

## 8. 验收

1. 旧报告配置不含 `calculations` 时仍能保存、发布和展示。
2. 核心指标可同时展示直接字段和计算指标。
3. 多个数组字段能够分别汇总后执行四则运算。
4. 乘除优先级正确。
5. 除数为零或非法数字只影响当前计算指标。
6. 非核心指标区块不能保存计算指标。
