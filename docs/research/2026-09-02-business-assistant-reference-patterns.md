# mc-ai 业务助手与组合报告：官方参考模式研究

> 日期：2026-09-02  
> 范围：只研究可借鉴模式，不修改代码、`pom.xml`、配置或数据库。  
> 证据约束：仅采用官方文档、项目官方 GitHub 仓库源码或公开规范。

## 1. 结论摘要

1. **模型只负责生成受约束的分析结构，业务数据、权限和报告事实仍由应用掌握。** Spring AI 的结构化输出转换是“尽力而为”，不能把 POJO 反序列化成功等同于业务正确；应在应用层继续做枚举、数量、字段和交叉一致性校验。
2. **结构化报告与流式文本应分层。** Spring AI 2.0 的 `stream()` 返回内容片段或 `ChatResponse` 流，结构化实体需要聚合完整内容后转换；因此组合报告先确定性构造块，再用现有 SSE 协议推送状态/块/文本，不能逐 token 解析半截 JSON。
3. **工具调用是应用执行，不是模型直连业务接口。** 只向单次请求暴露已授权工具；租户、用户、授权信息通过应用上下文传递并在真正调用前二次校验，不能进入模型可编辑参数。
4. **XLSX/DOCX/PDF 导出应消费同一个安全快照。** XLSX 小数据用 XSSF、大数据才用 SXSSF；DOCX 仅用 XWPF 高层 API 能覆盖的能力；PDFBox 是低层绘制库，分页、换行、表格都要由本项目确定性布局代码负责。
5. **后台任务采用 MySQL 唯一键 + 短事务认领 + 有期限租约。** 租约只是崩溃恢复机制，不保证 exactly-once；最终写入仍需幂等键/版本条件。`SKIP LOCKED` 只适合队列式认领，不适合普通业务读取。
6. **权限、字段和持久化边界保持当前项目方向。** 创建任务、执行任务、下载产物均按当前用户重新校验；只保存白名单投影后的快照、校验和和必要审计元数据，不保存模型原始 completion、工具原始响应、Token 或认证信息。
7. **Vue 使用有判别字段的块协议 + 本地组件注册表。** 后端只返回 `type + data`，前端只从固定注册表选择组件；未知类型安全降级，不将服务端内容当模板或 `v-html` 渲染。

## 2. 项目适配基线

- 当前父 POM 为 Java 17、Spring Boot 4.0.6、Spring AI 2.0.0；Spring AI 官方仓库明确 2.x 对应 Spring Boot 4.x。[Spring AI v2.0.0 官方仓库标签](https://github.com/spring-projects/spring-ai/tree/v2.0.0)
- 当前 `ai-agent` 已有 MySQL、JDBC、MyBatis Plus、Flyway、Spring AI 和 MVC 能力，未发现 Apache POI 或 PDFBox 依赖。本研究只提供模式；若后续确需导出，新增依赖属于单独的风险确认与版本选型任务。
- 当前项目已经存在“字段字典投影、安全结果快照、原始响应不落盘、执行前重验权限”的实现方向。建议扩展现有边界，不另建通用编排框架、微服务、MQ 或插件系统。

## 3. Spring AI：结构化输出、工具调用和流式边界

### 3.1 用强类型结果承载“模型分析”，并做第二层业务校验

- **可借鉴点：** `ChatClient.call().entity(...)` 使用 `StructuredOutputConverter`/`BeanOutputConverter` 将完整模型文本转换为 Java 类型；官方明确转换器只是 best effort，模型并不保证遵守格式。Spring AI 2.0 还提供 schema 校验/提供方原生结构化输出开关，但仍不能替代业务校验。[官方 Output Converters](https://docs.spring.io/spring-ai/reference/api/structured-output/converters.html)；[v2.0.0 `DefaultChatClient` 转换源码](https://github.com/spring-projects/spring-ai/blob/v2.0.0/spring-ai-client-chat/src/main/java/org/springframework/ai/chat/client/DefaultChatClient.java)
- **与本项目的适配方式：** 把模型结果限制为小而稳定的 `ReportAnalysisResult` 一类 DTO，例如 `summary`、`insights[]`、`risks[]`、`suggestions[]`；数据表、指标、引用和权限字段由确定性代码生成。反序列化后继续校验允许枚举、字符串长度、条数上限、引用 ID 是否来自当前安全快照；失败时走现有降级分析，不把模型 JSON 直接作为报告协议。
- **明确不照搬的部分：** 不把“能反序列化”当成可信；不让模型生成数据库字段路径、权限码、任意组件名或文件路径；不因较新文档出现 API 就默认 Spring AI 2.0.0 一定具备相同小版本行为，实施时以 `v2.0.0` 标签源码和编译结果为准。
- **直接来源链接：** [Spring AI 结构化输出转换器](https://docs.spring.io/spring-ai/reference/api/structured-output/converters.html)；[Spring AI v2.0.0 源码标签](https://github.com/spring-projects/spring-ai/tree/v2.0.0)

### 3.2 流式传输块事件，不流式解析半截 JSON

- **可借鉴点：** 官方 `ChatClient.stream()` 返回 `Flux<String>`、`Flux<ChatResponse>` 或 `Flux<ChatClientResponse>`；结构化实体的流式便利方法并不存在，官方示例要求先聚合内容，再显式转换。[官方 ChatClient Streaming Responses](https://docs.spring.io/spring-ai/reference/api/chatclient.html#_streaming_responses)；[v2.0.0 流式实现源码](https://github.com/spring-projects/spring-ai/blob/v2.0.0/spring-ai-client-chat/src/main/java/org/springframework/ai/chat/client/DefaultChatClient.java)
- **与本项目的适配方式：** 保持现有 MVC/SSE 协议，在应用层定义稳定事件，如 `report_started`、`report_block`、`analysis_delta`、`artifact_ready`、`completed`、`error`。结构化块先完整校验后一次推送；只有普通叙述文本允许 delta。断线重连依赖事件 ID/已保存安全快照，而不是重放模型原始流。
- **明确不照搬的部分：** 不对 token 片段做增量 JSON 反序列化；不把 Reactor 类型扩散到 Service/Mapper；不为了流式新增 WebFlux 通用架构；不把 Spring AI 的内部 `ChatResponse` 直接暴露为前端协议。
- **直接来源链接：** [Spring AI ChatClient API](https://docs.spring.io/spring-ai/reference/api/chatclient.html)；[Spring AI v2.0.0 `DefaultChatClient`](https://github.com/spring-projects/spring-ai/blob/v2.0.0/spring-ai-client-chat/src/main/java/org/springframework/ai/chat/client/DefaultChatClient.java)

### 3.3 工具只暴露单次允许集合，真实执行前重验权限

- **可借鉴点：** Spring AI 明确“应用拥有工具调用逻辑”，模型只能请求调用；应用执行工具。工具上下文可传租户/用户等不发送给模型的数据；风险工具应按单次请求注册，默认全局工具可能危险。[官方 Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html)
- **与本项目的适配方式：** 继续复用现有 Capability/Tool/Service 链路。路由阶段只产生候选计划；执行阶段根据当前用户、当前资源授权、当前版本再次构建本次允许工具集合。`userId`、PM Token、tenant/department 范围只放 `ToolExecutionContext` 或服务端请求上下文，模型参数仅保留业务白名单字段。
- **明确不照搬的部分：** 不把所有 `ToolCallback` 作为全局默认工具；不开启“按名字从全局 resolver 回退执行”来绕过本次允许集合；不允许模型传入 userId、permission、authorization；不让 Agent 绕过 Service 直连 Mapper/数据库。
- **直接来源链接：** [Spring AI 工具调用架构与 Tool Context](https://docs.spring.io/spring-ai/reference/api/tools.html#_tool_context)；[Spring AI 工具注册边界](https://docs.spring.io/spring-ai/reference/api/tools.html#_passing_tools_to_chatclient)

### 3.4 默认不记录 prompt、completion、工具参数和工具结果

- **可借鉴点：** Spring AI 官方观测文档因内容大且可能敏感，默认不导出 prompt/completion，也默认不导出工具参数和结果。[官方 Observability](https://docs.spring.io/spring-ai/reference/observability/index.html)
- **与本项目的适配方式：** 生产环境只记录 runId、模型标识、token 数、延迟、finish reason、结构校验结果、失败分类和安全校验和。错误日志也只记录摘要，不记录原始 completion、工具原始响应或业务数据。
- **明确不照搬的部分：** 不在生产打开 `log-prompt`、`log-completion` 或工具 content 观测开关；不把完整 AI/provider 响应当审计快照；调试环境若临时开启，必须有隔离、脱敏和短期保留策略。
- **直接来源链接：** [Spring AI Prompt and Completion Data](https://docs.spring.io/spring-ai/reference/observability/index.html#_prompt_and_completion_data)；[Tool Call Arguments and Result Data](https://docs.spring.io/spring-ai/reference/observability/index.html#_tool_call_arguments_and_result_data)

## 4. Apache POI：XLSX 与 DOCX

### 4.1 XLSX 采用“报告快照 -> 格式渲染器”，按规模选择 XSSF/SXSSF

- **可借鉴点：** POI 官方把 XSSF 定义为 XLSX 的完整 usermodel；SXSSF 是在 XSSF 上的低内存滑动窗口写出，旧行刷盘后不可访问。官方也提醒 XSSF 的 XML 内存开销更高。[官方 Spreadsheet 概览](https://poi.apache.org/components/spreadsheet/index)；[官方 Quick Guide](https://poi.apache.org/components/spreadsheet/quick-guide.html)
- **与本项目的适配方式：** 导出器只消费已经冻结并通过字段权限过滤的组合报告快照。普通规模优先 `XSSFWorkbook`；只有经压测确认的大表才切换 `SXSSFWorkbook`，并预先完成列、样式、公式和合计规划。复用少量 `CellStyle`/`Font`，sheet 名通过 `WorkbookUtil.createSafeSheetName` 处理。
- **明确不照搬的部分：** 不统一使用 SXSSF；不在循环里为每个单元格创建样式；不把任意业务原始 JSON 展平为列；不依赖 SXSSF 已刷出的行做回读、全局公式计算或克隆 sheet。
- **直接来源链接：** [POI XSSF/SXSSF 官方说明](https://poi.apache.org/components/spreadsheet/index)；[POI XLSX 官方快速指南](https://poi.apache.org/components/spreadsheet/quick-guide.html)

### 4.2 SXSSF 必须显式管理临时文件生命周期

- **可借鉴点：** SXSSF 每个 sheet 使用临时 XML 文件，可能远大于输入；`dispose()` 用于删除临时文件，`close()` 关闭底层 workbook/package。[官方 `SXSSFWorkbook` Javadoc](https://poi.apache.org/apidocs/dev/org/apache/poi/xssf/streaming/SXSSFWorkbook.html)
- **与本项目的适配方式：** 任务执行器用 `try/finally` 保证关闭 workbook、输出流并清理临时文件；产物先写任务专属临时路径，成功校验后再原子发布到正式路径。租约超时/进程重启时由清理任务按任务 ID 和 TTL 清除孤儿临时文件。
- **明确不照搬的部分：** 不假定 `close()` 等同于所有版本的临时文件清理；不使用实验性的 `writeAvoidingTempFiles` 作为生产默认；不把临时文件放到不可控共享目录后永久遗留。
- **直接来源链接：** [SXSSFWorkbook `close`/`dispose`/临时文件说明](https://poi.apache.org/apidocs/dev/org/apache/poi/xssf/streaming/SXSSFWorkbook.html)

### 4.3 DOCX 只依赖 XWPF 高层能力，复杂版式先降级

- **可借鉴点：** POI 官方称 XWPF 核心 API 较稳定但并不完整，复杂功能可能需要进入底层 XMLBeans；官方建议优先参考 examples 和单元测试。[官方 XWPF Quick Guide](https://poi.apache.org/components/document/quick-guide-xwpf.html)；[官方 `XWPFDocument` 源码](https://github.com/apache/poi/blob/trunk/poi-ooxml/src/main/java/org/apache/poi/xwpf/usermodel/XWPFDocument.java)
- **与本项目的适配方式：** 首版限制为标题、段落、简单表格、页眉页脚和图片，全部从同一组合报告块模型渲染；将样式、字体、页边距集中在一个 DOCX renderer 中，并用固定模板测试中文、长文本、空数据和分页。
- **明确不照搬的部分：** 不复制 POI 内部包或直接依赖大量底层 OOXML 对象；不承诺 Word 与浏览器/PDF 像素级一致；不在首版实现目录域、复杂浮动、任意模板脚本或 DOCX 转 PDF。
- **直接来源链接：** [POI XWPF 官方快速指南](https://poi.apache.org/components/document/quick-guide-xwpf.html)；[Apache POI 官方仓库](https://github.com/apache/poi)

## 5. Apache PDFBox：PDF 与字体

### 5.1 把 PDFBox 视为低层绘图库，自行做确定性布局

- **可借鉴点：** PDFBox 官方 FAQ 明确其能绘制文本、图片等页面内容，但不提供高层分页、段落、自动换行或表格布局。[官方 PDF Creation FAQ](https://pdfbox.apache.org/3.0/faq.html#can-i-use-pdfbox-to-create-complex-layouts)
- **与本项目的适配方式：** 为有限报告块实现小型确定性 renderer：测量文本宽度、换行、分页、重复表头、页码和边距；先支持标题、文本、键值、简单表格、指标卡的打印等价物。生成后至少做页数/文本提取/文件头校验，并用渲染图片做视觉回归。
- **明确不照搬的部分：** 不把 PDFBox 当 HTML/CSS 排版引擎；不引入新的通用模板/布局框架；不在首版支持任意富文本、复杂图表或和 DOCX 共用坐标级实现。
- **直接来源链接：** [PDFBox 3.0 FAQ](https://pdfbox.apache.org/3.0/faq.html)；[Apache PDFBox 官方仓库](https://github.com/apache/pdfbox)

### 5.2 中文字体必须显式嵌入并做字形覆盖检查

- **可借鉴点：** 官方对超出 WinAnsiEncoding 的字符建议使用 `PDType0Font.load()`；官方示例用 `PDType0Font` 加载并嵌入 TrueType 字体输出 Unicode 文本。[PDFBox 字体 FAQ](https://pdfbox.apache.org/3.0/faq.html#im-getting-java-lang-illegalargumentexception-is-not-available-in-this-fonts-encoding-winansiencoding)；[官方 EmbeddedFonts 示例](https://github.com/apache/pdfbox/blob/trunk/examples/src/main/java/org/apache/pdfbox/examples/pdmodel/EmbeddedFonts.java)
- **与本项目的适配方式：** 使用项目明确授权、随应用发布的中文 TTF/OTF，不依赖 Windows 系统字体；启动或测试时验证字体资源存在，渲染前检查常用中文、数字、货币符号和缺字降级。字体流、`PDDocument`、`PDPageContentStream` 全部显式关闭。
- **明确不照搬的部分：** 不依赖开发机字体搜索与替换；不使用标准 14 字体输出中文；不随意打包许可证不允许再分发的字体；不假设所有复杂文字 shaping 都完整支持。
- **直接来源链接：** [PDFBox 3.0 Font Handling](https://pdfbox.apache.org/3.0/faq.html#font-handling)；[EmbeddedFonts.java](https://github.com/apache/pdfbox/blob/trunk/examples/src/main/java/org/apache/pdfbox/examples/pdmodel/EmbeddedFonts.java)

## 6. MySQL 租约与幂等任务

### 6.1 队列认领使用短事务 `FOR UPDATE SKIP LOCKED`

- **可借鉴点：** MySQL 官方说明 `SKIP LOCKED` 不等待被锁行并将其排除，返回的是不一致视图，因此不适合普通事务，但适合多会话访问队列式表。[MySQL 8.4 Locking Reads](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html)
- **与本项目的适配方式：** 在极短事务内按 `status + next_run_at + id` 索引选一小批待执行任务并锁行，立即更新为 `RUNNING`、写入 `lease_owner`、`lease_until`、`attempt_no` 后提交；耗时的模型调用和文件生成必须在事务外执行。完成更新带 `id + lease_owner + status=RUNNING` 条件，避免旧 worker 覆盖新租约持有者。
- **明确不照搬的部分：** 不在持有行锁时调用模型、业务接口或生成文件；不把 `SKIP LOCKED` 用于用户查询、报表事实读取或权限读取；不在未确认 MySQL 版本/复制模式前直接启用。
- **直接来源链接：** [MySQL 官方 `NOWAIT`/`SKIP LOCKED` 语义](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html#innodb-locking-reads-nowait-skip-locked)

### 6.2 借鉴 ShedLock 的“唯一行 + DB 时间 + 有期限租约”，不引入 ShedLock

- **可借鉴点：** ShedLock JDBC 模式以主键名称唯一一行，只有 `lock_until <= now` 才更新取得租约；官方强烈推荐 DB 时间以避免应用节点时钟漂移，并明确租约超时后任务可能重复执行。[ShedLock 官方 README](https://github.com/lukas-krecan/ShedLock/blob/master/README.md)；[`JdbcTemplateLockProvider` 官方源码](https://github.com/lukas-krecan/ShedLock/blob/master/providers/jdbc/shedlock-provider-jdbc-template/src/main/java/net/javacrumbs/shedlock/provider/jdbctemplate/JdbcTemplateLockProvider.java)
- **与本项目的适配方式：** 在现有任务表内实现同等小模式：数据库 `CURRENT_TIMESTAMP(3)` 计算租约，owner 使用实例 ID + 随机执行 ID，租约有最大时长；只对确实较长且可观测的任务做条件续租。使用现有 MyBatis Plus/Mapper 和 MySQL，不添加 ShedLock 依赖。
- **明确不照搬的部分：** 不复制 ShedLock AOP/注解体系；不把租约理解为 exactly-once；不默认无限续租；不采用跨节点 JVM 本地时间比较；不把简单模块化单体任务升级成分布式调度平台。
- **直接来源链接：** [ShedLock JDBC 官方说明](https://github.com/lukas-krecan/ShedLock#jdbctemplate)；[ShedLock 官方并发算法源码说明](https://github.com/lukas-krecan/ShedLock/blob/master/providers/jdbc/shedlock-provider-jdbc-template/src/main/java/net/javacrumbs/shedlock/provider/jdbctemplate/JdbcTemplateLockProvider.java)

### 6.3 幂等键保护“业务结果”，租约只保护“当前执行者”

- **可借鉴点：** MySQL 的唯一索引/主键可配合 `INSERT ... ON DUPLICATE KEY UPDATE` 原子处理重复键；Zalando 官方 API 指南建议资源级 secondary key 由服务端唯一约束保护，适合防止重复资源。[MySQL INSERT 官方文档](https://dev.mysql.com/doc/refman/8.4/en/insert-on-duplicate.html)；[Zalando 官方幂等设计指南](https://github.com/zalando/restful-api-guidelines/blob/main/chapters/http-requests.adoc)
- **与本项目的适配方式：** 组合报告创建使用服务端可解释的唯一键，例如 `(user_id, request_id)` 或业务输入规范化摘要；保存 `request_fingerprint`，同 key 不同参数直接冲突。产物发布、任务完成和快照写入均用状态条件/版本号实现幂等，重试返回同一任务/产物标识，不重复生成数据库实体。
- **明确不照搬的部分：** 不照搬“缓存并返回完整原始响应”的通用 Idempotency-Key 实现，因为本项目要求模型/工具原始响应不落盘；不只靠内存去重；不使用无唯一约束的“先查再插”。
- **直接来源链接：** [MySQL `ON DUPLICATE KEY UPDATE`](https://dev.mysql.com/doc/refman/8.4/en/insert-on-duplicate.html)；[Zalando idempotent POST patterns](https://github.com/zalando/restful-api-guidelines/blob/main/chapters/http-requests.adoc)

## 7. 权限、快照、字段白名单与不落原始响应

### 7.1 在执行和下载时按当前主体再次授权，失败关闭

- **可借鉴点：** OWASP ASVS 5.0 要求在可信 Service 层执行功能级、数据级、字段级授权，并要求授权依据变化及时生效；Spring Security 官方将请求级和方法级授权视为纵深防御。[OWASP ASVS V8](https://github.com/OWASP/ASVS/blob/master/5.0/en/0x17-V8-Authorization.md)；[Spring Security Method Security](https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html)
- **与本项目的适配方式：** 创建组合报告时校验一次；worker 真正执行每个 Capability/Tool 前按原始用户重新读取当前权限；下载/查看安全快照时再校验当前用户、资源范围和租户。权限服务不可用时失败关闭，不能使用任务创建时的权限快照兜底执行。
- **明确不照搬的部分：** 当前项目未使用 Spring Security 作为主权限框架，不为此新增该框架；不把前端按钮、任务 owner 字段或历史会话权限当最终授权；不让后台机器身份扩大原始用户权限。
- **直接来源链接：** [OWASP ASVS 5.0 Authorization](https://github.com/OWASP/ASVS/blob/master/5.0/en/0x17-V8-Authorization.md)；[OWASP Authorization Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html)

### 7.2 快照只包含已授权投影，字段采用正向白名单

- **可借鉴点：** ASVS 要求字段级授权；OWASP Mass Assignment 指南推荐只允许可绑定的非敏感字段，并用 DTO 避免直接绑定领域实体。[OWASP ASVS V8.2.3](https://github.com/OWASP/ASVS/blob/master/5.0/en/0x17-V8-Authorization.md)；[OWASP Mass Assignment Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Mass_Assignment_Cheat_Sheet.html)
- **与本项目的适配方式：** 在一次执行开始时冻结“已发布能力/工作流版本 + 字段字典版本 + 报告定义版本”，但业务数据只存经 `CapabilityOutputProjector`/字段策略投影后的安全 DTO。组合报告块引用稳定 blockId、字段语义和 checksum；导出与重放只消费该安全快照，不重新读取可能变化的原始响应。
- **明确不照搬的部分：** 不保存完整 Entity/原始 JSON 后再靠展示层隐藏；不使用黑名单枚举敏感字段；不允许任意 JsonPath、脚本或反射式字段读取；权限快照只用于可复现说明，不能替代执行/下载时的当前授权。
- **直接来源链接：** [OWASP Mass Assignment 解决方案](https://cheatsheetseries.owasp.org/cheatsheets/Mass_Assignment_Cheat_Sheet.html#solutions)；[OWASP ASVS 字段级授权](https://github.com/OWASP/ASVS/blob/master/5.0/en/0x17-V8-Authorization.md)

### 7.3 原始模型和工具响应仅在内存短暂存在

- **可借鉴点：** Spring AI 默认不导出可能敏感的 prompt/completion 和工具参数/结果；OWASP Logging 指南要求敏感数据通常不应直接记录，应删除、掩码、散列或加密。[Spring AI Observability](https://docs.spring.io/spring-ai/reference/observability/index.html)；[OWASP Logging Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html#data-to-exclude)
- **与本项目的适配方式：** 原始响应只在“解析 -> 白名单投影 -> 校验”调用栈中存在；持久层只接收安全块、统计计数、引用标识、错误分类、模型元数据和 checksum。异常信息先分类，禁止把 provider body、业务响应或 token 写入任务表、trace、日志或产物。
- **明确不照搬的部分：** 不使用 Java 序列化/JSON blob 保存整个调用上下文；不保存“以便以后调试”的 completion；不让幂等机制缓存原始 HTTP body；不在失败时把原始内容塞进 `error_message`。
- **直接来源链接：** [Spring AI 内容默认不导出](https://docs.spring.io/spring-ai/reference/observability/index.html)；[OWASP 不应记录的数据](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html#data-to-exclude)

## 8. Vue 3 / Element Plus：通用结构化块渲染

### 8.1 使用判别联合 + 固定组件注册表

- **可借鉴点：** Vue 官方支持通过 `<component :is="...">` 动态选择组件；`v-for` 中稳定 primitive `key` 用于保持节点身份。[Vue 3 Components Basics](https://vuejs.org/guide/essentials/component-basics.html#dynamic-components)；[Vue 3 List Rendering](https://vuejs.org/guide/essentials/list.html#maintaining-state-with-key)
- **与本项目的适配方式：** 前端定义封闭 `ReportBlock` 判别联合，如 `text | metric | keyValue | table | warning | artifact`；本地 `blockRenderers` 将 type 映射到受信组件，`block.id` 作为 key。未知类型使用 `UnsupportedBlock` 显示安全提示并上报协议版本，不执行服务端指定模块路径。
- **明确不照搬的部分：** 不让后端传 Vue 组件名、import 路径、render function 或模板；不按数组下标作为会插入/更新块的 key；不为每种业务报告复制整套页面。
- **直接来源链接：** [Vue 动态组件](https://vuejs.org/guide/essentials/component-basics.html#dynamic-components)；[Vue 列表 key](https://vuejs.org/guide/essentials/list.html#maintaining-state-with-key)

### 8.2 Element Plus 组件是叶子渲染器，不是后端 DSL

- **可借鉴点：** Element Plus 官方提供 Table、Descriptions、Statistic、Alert、Result、Skeleton 等通用展示组件；Table 支持明确列定义、数据数组、summary 和 empty slot。[Element Plus 组件总览](https://element-plus.org/en-US/component/overview.html)；[Element Plus Table](https://element-plus.org/en-US/component/table.html)
- **与本项目的适配方式：** `metric` 映射 `el-statistic`，`keyValue` 映射 `el-descriptions`，`table` 映射 `el-table`，部分失败映射 `el-alert`，加载映射 `el-skeleton`。列定义来自后端安全快照中的可见字段清单，但 formatter、slot 和组件选择固定在前端；表格为空、部分成功、截断和导出就绪都有独立状态。
- **明确不照搬的部分：** 不把 Element Plus props/slot 名完整暴露给后端；不允许服务端注入 formatter 函数；不把大表所有行无分页推到浏览器；不把 UI 组件协议与数据库 Entity 一一绑定。
- **直接来源链接：** [Element Plus Table](https://element-plus.org/en-US/component/table.html)；[Element Plus Statistic](https://element-plus.org/en-US/component/statistic)；[Element Plus Skeleton](https://element-plus.org/en-US/component/skeleton)

### 8.3 所有模型/业务文本按纯文本渲染

- **可借鉴点：** Vue 官方安全指南说明模板插值会自动转义，而不可信模板或未经处理的 `v-html` 存在代码执行/XSS 风险。[Vue Security](https://vuejs.org/guide/best-practices/security.html)
- **与本项目的适配方式：** 摘要、洞察、风险和表格值默认使用插值文本；若未来支持 Markdown，必须限定语法并在独立、安全审查后的 renderer 中消毒，链接协议使用 allowlist。
- **明确不照搬的部分：** 不用 `v-html` 渲染模型输出、业务系统富文本或错误信息；不允许模型生成 Vue 模板/CSS；不把后端返回 URL 直接用于 `href/src` 而不校验协议和域名。
- **直接来源链接：** [Vue 官方安全最佳实践](https://vuejs.org/guide/best-practices/security.html)

## 9. 推荐的最小落地顺序

1. 先定义与现有回答协议兼容的封闭 `ReportBlock` DTO、协议版本和校验规则。
2. 复用现有字段投影与结果快照链路，完成“只存安全块，不存原始响应”。
3. 增加任务唯一键、租约字段与短事务认领；完成 owner 条件更新和过期恢复测试。
4. 将模型限制为安全快照上的小型分析 DTO；同步聚合、结构校验、业务校验后再生成块。
5. Vue 以固定组件注册表渲染块，并覆盖 unknown/empty/partial/error/reconnect 状态。
6. 最后再单独评估导出依赖。只有确认 POI/PDFBox 版本、字体授权、资源上限和视觉验收后，才修改 POM 与新增 Flyway/配置；本次研究不执行这些变更。

## 10. 实施前必须再次确认的版本/环境边界

- Spring AI 当前锁定 2.0.0，而官网默认页面可能展示 2.0.1；新增 API 必须以 `v2.0.0` 源码、IDE 补全和编译为准。
- MySQL 必须确认实际生产版本、事务隔离级别和复制模式；官方警告 `SKIP LOCKED` 对 statement-based replication 不安全。
- POI/PDFBox 当前不在项目依赖中；引入会修改 POM 和制品体积，必须独立说明风险并获确认。
- 中文字体必须确认再分发许可证、文件体积和容器部署路径；不能依赖 Windows 开发机环境。
- 导出验收不能只测“能打开”，还应验证文件大小上限、并发内存/临时磁盘、长中文、空值、分页、部分成功和权限撤销后的下载拒绝。
