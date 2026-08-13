# 模型调用监控最近失败分页 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将调用监控最近失败改为后端 SQL 分页和前端标准分页列表。

**Architecture:** 保留总览接口，在请求中增加失败分页参数，并在总览 VO 中返回 MyBatis Plus `Page`。模型汇总与模型统计继续按原范围查询，前端分页仅控制失败明细。

**Tech Stack:** Java 17、Spring Boot、MyBatis Plus、MyBatis XML、Vue 3、Element Plus

---

### Task 1: 后端失败记录分页查询

**Files:**
- Modify: `ai-agent/src/main/java/org/example/ai/agent/modelusage/controller/ModelUsageAdminController.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/modelusage/service/ModelUsageService.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/modelusage/service/impl/ModelUsageServiceImpl.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/modelusage/mapper/ModelUsageMapper.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/modelusage/vo/ModelUsageOverviewVO.java`
- Modify: `ai-agent/src/main/resources/mapper/ModelUsageMapper.xml`

- [ ] **Step 1: 将总览参数改为失败页码和每页数量**

Controller 接收 `failureCurrent=1`、`failureSize=10`，Service 方法同步调整为三个参数。

- [ ] **Step 2: Mapper 使用 MyBatis Plus Page**

```java
Page<RecentModelFailureVO> selectRecentFailures(
        Page<RecentModelFailureVO> page,
        @Param("startTime") LocalDateTime startTime
);
```

XML 删除 `LIMIT`，保留 `success=0`、时间范围和 `ORDER BY id DESC`。

- [ ] **Step 3: 总览返回分页对象**

`ModelUsageOverviewVO.recentFailures` 调整为 `Page<RecentModelFailureVO>`；Service 将页码最小限制为 1，每页限制为 10 至 50，并继续转换当前页记录的安全错误摘要。

### Task 2: 前端分页列表

**Files:**
- Modify: `D:/TraeProject/enterprise-vue-admin/src/views/knowledge/system-config/components/ModelUsagePanel.vue`

- [ ] **Step 1: 增加分页状态**

```javascript
const failurePagination = reactive({ current: 1, size: 10, total: 0 })
```

- [ ] **Step 2: 请求分页参数并解析分页响应**

请求发送 `failureCurrent` 和 `failureSize`，表格读取 `data.recentFailures.records`，总数读取 `data.recentFailures.total`。

- [ ] **Step 3: 增加 Element Plus 分页器**

分页器支持 10、20、50；切换页码加载当前页，切换条数回第一页，切换时间范围也回第一页。

### Task 3: 进度同步和静态检查

**Files:**
- Modify: `docs/requirements/pm-agent-ai-report-refactor-plan.md`

- [ ] **Step 1: 记录阶段实现内容和待验收状态**
- [ ] **Step 2: 检查 Java 调用签名、XML 参数和 Vue 脚本语法**
- [ ] **Step 3: 不运行编译、测试或构建，由用户执行页面验收**
