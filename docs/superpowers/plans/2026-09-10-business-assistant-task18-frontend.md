# Task18 业务助手前端视图 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不重做现有聊天页的前提下，让 Vue 前端完整展示 CHAT v2 业务回答、项目或人员上下文、数据集状态、核心指标、风险、明细和报告文件。

**Architecture:** 保留现有 AiChat 消息流和统一 Block 协议。responseStream 只负责协议校验与安全字段归一化，index.vue 只负责消息级业务外壳，BlockRenderer 只负责区块展示，agentReportTask.js 只负责报告任务 HTTP 调用；业务统计仍全部由后端完成。

**Tech Stack:** Vue 3、Element Plus、Vite、Node.js test runner、Axios

---

## 执行前置

前端源仓库是 D:/TraeProject/enterprise-vue-admin，当前 main 分支包含用户未提交的 vite.config.js 修改和 src/api/mdaes.js 新文件。执行计划前必须使用 using-git-worktrees 技能，从前端当前 HEAD f351b39 创建独立分支 codex/business-assistant-ui；不得复制、删除或提交上述用户改动。

后端设计文档保存在 D:/codex-home/.config/superpowers/worktrees/mc-ai/business-assistant。本计划的代码修改只发生在新建的前端工作树。

创建前端工作树后先执行：

    npm ci

Expected: 按现有 package-lock.json 安装依赖，package.json 和 package-lock.json 均不发生修改。

## 文件职责

- Modify: src/utils/responseStream.js
  负责 CHAT v2 与 REPORT v1 协议校验，保存后端提供的 ResponseContext。
- Test: test/responseStream.test.js
  验证协议版本、上下文归一化和旧协议兼容。
- Modify: src/views/knowledge/AiChat/index.vue
  负责消息级标题、查询范围、完成状态和业务卡片外壳。
- Modify: src/components/AiChat/BlockRenderer.vue
  负责 STATUS_LIST 与 ARTIFACT，以及现有指标、风险、表格的统一视觉。
- Create: src/api/agentReportTask.js
  只封装报告状态、下载和取消接口。
- Test: test/agentReportTaskSource.test.js
  对新增 API 的路径编码、请求方式和安全下载方式做静态契约检查。

### Task 1: 兼容 CHAT v2 并保存回答上下文

**Files:**
- Modify: src/utils/responseStream.js:23-113
- Modify: src/views/knowledge/AiChat/index.vue:423-462
- Create: test/responseStream.test.js

- [ ] **Step 1: 写协议失败测试**

创建 test/responseStream.test.js：

    import test from 'node:test'
    import assert from 'node:assert/strict'
    import {
      applyResponseDocument,
      validateResponseDocument
    } from '../src/utils/responseStream.js'

    function chatDocument(schemaVersion = 2) {
      return {
        schemaVersion,
        responseId: 'response-1',
        runId: 'run-1',
        conversationId: 'conversation-1',
        mode: 'CHAT',
        status: 'COMPLETED',
        dataComplete: true,
        context: {
          subjectType: 'PROJECT',
          subjectId: 'XXXT2674040',
          subjectLabel: 'XXXT2674040 项目全景',
          scopeLabel: '2026年度',
          periodStart: '2026-01-01',
          periodEnd: '2026-12-31',
          snapshotAt: '2026-09-02T10:18:00'
        },
        blocks: [{
          id: 'business_metrics',
          type: 'METRICS',
          status: 'READY',
          items: []
        }],
        references: [],
        meta: {}
      }
    }

    function emptyMessage() {
      return {
        responseId: '',
        runId: '',
        conversationId: '',
        presentationMode: '',
        blocks: []
      }
    }

    test('CHAT v2 快照合法并保存安全业务上下文', () => {
      const message = emptyMessage()
      const document = chatDocument()

      assert.doesNotThrow(() => validateResponseDocument(document, message))
      applyResponseDocument(message, document)

      assert.deepEqual(message.responseContext, document.context)
      assert.equal(message.blocks[0].type, 'METRICS')
    })

    test('CHAT v1 历史快照继续兼容', () => {
      assert.doesNotThrow(() => {
        validateResponseDocument(chatDocument(1), emptyMessage())
      })
    })

    test('REPORT 仍只接受 v1，未知 CHAT 版本被拒绝', () => {
      const reportV2 = {
        ...chatDocument(2),
        mode: 'REPORT',
        sections: []
      }

      assert.throws(
        () => validateResponseDocument(reportV2, emptyMessage()),
        /格式或状态无效/
      )
      assert.throws(
        () => validateResponseDocument(chatDocument(3), emptyMessage()),
        /格式或状态无效/
      )
    })

    test('缺少上下文时保存 null，不从问题文本猜测', () => {
      const message = emptyMessage()
      const document = { ...chatDocument(), context: null }

      applyResponseDocument(message, document)

      assert.equal(message.responseContext, null)
    })

- [ ] **Step 2: 运行测试并确认当前实现失败**

Run:

    npm test -- --test-name-pattern="CHAT v2|REPORT 仍只接受|缺少上下文"

Expected: FAIL，错误来自回答快照格式校验，表明现有代码仍只接受 schemaVersion=1。

- [ ] **Step 3: 用最小规则修改协议校验**

将 validateResponseDocument 开头改为：

    export function validateResponseDocument(document, message = {}) {
      const mode = String(document?.mode || '').toUpperCase()
      const schemaVersion = Number(document?.schemaVersion)
      const versionValid = mode === 'CHAT'
        ? [1, 2].includes(schemaVersion)
        : mode === 'REPORT' && schemaVersion === 1

      if (!document || !versionValid
        || !['CHAT', 'REPORT'].includes(mode)
        || !['RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED'].includes(document.status)) {
        throw new Error('回答快照格式或状态无效。')
      }
      for (const key of ['conversationId', 'runId', 'responseId']) {
        if (typeof document[key] !== 'string' || !document[key].trim()
          || (message[key] && message[key] !== document[key])) {
          throw new Error('回答快照与当前会话或请求不匹配。')
        }
      }
      if ((message.presentationMode && message.presentationMode !== mode)
        || (mode === 'CHAT' && !Array.isArray(document.blocks))
        || (mode === 'REPORT' && !Array.isArray(document.sections))) {
        throw new Error('回答快照的展示模式或正文结构无效。')
      }
    }

在 applyResponseDocument 设置 responseMeta 后增加：

    message.responseContext = normalizeResponseContext(document.context)

在 normalizeBlocks 前增加一个扁平的上下文归一化函数：

    function normalizeResponseContext(context) {
      if (!context || typeof context !== 'object' || Array.isArray(context)) {
        return null
      }
      return {
        subjectType: String(context.subjectType || '').toUpperCase(),
        subjectId: String(context.subjectId || '').trim(),
        subjectLabel: String(context.subjectLabel || '').trim(),
        scopeLabel: String(context.scopeLabel || '').trim(),
        periodStart: String(context.periodStart || '').trim(),
        periodEnd: String(context.periodEnd || '').trim(),
        snapshotAt: String(context.snapshotAt || '').trim()
      }
    }

在 index.vue 的 createMessage 返回对象中，紧邻 responseMeta 增加：

    responseContext: extra.responseContext || null,

- [ ] **Step 4: 运行协议测试**

Run:

    node --test test/responseStream.test.js

Expected: 4 tests pass。

- [ ] **Step 5: 运行现有前端测试**

Run:

    npm test

Expected: 全部测试通过。

- [ ] **Step 6: 提交协议兼容**

Run:

    git add src/utils/responseStream.js src/views/knowledge/AiChat/index.vue test/responseStream.test.js
    git commit -m "fix(chat): support business response context"

Expected: 新测试文件和两个修改文件进入同一个提交。

### Task 2: 增加轻量业务回答卡片

**Files:**
- Modify: src/views/knowledge/AiChat/index.vue:76-121
- Modify: src/views/knowledge/AiChat/index.vue:462-493
- Modify: src/views/knowledge/AiChat/index.vue:1983-2013

- [ ] **Step 1: 增加消息展示纯函数**

在 shouldRenderReport 后增加以下函数，不创建新的状态对象：

    function shouldRenderBusinessAnswer(message) {
      return message?.role === 'assistant'
        && Array.isArray(message.blocks)
        && message.blocks.length > 0
        && !message.actionForm
        && message.action?.status !== 'PENDING'
    }

    function businessAnswerTitle(message) {
      const context = message?.responseContext
      return context?.subjectLabel || context?.subjectId || '业务查询结果'
    }

    function businessAnswerStatus(message) {
      if (message?.streaming) return { label: '查询中', type: 'primary' }
      if (message?.responseStatus === 'FAILED') return { label: '查询失败', type: 'danger' }
      if (message?.responseStatus === 'CANCELLED') return { label: '已终止', type: 'info' }
      if (message?.responseStatus === 'PARTIAL' || !message?.dataComplete) {
        return { label: '部分完成', type: 'warning' }
      }
      return { label: '查询完成', type: 'success' }
    }

    function businessContextItems(message) {
      const context = message?.responseContext
      if (!context) return []

      const period = context.periodStart && context.periodEnd
        ? context.periodStart + ' 至 ' + context.periodEnd
        : ''
      const snapshot = context.snapshotAt
        ? formatMessageTime(context.snapshotAt)
        : ''

      return [
        { key: 'subject', label: context.subjectId },
        { key: 'scope', label: context.scopeLabel },
        { key: 'period', label: period },
        { key: 'snapshot', label: snapshot ? '快照 ' + snapshot : '' }
      ].filter(item => item.label)
    }

- [ ] **Step 2: 给结构化回答增加外壳模板**

在 message-bubble 的 class 对象增加：

    'is-business-bubble': shouldRenderBusinessAnswer(message),

将现有 message.blocks 分支替换为：

    <article
      v-else-if="!message.actionForm
        && (!message.action || ['SUCCESS', 'FAILED', 'UNKNOWN'].includes(message.action.status))
        && message.blocks?.length"
      class="business-answer-card"
    >
      <header class="business-answer-header">
        <div>
          <h3>{{ businessAnswerTitle(message) }}</h3>
          <p v-if="message.responseContext?.scopeLabel">
            {{ message.responseContext.scopeLabel }}业务数据汇总
          </p>
        </div>
        <el-tag
          :type="businessAnswerStatus(message).type"
          effect="plain"
        >
          {{ businessAnswerStatus(message).label }}
        </el-tag>
      </header>
      <div
        v-if="businessContextItems(message).length"
        class="business-context-list"
      >
        <span
          v-for="item in businessContextItems(message)"
          :key="item.key"
        >
          {{ item.label }}
        </span>
      </div>
      <div class="response-block-list">
        <BlockRenderer
          v-for="block in message.blocks"
          :key="block.id"
          :block="block"
          :streaming="message.streaming"
          :page-context="{
            conversationId: message.conversationId,
            runId: message.runId,
            responseId: message.responseId
          }"
        />
      </div>
    </article>

- [ ] **Step 3: 增加紧凑样式**

在 is-report-bubble 规则后增加：

    .message-bubble.is-business-bubble {
      padding: 0;
      border: 0;
      background: transparent;
      white-space: normal;
      box-shadow: none;
    }

    .business-answer-card {
      overflow: hidden;
      border: 1px solid #dfe5ec;
      border-radius: 10px;
      background: #fff;
    }

    .business-answer-header {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 16px;
      padding: 18px 20px 14px;
      border-top: 3px solid #1677ff;
      border-bottom: 1px solid #ebeef5;
    }

    .business-answer-header h3 {
      margin: 0;
      color: #172033;
      font-size: 18px;
      line-height: 1.45;
    }

    .business-answer-header p {
      margin: 4px 0 0;
      color: #909399;
      font-size: 12px;
    }

    .business-context-list {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      padding: 12px 20px 0;
    }

    .business-context-list span {
      padding: 4px 10px;
      border: 1px solid #e4e7ed;
      border-radius: 6px;
      color: #606266;
      font-size: 12px;
    }

    .business-answer-card > .response-block-list {
      gap: 0;
      padding: 14px 20px 18px;
    }

在 760px 媒体查询中增加：

    .business-answer-header {
      padding: 16px;
    }

    .business-context-list {
      padding: 12px 16px 0;
    }

    .business-answer-card > .response-block-list {
      padding: 14px 16px 16px;
    }

- [ ] **Step 4: 构建验证模板和样式**

Run:

    npm run build

Expected: vite build 成功，没有 Vue template 编译错误。

- [ ] **Step 5: 提交回答卡片**

Run:

    git add src/views/knowledge/AiChat/index.vue
    git commit -m "feat(chat): add business answer card"

Expected: 只提交 index.vue。

### Task 3: 展示数据集状态并统一指标层级

**Files:**
- Modify: src/components/AiChat/BlockRenderer.vue:1-151
- Modify: src/components/AiChat/BlockRenderer.vue:153-207
- Modify: src/components/AiChat/BlockRenderer.vue:209-389

- [ ] **Step 1: 增加 STATUS_LIST 模板**

在 KEY_VALUE 分支后、TABLE 分支前增加：

    <div
      v-else-if="type === 'STATUS_LIST'"
      class="block-status-list"
    >
      <div
        v-for="item in statusItems"
        :key="item.code"
        class="block-status-item"
      >
        <span
          class="block-status-dot"
          :class="'is-' + tagType(item.tone)"
        ></span>
        <span>{{ item.label || item.code }}</span>
        <el-tag
          :type="tagType(item.tone)"
          effect="plain"
          size="small"
        >
          {{ stateLabel(item.state) }}
        </el-tag>
        <small v-if="item.safeMessage">{{ item.safeMessage }}</small>
      </div>
      <el-empty
        v-if="!statusItems.length"
        description="暂无数据状态"
        :image-size="56"
      />
    </div>

- [ ] **Step 2: 增加扁平状态映射**

在 warnings computed 后增加：

    const statusItems = computed(() => (
      Array.isArray(props.block?.items) ? props.block.items : []
    ))

在 severityLabel 后增加：

    function stateLabel(state) {
      const labels = {
        PENDING: '待查询',
        RUNNING: '查询中',
        SUCCESS: '完成',
        COMPLETED: '完成',
        EMPTY: '无数据',
        DENIED: '无权限',
        PARTIAL: '部分完成',
        PARTIAL_SUCCESS: '部分完成',
        FAILED: '失败',
        TIMEOUT: '超时',
        SKIPPED: '已跳过',
        CANCELLED: '已终止',
        RETRY: '等待重试',
        COLLECTING: '汇总中',
        RENDERING: '生成文件中',
        EXPIRED: '已过期'
      }
      return labels[String(state || '').toUpperCase()] || '状态未知'
    }

- [ ] **Step 3: 调整状态、指标和风险样式**

将 block-metric 背景由 var(--el-fill-color-light) 改为 #fff，并增加以下规则：

    .block-status-list {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }

    .block-status-item {
      display: flex;
      align-items: center;
      gap: 6px;
      min-width: 0;
      padding: 6px 9px;
      border: 1px solid #e4e7ed;
      border-radius: 6px;
      background: #fafcff;
      color: #303133;
      font-size: 12px;
    }

    .block-status-item small {
      max-width: 260px;
      overflow: hidden;
      color: #909399;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .block-status-dot {
      width: 7px;
      height: 7px;
      flex: 0 0 7px;
      border-radius: 50%;
      background: #909399;
    }

    .block-status-dot.is-success {
      background: #67c23a;
    }

    .block-status-dot.is-warning {
      background: #e6a23c;
    }

    .block-status-dot.is-danger {
      background: #f56c6c;
    }

    .block-status-dot.is-primary {
      background: #409eff;
    }

- [ ] **Step 4: 运行完整测试与构建**

Run:

    npm test
    npm run build

Expected: 全部测试和 Vite 构建通过。

- [ ] **Step 5: 提交状态与指标展示**

Run:

    git add src/components/AiChat/BlockRenderer.vue
    git commit -m "feat(chat): render business dataset status"

Expected: 只提交 BlockRenderer.vue。

### Task 4: 接入报告任务状态和安全下载

**Files:**
- Create: src/api/agentReportTask.js
- Create: test/agentReportTaskSource.test.js
- Modify: src/components/AiChat/BlockRenderer.vue

- [ ] **Step 1: 写报告 API 契约失败测试**

创建 test/agentReportTaskSource.test.js：

    import test from 'node:test'
    import assert from 'node:assert/strict'
    import { readFile } from 'node:fs/promises'

    const sourceUrl = new URL('../src/api/agentReportTask.js', import.meta.url)

    test('报告任务 API 对 taskId 编码并使用后端安全下载接口', async () => {
      const source = await readFile(sourceUrl, 'utf8')

      assert.match(source, /encodeURIComponent\\(taskId\\)/)
      assert.match(source, /\\/api\\/agent\\/report-tasks\\//)
      assert.match(source, /responseType:\\s*'blob'/)
      assert.doesNotMatch(source, /storagePath|checksum|downloadUrl/)
    })

    test('报告任务只开放状态、下载和取消', async () => {
      const source = await readFile(sourceUrl, 'utf8')

      assert.match(source, /export function getAgentReportTask/)
      assert.match(source, /export function downloadAgentReportTask/)
      assert.match(source, /export function cancelAgentReportTask/)
    })

- [ ] **Step 2: 运行测试并确认新 API 尚不存在**

Run:

    node --test test/agentReportTaskSource.test.js

Expected: FAIL，错误为找不到 src/api/agentReportTask.js。

- [ ] **Step 3: 创建最小报告任务 API**

创建 src/api/agentReportTask.js：

    import request from '@/utils/request'

    function taskPath(taskId, suffix = '') {
      return '/api/agent/report-tasks/'
        + encodeURIComponent(taskId)
        + suffix
    }

    export function getAgentReportTask(taskId) {
      return request({
        url: taskPath(taskId),
        method: 'get',
        params: { _t: Date.now() },
        silentError: true
      })
    }

    export function downloadAgentReportTask(taskId) {
      return request({
        url: taskPath(taskId, '/download'),
        method: 'get',
        responseType: 'blob',
        silentError: true
      })
    }

    export function cancelAgentReportTask(taskId) {
      return request({
        url: taskPath(taskId, '/cancel'),
        method: 'post',
        silentError: true
      })
    }

- [ ] **Step 4: 运行 API 契约测试**

Run:

    node --test test/agentReportTaskSource.test.js

Expected: 2 tests pass。

- [ ] **Step 5: 在 BlockRenderer 中增加 ARTIFACT 状态**

将 script setup import 扩展为：

    import { computed, ref } from 'vue'
    import { ElMessage } from 'element-plus'
    import MarkdownRender from './MarkdownRender.vue'
    import BlockDataTable from './BlockDataTable.vue'
    import BlockValue from './BlockValue.vue'
    import {
      cancelAgentReportTask,
      downloadAgentReportTask,
      getAgentReportTask
    } from '@/api/agentReportTask'

在 computed 定义区增加：

    const artifactState = ref(null)
    const artifactLoading = ref(false)
    const artifact = computed(() => ({
      ...props.block,
      ...(artifactState.value || {})
    }))
    const artifactStatus = computed(() => (
      String(artifact.value.taskStatus || '').toUpperCase()
    ))
    const artifactReady = computed(() => (
      ['SUCCESS', 'COMPLETED'].includes(artifactStatus.value)
    ))
    const artifactCancellable = computed(() => (
      ['PENDING', 'RETRY', 'RUNNING', 'COLLECTING', 'RENDERING']
        .includes(artifactStatus.value)
    ))

在 stateLabel 后增加：

    async function refreshArtifact() {
      if (!artifact.value.taskId || artifactLoading.value) return
      artifactLoading.value = true
      try {
        const task = await getAgentReportTask(artifact.value.taskId)
        artifactState.value = {
          taskStatus: task?.status,
          format: task?.format,
          fileName: task?.fileName,
          expiresAt: task?.expiresAt,
          dataComplete: task?.dataComplete,
          safeMessage: task?.safeErrorMessage || ''
        }
      } catch (error) {
        ElMessage.warning(error?.message || '报告状态查询失败')
      } finally {
        artifactLoading.value = false
      }
    }

    async function downloadArtifact() {
      if (!artifactReady.value || artifactLoading.value) return
      artifactLoading.value = true
      try {
        const content = await downloadAgentReportTask(artifact.value.taskId)
        const url = URL.createObjectURL(content)
        const link = document.createElement('a')
        link.href = url
        link.download = artifact.value.fileName || '业务报告'
        document.body.appendChild(link)
        link.click()
        link.remove()
        window.setTimeout(() => URL.revokeObjectURL(url), 0)
      } catch (error) {
        ElMessage.warning(error?.message || '报告下载失败')
      } finally {
        artifactLoading.value = false
      }
    }

    async function cancelArtifact() {
      if (!artifactCancellable.value || artifactLoading.value) return
      artifactLoading.value = true
      try {
        await cancelAgentReportTask(artifact.value.taskId)
        artifactState.value = {
          ...(artifactState.value || {}),
          taskStatus: 'CANCELLED',
          safeMessage: '报告任务已取消'
        }
      } catch (error) {
        ElMessage.warning(error?.message || '报告取消失败')
      } finally {
        artifactLoading.value = false
      }
    }

    function artifactExpiry(value) {
      return String(value || '').replace('T', ' ').slice(0, 16)
    }

- [ ] **Step 6: 增加 ARTIFACT 模板**

先把通用骨架屏条件改为以下内容，报告任务即使处于 PENDING 也必须显示可操作卡片：

    <el-skeleton
      v-else-if="(status === 'PENDING' && type !== 'ARTIFACT')
        || (status === 'STREAMING' && !['TEXT', 'ARTIFACT'].includes(type))"
      :rows="2"
      animated
    />

在 WARNINGS 分支后、未知类型分支前增加：

    <article
      v-else-if="type === 'ARTIFACT'"
      class="block-artifact"
    >
      <div class="block-artifact-main">
        <strong>{{ artifact.fileName || '业务报告' }}</strong>
        <span>
          {{ artifact.format || '文件' }} ·
          {{ stateLabel(artifactStatus) }}
        </span>
        <small v-if="artifact.safeMessage">
          {{ artifact.safeMessage }}
        </small>
        <small v-if="artifact.expiresAt">
          有效期至 {{ artifactExpiry(artifact.expiresAt) }}
        </small>
      </div>
      <div class="block-artifact-actions">
        <el-button
          v-if="artifactReady"
          type="primary"
          plain
          :loading="artifactLoading"
          @click="downloadArtifact"
        >
          下载报告
        </el-button>
        <el-button
          v-else
          :loading="artifactLoading"
          @click="refreshArtifact"
        >
          刷新状态
        </el-button>
        <el-button
          v-if="artifactCancellable"
          text
          :disabled="artifactLoading"
          @click="cancelArtifact"
        >
          取消
        </el-button>
      </div>
    </article>

- [ ] **Step 7: 增加报告文件样式**

在 block-warning 规则后增加：

    .block-artifact {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 16px;
      padding: 12px 14px;
      border: 1px solid #d9ecff;
      border-radius: 8px;
      background: #f4f9ff;
    }

    .block-artifact-main {
      display: grid;
      gap: 3px;
      min-width: 0;
    }

    .block-artifact-main strong,
    .block-artifact-main span,
    .block-artifact-main small {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .block-artifact-main span,
    .block-artifact-main small {
      color: #606266;
      font-size: 12px;
    }

    .block-artifact-actions {
      display: flex;
      flex: 0 0 auto;
      align-items: center;
    }

在 480px 媒体查询中增加：

    .block-artifact {
      align-items: stretch;
      flex-direction: column;
    }

    .block-artifact-actions {
      justify-content: flex-end;
    }

- [ ] **Step 8: 运行报告 API 测试、完整测试和构建**

Run:

    node --test test/agentReportTaskSource.test.js
    npm test
    npm run build

Expected: 新增测试、全部现有测试和 Vite 构建通过。

- [ ] **Step 9: 提交报告任务展示**

Run:

    git add src/api/agentReportTask.js test/agentReportTaskSource.test.js src/components/AiChat/BlockRenderer.vue
    git commit -m "feat(chat): show business report artifacts"

Expected: 新增 API 文件必须出现在 git status 的已提交文件中，不遗漏新文件。

### Task 5: 回归检查和最终交付

**Files:**
- Verify: src/views/knowledge/AiChat/index.vue
- Verify: src/components/AiChat/BlockRenderer.vue
- Verify: src/utils/responseStream.js
- Verify: src/api/agentReportTask.js
- Verify: test/responseStream.test.js
- Verify: test/agentReportTaskSource.test.js

- [ ] **Step 1: 检查未支持 Block 和固定业务模块**

Run:

    rg -n "STATUS_LIST|ARTIFACT|METRICS|WARNINGS|GROUP_TABLE|TREE_TABLE" src/components/AiChat/BlockRenderer.vue
    rg -n "合同|概算|现金流|产值|出差|打卡|请假|报销" src/views/knowledge/AiChat/index.vue src/components/AiChat/BlockRenderer.vue

Expected: BlockRenderer 包含统一协议类型；第二条命令无输出，证明页面未写死业务模块。

- [ ] **Step 2: 检查敏感下载字段和危险 HTML**

Run:

    rg -n "storagePath|checksum|downloadUrl|v-html" src/api/agentReportTask.js src/components/AiChat/BlockRenderer.vue src/views/knowledge/AiChat/index.vue

Expected: 无输出。

- [ ] **Step 3: 执行干净安装环境下的最终验证**

Run:

    npm test
    npm run build

Expected: 全部 Node 测试通过，Vite 输出 built in，不出现 error。

- [ ] **Step 4: 检查修改范围**

Run:

    git status --short
    git diff --check HEAD~4..HEAD
    git log --oneline -5

Expected: 工作树干净；只包含 Task18 计划列出的前端文件；可以看到协议、业务卡片、状态块和报告文件四个聚焦提交。

- [ ] **Step 5: 最终提示**

最终报告必须明确：

1. Task18 第一版实际完成了什么。
2. CHAT v2、上下文、STATUS_LIST 和 ARTIFACT 如何展示。
3. npm test 与 npm run build 的真实结果。
4. 前端代码所在工作树和提交编号。
5. 原前端仓库中的 vite.config.js 与 src/api/mdaes.js 未被修改。
6. 本阶段没有改 Java、Mapper XML、Flyway、pom.xml 或数据库。
7. 当前报告状态采用手动刷新，没有引入后台轮询。
