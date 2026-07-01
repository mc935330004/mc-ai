# 企业级 RAG 管理系统需求设计

## 目标

把当前项目从“文件级 RAG Demo”升级为“企业知识文档管理 + RAG 问答平台”。后续主线统一为：

```text
知识分类 -> 文档 -> 文档版本 -> 文本切片 -> Embedding 向量化 -> PGVector 入库 -> 检索召回 -> 大模型回答 -> 引用溯源 -> 问答审计
```

本设计以现有 `knowledge_document`、`knowledge_document_version`、`knowledge_chunk`、`knowledge_base_vector_task` 为核心继续建设。旧 `knowledge_base` 先保留兼容，不再作为新企业级能力的主要扩展对象。

## 当前项目基础

当前项目已经具备以下能力：

- Spring Boot 4、Java 17、Maven、MyBatis Plus。
- MySQL 存业务元数据。
- PostgreSQL + PGVector 存向量数据。
- Spring AI 接 DeepSeek Chat 和 DashScope Embedding。
- 文件上传、文件类型检测、Hash、FTP/本地存储。
- PDF、Tika、Markdown、Jsoup 文档解析依赖。
- 知识库旧模型 `knowledge_base`。
- 企业文档新模型 `knowledge_category`、`knowledge_document`、`knowledge_document_version`、`knowledge_chunk`。
- 异步向量化任务 `knowledge_base_vector_task`，支持任务锁、重试、超时恢复。

## 设计原则

1. 以企业文档为主线，不继续扩展旧 `knowledge_base` 主模型。
2. 先打通可运行闭环，再增加权限、审计、重排、反馈等增强能力。
3. 数据库变更只追加迁移，不直接修改已有迁移文件。
4. 简单 CRUD 继续使用 MyBatis Plus，复杂列表、统计、检索审计再使用 Mapper XML。
5. API Key、数据库密码、Token 不写死在代码和生产配置里。
6. 问答必须可溯源，回答引用到文档、版本、切片。
7. 正式问答默认只检索已发布且当前生效的文档版本。

## 已发现问题和解决方案

### 1. 新旧知识模型双主线并存

现状：

- `/api/knowledgebase/**` 走旧 `knowledge_base`。
- `/api/knowledge/documents/**` 走新 `knowledge_document`。
- 上传、向量化、查询逻辑存在分裂。

风险：

- 权限、审计、统计、检索会重复建设。
- 新文档链路无法自然复用旧查询链路。
- 后续功能越多，维护成本越高。

方案：

- 企业级主线统一为 `knowledge_document`。
- 旧 `knowledge_base` 保留兼容接口，不再承载新需求。
- 新增企业文档问答、发布、版本、统计接口。
- 管理端优先对接 `/api/knowledge/documents/**`。

### 2. 文档版本向量化状态 bug

位置：

`src/main/java/org/example/airag/modules/knowledgebase/service/impl/KnowledgeDocumentVersionServiceImpl.java`

现状：

`vectorizeVersion()` 成功调用 `markCompleted()` 后，又调用了 `markProcessing(version)`。

风险：

- 成功完成的版本会被改回 `PROCESSING`。
- 页面无法正确显示向量化完成。
- 发布流程会被状态阻塞。

方案：

- `markProcessing(version)` 移到解析前。
- 成功后只执行 `markCompleted()`。
- 失败后执行 `markFailed()`。

目标状态流：

```text
PENDING -> PROCESSING -> COMPLETED
PENDING -> PROCESSING -> FAILED
```

### 3. 新文档向量 metadata 与旧查询过滤不一致

现状：

- 旧向量 metadata 使用 `kb_id`。
- 旧查询按 `kb_id in [...]` 过滤。
- 新文档向量 metadata 使用 `document_id`、`version_id`、`chunk_id`、`category_id` 等字段。

风险：

- 新文档上传和向量化成功后，旧问答接口不一定能检索到。
- 引用溯源无法统一。

方案：

- 新增 `KnowledgeDocumentQueryService`。
- 新增 `/api/knowledge/documents/query` 和 `/api/knowledge/documents/query/stream`。
- 查询按 `document_id`、`version_id`、`category_id` 过滤。
- 默认只查 `document.current_version_id` 对应版本。
- 返回引用来源：文档标题、版本号、切片 ID、切片序号、文件名。

### 4. 向量任务表结构可能与实体不一致

现状：

`KnowledgeBaseVectorTask` 实体已有：

- `documentId`
- `versionId`

但已有 `V2__create_vectorize_task.sql` 中未看到 `document_id`、`version_id` 字段。

风险：

- 新文档上传创建任务时可能出现 `Unknown column 'document_id'` 或 `Unknown column 'version_id'`。

方案：

新增 Flyway 迁移，不修改旧迁移：

```sql
ALTER TABLE knowledge_base_vector_task
    ADD COLUMN document_id BIGINT NULL COMMENT '企业知识文档ID',
    ADD COLUMN version_id BIGINT NULL COMMENT '企业知识文档版本ID';

CREATE INDEX idx_vector_task_document
    ON knowledge_base_vector_task (document_id);

CREATE INDEX idx_vector_task_version
    ON knowledge_base_vector_task (version_id);
```

执行前需要确认当前开发库是否已手工添加过字段，避免重复加字段。

### 5. 文档发布流程不完整

现状：

向量化完成后直接把 `document.current_version_id` 设置成当前版本。

风险：

- 未审核内容自动进入正式问答。
- 错误版本可能覆盖当前正式版本。
- 企业制度、流程、合同类知识不适合上传即发布。

方案：

第一阶段做轻量发布机制：

- 上传后 `document.status = DRAFT`。
- 向量化成功后 `version.vector_status = COMPLETED`，但不自动发布。
- 调用发布接口后：
  - `document.status = PUBLISHED`
  - `document.current_version_id = versionId`
  - `version.published_at = now`
  - 旧版本设置 `deprecated_at = now`

建议接口：

```text
POST /api/knowledge/documents/{documentId}/versions/{versionId}/publish
POST /api/knowledge/documents/{documentId}/archive
POST /api/knowledge/documents/{documentId}/deprecated
```

### 6. 缺少问答日志和引用审计

现状：

`QueryResponse` 返回引用，但没有持久化问答记录。

风险：

- 无法分析问答质量。
- 无法定位错误回答来源。
- 无法统计无答案问题、热门知识、低质量切片。

方案：

新增：

- `knowledge_query_log`
- `knowledge_query_reference`

`knowledge_query_log` 核心字段：

- `id`
- `question`
- `answer`
- `user_id`
- `session_id`
- `top_k`
- `min_score`
- `model_name`
- `duration_ms`
- `status`
- `error_message`
- `created_at`

`knowledge_query_reference` 核心字段：

- `id`
- `query_log_id`
- `document_id`
- `version_id`
- `chunk_id`
- `chunk_index`
- `score`
- `source`
- `created_at`

### 7. 切片策略偏基础

现状：

当前使用 `TokenTextSplitter.builder().build()`。

风险：

- 制度、流程、合同类文档的标题、条款、页码、段落结构容易丢失。
- 引用来源不够清楚。
- 召回内容可能缺少上下文。

方案：

第一阶段先增强 metadata，不急着引入复杂解析框架：

- 向量 metadata 增加 `document_title`、`document_code`、`version_no`、`owner_dept`、`chunk_id`、`chunk_index`。
- `KnowledgeChunk` 后续可扩展 `section_title`、`section_path`、`start_offset`、`end_offset`、`metadata_json`。
- 管理端支持查看 chunk，后续支持禁用低质量 chunk。

### 8. 权限和租户模型缺失

现状：

企业知识表暂未包含 `tenant_id`、`created_by`、`owner_dept_id`、`visibility` 等字段。

风险：

- 无法控制部门、项目、角色范围。
- 查询时无法按用户权限过滤。
- 多租户或多组织场景不可用。

方案：

第二阶段开始补齐权限基础字段：

- `tenant_id`
- `owner_dept_id`
- `created_by`
- `updated_by`
- `visibility`

新增授权表：

```text
knowledge_document_permission
```

字段：

- `document_id`
- `subject_type`：USER、DEPT、ROLE、PROJECT
- `subject_id`
- `permission_type`：READ、MANAGE

## 推荐模块划分

### 1. 知识分类模块

职责：

- 维护企业知识分类树。
- 支持启用、禁用、排序。
- 作为文档筛选和检索过滤条件。

现有基础：

- `KnowledgeCategory`
- `KnowledgeCategoryController`
- `KnowledgeCategoryService`

后续需求：

- 分类树查询。
- 分类下文档数量统计。
- 禁用分类时限制新文档上传。

### 2. 企业文档模块

职责：

- 管理文档主数据。
- 维护标题、编号、归属部门、状态、摘要、当前版本。

现有基础：

- `KnowledgeDocument`
- `KnowledgeDocumentController`
- `KnowledgeDocumentService`

后续需求：

- 文档分页查询支持标题、编号、分类、状态、部门过滤。
- 文档详情返回当前版本、版本列表、切片数量、向量状态。
- 文档归档、废止。

### 3. 文档版本模块

职责：

- 管理同一文档的多个版本。
- 维护文件、Hash、解析状态、向量状态、生效时间、发布时间。

现有基础：

- `KnowledgeDocumentVersion`
- `KnowledgeDocumentVersionService`
- `KnowledgeDocumentVersionController`

后续需求：

- 版本列表。
- 上传新版本。
- 发布版本。
- 废止版本。
- 重新向量化版本。
- 下载版本原文件。

### 4. 切片管理模块

职责：

- 保存解析后的 chunk。
- 支持切片审计、启用、禁用、查看。
- 作为引用溯源依据。

现有基础：

- `KnowledgeChunk`
- `KnowledgeChunkController`
- `KnowledgeChunkService`

后续需求：

- 按文档版本查询切片。
- 禁用低质量切片。
- 查看切片关联向量 metadata。
- 后续支持人工修正切片内容。

### 5. 向量任务模块

职责：

- 异步处理解析、切片、Embedding、PGVector 入库。
- 支持失败重试、超时恢复、任务观测。

现有基础：

- `KnowledgeBaseVectorTask`
- `KnowledgeBaseVectorTaskWorker`
- `KnowledgeBaseVectorTaskService`

后续需求：

- 任务分页查询。
- 按文档、版本、状态过滤。
- 任务重试。
- 任务耗时统计。
- 失败原因展示。

### 6. 企业文档问答模块

职责：

- 基于已发布文档进行 RAG 问答。
- 支持分类、文档范围过滤。
- 返回答案和引用来源。

新增服务：

- `KnowledgeDocumentQueryService`

建议请求 DTO：

```java
public record KnowledgeDocumentQueryRequest(
        List<Long> categoryIds,
        List<Long> documentIds,
        String question,
        Integer topK,
        Double minScore
) {
}
```

建议响应 VO：

```java
public record KnowledgeDocumentQueryResponse(
        String answer,
        List<Reference> references
) {
    public record Reference(
            Long documentId,
            Long versionId,
            Long chunkId,
            Integer chunkIndex,
            String documentTitle,
            String versionNo,
            String source
    ) {
    }
}
```

### 7. 问答审计模块

职责：

- 保存每次问答请求和引用来源。
- 支持质量分析和问题复盘。

新增表：

- `knowledge_query_log`
- `knowledge_query_reference`

后续需求：

- 查询日志分页。
- 无答案问题统计。
- 热门问题统计。
- 热门引用文档统计。
- 失败问答排查。

## 分阶段需求规划

### 第 1 阶段：企业文档 RAG 主链路闭环

目标：

让新企业文档模型完成上传、向量化、发布、查询、引用溯源。

需求：

1. 修复文档版本向量化状态 bug。
2. 新增任务表 `document_id`、`version_id` 字段迁移。
3. 调整文档版本向量化成功后不自动发布。
4. 新增版本发布接口。
5. 新增企业文档问答接口。
6. 查询默认只使用已发布文档的当前版本。
7. 回答返回文档、版本、切片引用。

验收标准：

- 上传文档后生成文档、版本、向量任务。
- worker 执行后版本状态为 `COMPLETED`。
- 未发布文档不会被正式问答检索到。
- 发布版本后，问答可以召回该版本 chunk。
- 回答引用包含 `documentId`、`versionId`、`chunkId`、`chunkIndex`、`source`。

### 第 2 阶段：文档生命周期管理

目标：

让企业知识具备可管理的版本和状态。

需求：

1. 文档分页高级筛选。
2. 文档详情聚合当前版本、版本列表、切片统计。
3. 版本列表和版本详情。
4. 版本重新向量化。
5. 文档废止、归档。
6. 生效时间过滤。

验收标准：

- 同一文档可以拥有多个版本。
- 只有当前发布版本参与默认问答。
- 废止和归档文档不参与默认问答。
- 可重新向量化指定版本。

### 第 3 阶段：问答审计和任务可观测

目标：

让 RAG 效果可分析、可追踪、可复盘。

需求：

1. 新增问答日志表。
2. 新增引用记录表。
3. 问答成功和失败都写日志。
4. 记录模型、耗时、topK、minScore。
5. 任务分页查询。
6. 任务失败重试。
7. 无答案问题统计。

验收标准：

- 每次问答都有日志。
- 每次命中的 chunk 都有引用记录。
- 管理端能查询失败问答和失败任务。
- 能统计高频问题和无答案问题。

### 第 4 阶段：权限治理和检索质量增强

目标：

让系统达到企业可控、可治理、可持续优化。

需求：

1. 文档权限字段。
2. 文档授权表。
3. 查询时按用户权限过滤。
4. 切片启用、禁用。
5. 切片人工修正。
6. 多路召回：向量检索 + 关键词检索。
7. rerank 重排。
8. 问答反馈：有用、无用、纠错。

验收标准：

- 用户只能检索自己有权限的文档。
- 管理员可以禁用低质量 chunk。
- 问答质量可以通过反馈和日志持续优化。

## 第一阶段建议接口清单

### 发布文档版本

```text
POST /api/knowledge/documents/{documentId}/versions/{versionId}/publish
```

行为：

- 校验文档存在。
- 校验版本存在且属于该文档。
- 校验版本向量状态为 `COMPLETED`。
- 将旧当前版本标记为废止。
- 设置当前版本。
- 设置文档状态为 `PUBLISHED`。

### 重新向量化文档版本

```text
POST /api/knowledge/documents/{documentId}/versions/{versionId}/revectorize
```

行为：

- 校验文档和版本。
- 清理旧 chunk 和旧向量。
- 创建新向量任务。
- 状态改为 `PENDING`。

### 企业文档问答

```text
POST /api/knowledge/documents/query
```

请求：

```json
{
  "categoryIds": [1, 2],
  "documentIds": [10, 11],
  "question": "差旅报销标准是什么？",
  "topK": 5,
  "minScore": 0.2
}
```

响应：

```json
{
  "answer": "根据已发布制度，差旅报销标准为...",
  "references": [
    {
      "documentId": 10,
      "versionId": 20,
      "chunkId": 300,
      "chunkIndex": 2,
      "documentTitle": "差旅报销制度",
      "versionNo": "v1.0",
      "source": "差旅报销制度.pdf"
    }
  ]
}
```

### 企业文档流式问答

```text
POST /api/knowledge/documents/query/stream
```

行为：

- 检索逻辑与普通问答一致。
- 使用 SSE 流式返回模型内容。
- 引用可以在结束事件中返回，或通过普通问答接口获取。

## 第一阶段建议数据库迁移

### 向量任务表补充文档字段

```sql
ALTER TABLE knowledge_base_vector_task
    ADD COLUMN document_id BIGINT NULL COMMENT '企业知识文档ID',
    ADD COLUMN version_id BIGINT NULL COMMENT '企业知识文档版本ID';

CREATE INDEX idx_vector_task_document
    ON knowledge_base_vector_task (document_id);

CREATE INDEX idx_vector_task_version
    ON knowledge_base_vector_task (version_id);
```

### 问答日志表

```sql
CREATE TABLE IF NOT EXISTS knowledge_query_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    question TEXT NOT NULL COMMENT '用户问题',
    answer MEDIUMTEXT COMMENT '模型回答',
    user_id BIGINT COMMENT '提问用户ID',
    session_id VARCHAR(100) COMMENT '会话ID',
    top_k INT NOT NULL DEFAULT 5 COMMENT '召回数量',
    min_score DECIMAL(6, 4) COMMENT '最低相似度',
    model_name VARCHAR(100) COMMENT '模型名称',
    duration_ms BIGINT COMMENT '耗时毫秒',
    status VARCHAR(32) NOT NULL COMMENT '状态：SUCCESS、NO_RESULT、FAILED',
    error_message VARCHAR(500) COMMENT '失败原因',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) COMMENT = '企业知识问答日志表';
```

### 问答引用表

```sql
CREATE TABLE IF NOT EXISTS knowledge_query_reference (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    query_log_id BIGINT NOT NULL COMMENT '问答日志ID',
    document_id BIGINT COMMENT '文档ID',
    version_id BIGINT COMMENT '版本ID',
    chunk_id BIGINT COMMENT '切片ID',
    chunk_index INT COMMENT '切片序号',
    score DECIMAL(8, 6) COMMENT '相似度分数',
    source VARCHAR(255) COMMENT '来源文件名',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) COMMENT = '企业知识问答引用表';

CREATE INDEX idx_query_reference_log
    ON knowledge_query_reference (query_log_id);

CREATE INDEX idx_query_reference_document
    ON knowledge_query_reference (document_id);
```

## 风险说明

1. 数据库迁移有风险，尤其是给 `knowledge_base_vector_task` 加字段前，需要确认当前数据库是否已经手工添加过字段。
2. 旧 `knowledge_base` 暂不删除，避免破坏已有上传、查询、下载接口。
3. 新问答接口会依赖 PGVector metadata，必须保证新文档向量写入字段稳定。
4. 发布流程改变后，上传完成不再自动可问答，需要前端或管理端增加发布操作。
5. 问答日志可能保存较长文本，表数据增长较快，后续需要归档策略。

## 推荐实施顺序

1. 修复 `vectorizeVersion()` 状态流。
2. 补齐 `knowledge_base_vector_task.document_id/version_id` 迁移。
3. 调整向量化完成后不自动发布。
4. 实现版本发布接口。
5. 实现企业文档查询服务和接口。
6. 增强引用返回。
7. 新增问答日志和引用表。
8. 增加任务和问答观测接口。

## 后续实施边界

第一阶段不做：

- 完整审批流。
- 多租户隔离。
- 复杂权限模型。
- rerank 重排。
- 多路召回。
- 人工 chunk 编辑。

这些能力放到第二阶段以后逐步补齐，避免第一阶段范围过大。
