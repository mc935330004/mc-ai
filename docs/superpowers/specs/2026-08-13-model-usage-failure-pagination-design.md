# 模型调用监控最近失败分页设计

## 目标

将“调用监控—最近失败”从固定数量列表改为数据库分页列表，避免失败记录较多时页面过长，同时保持模型调用汇总和模型统计逻辑不变。

## 方案

- 继续复用 `GET /api/agent/admin/model-usage/overview`，增加 `failureCurrent`、`failureSize` 参数。
- `recentFailures` 从普通集合调整为 MyBatis Plus `Page<RecentModelFailureVO>`。
- Mapper 使用 `Page` 参数和现有 XML SQL 分页，不在 Java 内截断，不新增表或索引。
- 前端默认每页 10 条，支持 10、20、50 条；切换统计天数时回到失败列表第一页。
- 页面分页时重新请求总览接口，汇总口径、失败安全摘要和时间范围保持原逻辑。

## 修改范围

- 后端：Controller、Service、ServiceImpl、Mapper、Mapper XML、Overview VO。
- 前端：模型调用监控组件。
- 文档：同步当前需求进度。

不修改模型调用记录写入、失败分类、安全摘要、数据库结构、模型统计 SQL 和其他监控页面。

## 异常与兼容

- `failureCurrent` 小于 1 时归一为 1。
- `failureSize` 限制在 10 至 50，防止一次读取过多失败记录。
- 没有失败数据时返回空分页对象，前端显示原有空状态。

## 验收

1. 失败记录超过 10 条时显示分页器且默认只展示 10 条。
2. 切换页码和每页数量后展示对应数据库记录。
3. 切换统计时间范围后回到第一页。
4. 汇总卡片和模型统计结果不受分页影响。
