# 报告审批状态专属位置 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `auditStatus` 从普通指标移动到基础信息右上角，并映射为审批状态标签。

**Architecture:** 复用现有 ReportSchema 区块数据，不修改后台协议。前端纯函数负责状态解析和字段过滤，通用报告组件只负责布局渲染。

**Tech Stack:** Vue 3、Element Plus、Node.js test

---

### Task 1: 审批状态纯函数

**Files:**
- Create: `D:/TraeProject/enterprise-vue-admin/src/utils/reportAuditStatus.js`
- Create: `D:/TraeProject/enterprise-vue-admin/test/reportAuditStatus.test.js`

- [ ] **Step 1: 定义 0 至 4 的文字和 Element Plus 类型映射**
- [ ] **Step 2: 从 `section.items` 解析唯一有效的 `auditStatus`**
- [ ] **Step 3: 提供过滤普通区块 `auditStatus` 的纯函数**
- [ ] **Step 4: 写入缺失、有效和多状态的最小回归用例，本次不执行测试**

### Task 2: 通用报告组件渲染

**Files:**
- Modify: `D:/TraeProject/enterprise-vue-admin/src/components/AiChat/AiReport.vue`

- [ ] **Step 1: 在首个 `KEY_VALUE` 区块标题右侧显示审批标签**
- [ ] **Step 2: KEY_VALUE、METRICS 等普通 item 区块过滤 `auditStatus`**
- [ ] **Step 3: 增加标题行右侧布局样式，不修改报告表头**

### Task 3: 进度同步和静态检查

**Files:**
- Modify: `docs/requirements/pm-agent-ai-report-refactor-plan.md`

- [ ] **Step 1: 记录实现范围和待验收状态**
- [ ] **Step 2: 检查 JavaScript 与 Vue 脚本语法、引用和差异**
- [ ] **Step 3: 不运行测试、构建或编译，由用户执行页面验收**
