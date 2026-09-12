# 业务助手运行手册

本手册记录业务助手「人员 + 项目 + 项目期间」可配置查询、确定性统计、友好缺失提示和报告导出链路的配置方式与运行行为，供管理员和运维人员参照。

## 1. 数据集配置

业务助手通过已注册的**报告数据集**（`ai_report_dataset`）承接外部业务数据。管理员在数据集管理页为每个数据集选择对应的已发布 READ 工作流，并配置字段映射。接口变化时只需调整数据集配置，无需修改 Java 代码。

人员业务查询相关数据集按 `domainCode` 归入以下逻辑类型：

| 数据集 | domainCode | 说明 |
|---|---|---|
| 项目基础 | `PROJECT_BASE` | 提供项目起止日期与项目标识，用于取得项目期间 |
| 项目成员 | `PROJECT_MEMBER` | 提供人员在项目中的成员关系有效期，用于区分项目人员期间关联 |
| 出差 | `TRAVEL` 或 `PERSON_TRAVEL` | 出差记录与金额 |
| 考勤 | 依赖 `PUNCH`、`LEAVE`、`SCHEDULE`、`CALENDAR`、`TRAVEL` | 打卡、请假、排班、日历共同构成考勤对账 |
| 报销 | `REIMBURSEMENT` 或 `PERSON_REIMBURSEMENT` | 报销记录与金额 |

要点：

- 每个数据集可独立选择不同的 READ 工作流和不同的入参映射，互不影响。
- 数据集必须 `enabled=true` 且 `subjectTypesJson` 声明允许 `PERSON`，才会进入人员查询计划。
- 同一逻辑类型（如两个 `TRAVEL`）同时启用会导致计划失败关闭，配置时必须保证唯一。

## 2. 项目期间字段与业务记录关联字段的必填规则

项目基础数据集（`PROJECT_BASE`）的字段映射必须产出 `calculation` 通道中的 `project_base` 事实，包含：

- `projectId` 或 `projectCode`（至少一个，用于校验项目一致）
- `projectStartDate`、`projectEndDate`（两者都必填，且 `start <= end`）

任一缺失或非法时，项目期间解析返回 `PERIOD_UNAVAILABLE`，只提示补充日期范围，不执行人员业务查询。

项目成员数据集（`PROJECT_MEMBER`）产出 `project_membership_records` 列表，每条记录包含 `employeeNo`、`projectId`/`projectCode`、`rosterStart`（必填）、`rosterEnd`（可空，为空表示至今有效）。

业务记录按以下规则分类关联（纯 Java 规则，不依赖模型）：

- 记录自带经过校验的项目编码或项目 ID → `DIRECT`（直接项目关联，计入项目统计）
- 记录无项目标识，但发生日期落在项目成员有效期内 → `PROJECT_PERSON_PERIOD`（项目人员期间关联，仅计数与标注）
- 记录无项目标识且无发生日期，或日期不在任何成员有效期内 → `UNKNOWN`
- 记录项目标识与目标项目冲突 → `UNRELATED`

只有 `DIRECT` 记录的金额和考勤进入项目正式统计；`PROJECT_PERSON_PERIOD`、`UNKNOWN`、`UNRELATED` 只形成固定关联说明，不复制金额或明细。

## 3. 状态语义：未配置、无记录、拒绝、失败

| 状态 | 含义 | 用户可见表现 |
|---|---|---|
| 未配置 | 对应数据集在管理页未启用或缺失 | 友好提示「数据源尚未配置，本次未纳入统计」，不抛异常 |
| 无记录（EMPTY） | 数据集执行成功但返回空 | 章节标记为空，不视为失败 |
| 拒绝（DENIED） | 来源系统权限校验拒绝 | 统一安全提示，不区分「不存在」与「无权查看」 |
| 失败（FAILED） | 执行或校验异常 | 章节标记失败，披露失败但已成功的业务区块不被清空 |

## 4. 报告生成规则

- **全部请求模块都不可用时，不创建报告任务**，只返回「当前尚未配置可用的数据源」等提示。
- **部分模块可用时允许生成报告**，缺失章节作为受限元数据进入报告，文件首页和对应章节显著标注数据不完整。
- 报告任务走 MySQL 租约队列异步执行（`CompositeReportWorker`），产出 XLSX、DOCX、PDF。
- 报告文件处理器（`BusinessReportFileHandler`）只消费安全快照的 `export` 通道，不读取来源系统原始响应。

## 5. 报告 worker、存储目录与重试

- `CompositeReportWorker` 周期性扫描待处理任务，通过 `tryClaim` 条件更新抢占任务，使用租约（`lease_until`）防止多 worker 重复处理。
- 租约默认 60 秒，扫描上限 10 个（`ai.business.composite-report.worker.lease-seconds` / `scan-limit` 可配置）。
- 处理失败进入 `RETRY`，默认延迟 30 秒；字段策略变更导致失败关闭（`REPORT_POLICY_CHANGED`），需重新创建报告。
- 目标路径形如 `reports/{taskId}/report.{ext}`，由 `SafeArtifactStorageService.store` 安全落盘；崩溃窗口内重试复用同一路径，避免重复文件。
- PDF 字体路径由环境变量 `AI_BUSINESS_REPORT_PDF_FONT_PATH` 提供，不硬编码本机路径（`ai.business.composite-report.pdf-font-path`）。

## 6. 权限行为

- 普通员工的可查询人员范围仅含本人，业务助手不能超出该范围用工号直接查询。
- 管理员或特殊权限用户可以取得来源系统允许的人员列表，但每次查询仍需来源系统权限复核。
- 项目期间解析固定按「人员复权 → 项目复权 → 项目基础 → 项目成员」顺序，人员与项目各自独立复权，任一拒绝即安全收尾，不执行后续业务查询。
- 报告生成/下载前对每个来源系统重新做权限复核，不以创建时授权为由继续访问。

## 7. 排查

- 报告任务卡在 `PENDING` 或 `RETRY`：检查 `CompositeReportWorker` 是否运行、租约是否被异常占用、`selectClaimCandidateIds` 是否返回预期任务。
- 项目期间提示「数据源尚未配置」：确认 `PROJECT_BASE` 数据集已启用且字段映射包含完整起止日期。
- 报告文件生成失败（`REPORT_GENERATION_FAILED`）：查看 worker 日志中 `REPORT_GENERATION_FAILED` 安全错误码；检查快照 `facts_json` 结构是否合法、PDF 字体路径是否可访问。
- 权限相关拒绝：确认来源系统按当前登录人角色返回的授权范围，业务助手只透传登录上下文，不本地推导权限。
