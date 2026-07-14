# 字段语义建议接口并发优化设计

## 背景

`/api/agent/dictionaries/semantic/suggest` 当前将待分析字段按每批 8 个拆分，
但多个批次按顺序调用大模型。以 19 个待处理字段为例，需要依次执行
`8/8/3` 三次模型调用，总响应时间等于三次调用耗时之和，容易超过前端
30 秒请求超时。

本次只优化 Java 后台，不修改前端、数据库表结构、Maven 依赖和模型输出
完整性规则。

## 目标

- 最外层字段批次受控并发执行，降低接口总等待时间。
- 最大模型并发数为 3，避免无上限并发影响系统稳定性。
- 保留现有 JSON 截断检测、自适应二分、字段完整性校验和数据库字段回填。
- 合并结果时保持数据库查询得到的字段顺序。
- 每次真实模型调用继续记录唯一的 `callSequence` 和 Token 用量。

## 方案

### 独立线程池

在现有异步线程池配置中增加字段语义专用线程池：

- 核心线程数：3。
- 最大线程数：3。
- 等待队列容量为 30，队列满时拒绝新任务，保证模型调用并发数不会突破 3。
- 使用独立线程名前缀，便于日志和问题定位。
- 应用关闭时等待正在执行的字段语义任务完成。

字段语义任务不复用 Agent 聊天线程池，也不使用
`ForkJoinPool.commonPool`，避免不同业务相互抢占执行资源。

### 批次执行

`FieldSemanticServiceImpl.suggest()` 仍按每批 8 个字段构造最外层批次，
然后将批次提交给字段语义专用线程池并发执行。19 个字段正常情况下产生
三个并发任务，对应 `8/8/3`。

每个任务内部继续调用现有 `generateWithAdaptiveSplit()`：如果某一批出现
`finishReason=LENGTH`、无效 JSON、字段缺失、重复或未知，则只在该任务内
继续二分处理。这样能够控制最大并发数，同时保留现有完整性保障。

### 结果和异常

- 主线程等待所有最外层批次结束后，按照批次原始顺序合并结果。
- 并发完成顺序不会改变最终字段顺序。
- `AtomicInteger` 继续为真实模型调用分配唯一递增的 `callSequence`。
- 任意批次抛出 `BusinessException` 时，向上恢复原始业务异常。
- 线程池任务被拒绝时返回明确的“系统繁忙，请稍后重试”业务错误。
- 等待线程被中断时恢复中断标记，并返回明确的字段语义生成失败信息。
- 不通过增加或伪造 JSON 括号来掩盖模型截断。

## 测试

在现有 `FieldSemanticServiceImplTest` 中新增并发回归用例：

- 构造 19 个字段和三个模型批次。
- 使用同步点要求三次模型调用必须同时进入；旧的串行实现会因无法到达同步点而失败。
- 验证最终返回 19 条结果，顺序与输入一致。
- 验证最大并发模型调用数不超过 3。
- 验证三个模型调用的 `callSequence` 不重复。

继续运行现有截断、无效 JSON、字段回填和 Token 提取测试，并执行 Maven
全工程编译检查。

## 修改范围

- `ai-common/src/main/java/org/example/ai/agent/common/config/AgentAsyncConfiguration.java`
- `ai-agent/src/main/java/org/example/ai/agent/capability/service/impl/FieldSemanticServiceImpl.java`
- `ai-agent/src/test/java/org/example/ai/agent/capability/service/impl/FieldSemanticServiceImplTest.java`

不修改其他业务文件。
