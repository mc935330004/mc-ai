# Task19 Report Dataset Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为现有报告数据集当前配置补齐管理员分页、详情、校验、保存、启停和结构化前端管理页面。

**Architecture:** 后台继续使用现有 `ReportDatasetService`、MyBatis Plus Mapper、当前配置表和 `ai_agent_admin` 权限，不新增数据库结构或配置历史。前端新增列表页、独立编辑页、一个 API 文件和一个纯转换工具；工作流与字段字典继续复用现有接口，所有安全校验以后端为准。

**Tech Stack:** Java 17、Spring Boot 4、MyBatis Plus、JUnit 5、Mockito、Vue 3、Element Plus、Vite、Node Test Runner。

---

## 文件职责

后台工作树 `D:/codex-home/.config/superpowers/worktrees/mc-ai/business-assistant`：

- `ai-agent/src/main/java/org/example/ai/agent/business/dataset/dto/ReportDatasetSaveDTO.java`：保存和独立校验请求，内部包含字段策略 DTO。
- `ai-agent/src/main/java/org/example/ai/agent/business/dataset/dto/ReportDatasetStatusDTO.java`：启停请求和乐观锁版本。
- `ai-agent/src/main/java/org/example/ai/agent/business/dataset/vo/ReportDatasetListVO.java`：列表安全返回。
- `ai-agent/src/main/java/org/example/ai/agent/business/dataset/vo/ReportDatasetDetailVO.java`：详情及字段策略安全返回。
- `ai-agent/src/main/java/org/example/ai/agent/business/dataset/vo/ReportDatasetValidationVO.java`：独立校验结果。
- `ai-agent/src/main/java/org/example/ai/agent/business/dataset/controller/ReportDatasetAdminController.java`：管理员 HTTP 协议转换。
- `ai-agent/src/main/java/org/example/ai/agent/business/dataset/ReportDatasetService.java`：声明管理用例。
- `ai-agent/src/main/java/org/example/ai/agent/business/dataset/impl/ReportDatasetServiceImpl.java`：分页、详情、转换、统一校验、保存和启停。
- `ai-agent/src/main/java/org/example/ai/agent/business/dataset/ReportDatasetValidator.java`：增加不依赖运行时值的映射定义校验。
- `ai-agent/src/test/java/org/example/ai/agent/business/dataset/ReportDatasetServiceTest.java`：数据集核心校验、事务和状态测试。
- `ai-agent/src/test/java/org/example/ai/agent/business/dataset/ReportDatasetContractTest.java`：DTO、VO 的结构化协议测试。
- `ai-agent/src/test/java/org/example/ai/agent/business/dataset/controller/ReportDatasetAdminControllerTest.java`：接口路径、委托和当前操作人测试。

前端工作树 `D:/codex-home/.config/superpowers/worktrees/enterprise-vue-admin/business-assistant-ui`：

- `src/api/reportDataset.js`：五个管理接口。
- `src/utils/reportDatasetForm.js`：详情、表单和请求的纯转换及轻量前端校验。
- `src/views/knowledge/report-dataset/index.vue`：分页列表和启停。
- `src/views/knowledge/report-dataset/save.vue`：四区块编辑页。
- `src/router/index.js`：数据集路由。
- `src/layout/Sidebar.vue`：AI 能力中心菜单和激活状态。
- `test/reportDatasetForm.test.js`：纯转换和无时间映射测试。
- `test/reportDatasetPageSource.test.js`：API、路由、菜单和页面安全约束测试。

## Task 1：建立后台管理数据协议

**Files:**
- Create: `ai-agent/src/main/java/org/example/ai/agent/business/dataset/dto/ReportDatasetSaveDTO.java`
- Create: `ai-agent/src/main/java/org/example/ai/agent/business/dataset/dto/ReportDatasetStatusDTO.java`
- Create: `ai-agent/src/main/java/org/example/ai/agent/business/dataset/vo/ReportDatasetListVO.java`
- Create: `ai-agent/src/main/java/org/example/ai/agent/business/dataset/vo/ReportDatasetDetailVO.java`
- Create: `ai-agent/src/main/java/org/example/ai/agent/business/dataset/vo/ReportDatasetValidationVO.java`
- Create: `ai-agent/src/test/java/org/example/ai/agent/business/dataset/ReportDatasetContractTest.java`

- [ ] **Step 1：先写失败的数据协议测试**

测试锁定前端所需的结构化请求字段，并防止列表返回原始 JSON：

```java
@Test
void saveRequestShouldUseStructuredMappingsAndFieldPolicies() {
    ReportDatasetSaveDTO dto = new ReportDatasetSaveDTO();
    dto.setSubjectTypes(List.of("PERSON"));
    dto.setQueryInputMapping(Map.of("employeeNo", "employee_no"));
    dto.setAccessInputMapping(Map.of());
    dto.setFields(List.of(new ReportDatasetSaveDTO.FieldDTO()));

    assertThat(dto.getSubjectTypes()).containsExactly("PERSON");
    assertThat(dto.getQueryInputMapping()).containsEntry("employeeNo", "employee_no");
    assertThat(dto.getAccessInputMapping()).isEmpty();
    assertThat(dto.getFields()).hasSize(1);
    assertThat(Arrays.stream(ReportDatasetSaveDTO.class.getDeclaredFields())
            .map(Field::getName)).doesNotContain("subjectTypesJson", "inputMappingJson");
}

@Test
void listViewShouldNotExposeRawConfigurationJson() {
    assertThat(Arrays.stream(ReportDatasetListVO.class.getDeclaredFields())
            .map(Field::getName))
            .doesNotContain("subjectTypesJson", "inputMappingJson", "configChecksum");
}
```

- [ ] **Step 2：运行测试并确认因类不存在而失败**

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17.0.19'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -pl ai-agent -am '-Dtest=ReportDatasetAdminControllerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

预期：编译失败，提示 DTO 或 VO 不存在。

- [ ] **Step 3：增加最小 DTO、VO 和 Controller**

`ReportDatasetSaveDTO` 使用结构化字段，不暴露数据库 JSON：

```java
@Data
public class ReportDatasetSaveDTO {
    private Long id;
    private Integer version;
    private String datasetCode;
    private String datasetName;
    private String domainCode;
    private List<String> subjectTypes;
    private String queryWorkflowCode;
    private String accessWorkflowCode;
    private Map<String, String> queryInputMapping;
    private Map<String, String> accessInputMapping;
    private Integer ttlMinutes;
    private String associationMode;
    private Integer maxConcurrency;
    private Boolean enabled;
    private List<FieldDTO> fields;

    @Data
    public static class FieldDTO {
        private Long fieldId;
        private String factCode;
        private String factName;
        private String factType;
        private Boolean calculable;
        private Boolean displayable;
        private Boolean exportable;
        private Boolean modelVisible;
        private Boolean filterable;
        private String maskStrategy;
        private String grain;
        private Integer displayOrder;
    }
}
```

`ReportDatasetStatusDTO` 只包含 `enabled` 和 `version`。列表 VO 不返回 JSON、校验和全文或审计敏感信息；详情 VO 使用 `List<String>`、两个 `Map<String,String>` 和字段 DTO 返回结构化配置。

- [ ] **Step 4：运行测试确认数据协议通过**

运行 Task 1 的测试命令。预期：`ReportDatasetContractTest` 全部通过。

- [ ] **Step 5：将新增类加入 Git 并提交**

```powershell
git add ai-agent/src/main/java/org/example/ai/agent/business/dataset/dto ai-agent/src/main/java/org/example/ai/agent/business/dataset/vo ai-agent/src/test/java/org/example/ai/agent/business/dataset/ReportDatasetContractTest.java
git commit -m "feat(agent): define dataset admin contract"
```

## Task 2：实现分页和详情

**Files:**
- Modify: `ai-agent/src/main/java/org/example/ai/agent/business/dataset/ReportDatasetService.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/business/dataset/impl/ReportDatasetServiceImpl.java`
- Modify: `ai-agent/src/test/java/org/example/ai/agent/business/dataset/ReportDatasetServiceTest.java`

- [ ] **Step 1：先写分页和详情失败测试**

覆盖页码范围、编码或名称关键字、业务域、启停状态、字段数量以及字段按 `displayOrder,id` 排序：

```java
@Test
void shouldReturnPagedDatasetSummariesWithFieldCounts() {
    ReportDataset dataset = existingDataset(10L, 2);
    Page<ReportDataset> resultPage = new Page<>(1, 10, 1);
    resultPage.setRecords(List.of(dataset));
    when(datasetMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(resultPage);
    when(fieldMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
            validField("amount", 1), validField("count", 2)
    ));

    Page<ReportDatasetListVO> result = service.pageCurrent(1, 10, "EMPLOYEE", "HR", true);

    assertThat(result.getTotal()).isEqualTo(1);
    assertThat(result.getRecords().get(0).getFieldCount()).isEqualTo(2);
    assertThat(result.getRecords().get(0).getDatasetCode()).isEqualTo("EMPLOYEE_PROFILE");
}

@Test
void shouldReturnDatasetDetailWithOrderedFieldsAndMappings() {
    when(datasetMapper.selectById(10L)).thenReturn(existingDataset(10L, 2));
    when(fieldMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
            validField("amount", 1)
    ));

    ReportDatasetDetailVO detail = service.detailCurrent(10L);

    assertThat(detail.getId()).isEqualTo(10L);
    assertThat(detail.getQueryInputMapping()).containsEntry("employeeNo", "employee_no");
    assertThat(detail.getFields()).extracting(ReportDatasetSaveDTO.FieldDTO::getFactCode)
            .containsExactly("amount");
}
```

- [ ] **Step 2：运行测试并确认方法缺失或断言失败**

运行：

```powershell
mvn -pl ai-agent -am '-Dtest=ReportDatasetServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

预期：新增测试失败，原因是分页、详情方法尚未实现。

- [ ] **Step 3：实现有上限的 MyBatis Plus 分页**

Service 接口增加：

```java
Page<ReportDatasetListVO> pageCurrent(
        long current, long size, String keyword, String domainCode, Boolean enabled);

ReportDatasetDetailVO detailCurrent(Long id);
```

实现约束：`current >= 1`，`size` 只允许 1 至 100。使用 `Wrappers.lambdaQuery()` 组合条件；取当前页数据集 ID 后，用一次 `fieldMapper.selectList(in datasetId)` 统计字段数，禁止逐行查询。详情不存在时返回 404 业务异常。

映射 JSON 统一在一个私有方法解析：

```java
private InputMappings readInputMappings(String json) {
    JsonNode root;
    try {
        root = objectMapper.readTree(json);
    } catch (JsonProcessingException exception) {
        throw new BusinessException(400, "inputMappingJson不是合法JSON对象");
    }
    if (root == null || !root.isObject()) {
        throw new BusinessException(400, "inputMappingJson不是合法JSON对象");
    }
    return new InputMappings(readStringMap(root.get("query")), readStringMap(root.get("access")));
}

private Map<String, String> readStringMap(JsonNode node) {
    if (node == null || !node.isObject()) {
        throw new BusinessException(400, "参数映射必须包含query和access对象");
    }
    Map<String, String> result = new LinkedHashMap<>();
    node.fields().forEachRemaining(entry -> {
        if (!entry.getValue().isTextual()) {
            throw new BusinessException(400, "参数映射值必须是字符串");
        }
        result.put(entry.getKey(), entry.getValue().textValue());
    });
    return Map.copyOf(result);
}

private record InputMappings(
        Map<String, String> query,
        Map<String, String> access) {
}
```

- [ ] **Step 4：运行 Service 和 Controller 测试**

```powershell
mvn -pl ai-agent -am '-Dtest=ReportDatasetServiceTest,ReportDatasetAdminControllerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

预期：分页、详情和协议测试通过。

- [ ] **Step 5：提交分页详情闭环**

```powershell
git add ai-agent/src/main/java/org/example/ai/agent/business/dataset ai-agent/src/test/java/org/example/ai/agent/business/dataset
git commit -m "feat(agent): query report datasets"
```

## Task 3：补齐统一配置校验

**Files:**
- Modify: `ai-agent/src/main/java/org/example/ai/agent/business/dataset/ReportDatasetValidator.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/business/dataset/ReportDatasetService.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/business/dataset/impl/ReportDatasetServiceImpl.java`
- Modify: `ai-agent/src/test/java/org/example/ai/agent/business/dataset/ReportDatasetServiceTest.java`

- [ ] **Step 1：先写参数映射失败测试**

新增测试分别证明：根节点缺少 `query`/`access`、目标参数不在 Schema、目标重复、权限工作流为空时允许空 `access`、没有时间条件时不强制时间映射、校验不写数据库。

```java
@Test
void validationShouldRejectMappingTargetOutsideWorkflowSchemaWithoutWriting() {
    ReportDatasetSaveDTO dto = validSaveDto();
    dto.setQueryInputMapping(Map.of("employeeNo", "unknown_field"));

    assertThatThrownBy(() -> service.validateCurrent(dto))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("目标参数不在inputSchema.properties中");
    verify(datasetMapper, never()).insert(any());
    verify(datasetMapper, never()).updateById(any());
    verify(fieldMapper, never()).delete(any(Wrapper.class));
}

@Test
void validationShouldAllowWorkflowWithoutTimeParameters() {
    ReportDatasetSaveDTO dto = validSaveDto();
    dto.setQueryInputMapping(Map.of("employeeNo", "employee_no"));
    dto.setAccessInputMapping(Map.of());
    dto.setAccessWorkflowCode(null);

    assertThat(service.validateCurrent(dto).isValid()).isTrue();
}
```

- [ ] **Step 2：运行测试并确认现有保存校验无法拦截**

运行 `ReportDatasetServiceTest`。预期：新增结构或 Schema 校验测试失败，证明当前只校验 JSON 根对象不足。

- [ ] **Step 3：增加映射定义校验并让运行时复用**

在 `ReportDatasetValidator` 增加：

```java
public void validateCanonicalMappingDefinition(
        Map<String, String> explicitMapping,
        JsonNode inputSchema) {
    JsonNode properties = requireObjectSchema(inputSchema);
    Set<String> mappedTargets = new HashSet<>();
    explicitMapping.forEach((source, target) -> {
        String canonicalName = requireExactText(source, "规范参数名不能为空");
        String targetName = requireExactText(target, "目标工作流参数名不能为空");
        rejectReserved(canonicalName, "规范参数");
        rejectReserved(targetName, "目标参数");
        if (!properties.has(targetName)) {
            throw new IllegalArgumentException("目标参数不在inputSchema.properties中：" + targetName);
        }
        if (!mappedTargets.add(targetName)) {
            throw new IllegalArgumentException("目标参数重复映射：" + targetName);
        }
    });
    validateRequiredTargetsHaveMappings(explicitMapping, inputSchema, properties);
}

private void validateRequiredTargetsHaveMappings(
        Map<String, String> explicitMapping,
        JsonNode inputSchema,
        JsonNode properties) {
    JsonNode required = inputSchema.get("required");
    if (required == null) {
        return;
    }
    if (!required.isArray()) {
        throw new IllegalArgumentException("inputSchema.required必须是数组");
    }
    Set<String> targets = new HashSet<>(explicitMapping.values());
    for (JsonNode item : required) {
        if (!item.isTextual() || !properties.has(item.textValue())) {
            throw new IllegalArgumentException("inputSchema.required包含非法参数");
        }
        if (!targets.contains(item.textValue())) {
            throw new IllegalArgumentException("required目标参数缺少映射：" + item.textValue());
        }
    }
}
```

现有 `validateCanonicalInputMapping` 先调用该方法，再只检查本次运行值是否都有映射以及 required 值是否非空，避免保存校验和运行时校验漂移。

`ReportDatasetServiceImpl` 将 DTO 转为 Entity 和字段实体后，统一执行：

```java
private PreparedConfiguration prepare(ReportDatasetSaveDTO dto) {
    ReportDataset dataset = toDataset(dto);
    List<ReportDatasetField> fields = toFields(dto.getFields());
    validateDataset(dataset);
    List<ReportDatasetField> validatedFields = validateFields(fields);
    PublishedWorkflow query = resolveReadOnlyWorkflow(dataset.getQueryWorkflowCode());
    PublishedWorkflow access = resolveOptionalReadOnlyWorkflow(dataset.getAccessWorkflowCode());
    InputMappings mappings = inputMappings(dto);
    validator.validateCanonicalMappingDefinition(mappings.query(), query.inputSchema());
    if (access == null && !mappings.access().isEmpty()) {
        throw new BusinessException(400, "未配置权限工作流时access映射必须为空");
    }
    if (access != null) {
        validator.validateCanonicalMappingDefinition(mappings.access(), access.inputSchema());
    }
    validateDictionaryFields(validatedFields, query.compiledGraph());
    applyNormalizedConfiguration(dataset, validatedFields, mappings);
    return new PreparedConfiguration(dataset, validatedFields);
}
```

`validateCurrent(dto)` 只调用 `prepare(dto)` 并返回 `new ReportDatasetValidationVO(true, "配置校验通过")`，不执行 Mapper 写操作。旧的 Entity 入口 `saveCurrent(ReportDataset,List,String)` 保留兼容测试和现有调用，但也必须进入同一校验链。

- [ ] **Step 4：验证统一校验测试**

```powershell
mvn -pl ai-agent -am '-Dtest=ReportDatasetServiceTest,ReportDatasetExecutionServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

预期：配置校验和运行时映射测试通过，旧执行链不回归。

- [ ] **Step 5：提交校验闭环**

```powershell
git add ai-agent/src/main/java/org/example/ai/agent/business/dataset ai-agent/src/test/java/org/example/ai/agent/business/dataset
git commit -m "fix(agent): validate dataset mappings before save"
```

## Task 4：实现保存和启停

**Files:**
- Create: `ai-agent/src/main/java/org/example/ai/agent/business/dataset/controller/ReportDatasetAdminController.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/business/dataset/ReportDatasetService.java`
- Modify: `ai-agent/src/main/java/org/example/ai/agent/business/dataset/impl/ReportDatasetServiceImpl.java`
- Modify: `ai-agent/src/test/java/org/example/ai/agent/business/dataset/ReportDatasetServiceTest.java`
- Create: `ai-agent/src/test/java/org/example/ai/agent/business/dataset/controller/ReportDatasetAdminControllerTest.java`

- [ ] **Step 1：先写保存和状态失败测试**

覆盖编码创建后不可修改、保存返回最新版本详情、启停要求匹配版本、不存在返回 404、冲突返回 409，并通过反射锁定管理端根路径和五个端点：

```java
@Test
void updateShouldRejectChangingStableDatasetCode() {
    ReportDatasetSaveDTO dto = validSaveDto();
    dto.setId(10L);
    dto.setVersion(2);
    dto.setDatasetCode("CHANGED_CODE");
    when(datasetMapper.selectById(10L)).thenReturn(existingDataset(10L, 2));

    assertThatThrownBy(() -> service.saveCurrent(dto, "admin-1"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("数据集编码创建后不能修改");
}

@Test
void statusUpdateShouldUseVersionAndOperator() {
    ReportDataset existing = existingDataset(10L, 2);
    when(datasetMapper.selectById(10L)).thenReturn(existing);
    when(datasetMapper.updateById(any(ReportDataset.class))).thenReturn(1);

    service.updateStatus(10L, false, 2, "admin-1");

    ArgumentCaptor<ReportDataset> captor = ArgumentCaptor.forClass(ReportDataset.class);
    verify(datasetMapper).updateById(captor.capture());
    assertThat(captor.getValue().getEnabled()).isFalse();
    assertThat(captor.getValue().getVersion()).isEqualTo(2);
    assertThat(captor.getValue().getUpdatedBy()).isEqualTo("admin-1");
}

@Test
void controllerShouldExposeExactlyFiveAdminOperations() throws Exception {
    RequestMapping root = ReportDatasetAdminController.class.getAnnotation(RequestMapping.class);
    assertThat(root.value()).containsExactly("/api/agent/admin/report-datasets");
    assertThat(ReportDatasetAdminController.class.getMethod(
            "pageList", long.class, long.class, String.class, String.class, Boolean.class
    ).getAnnotation(GetMapping.class).value()).containsExactly("/pageList");
    assertThat(ReportDatasetAdminController.class.getMethod("detail", Long.class)
            .getAnnotation(GetMapping.class).value()).containsExactly("/detail/{id}");
    assertThat(ReportDatasetAdminController.class.getMethod("validate", ReportDatasetSaveDTO.class)
            .getAnnotation(PostMapping.class).value()).containsExactly("/validate");
    assertThat(ReportDatasetAdminController.class.getMethod("save", ReportDatasetSaveDTO.class)
            .getAnnotation(PostMapping.class).value()).containsExactly("/save");
    assertThat(ReportDatasetAdminController.class.getMethod(
            "updateStatus", Long.class, ReportDatasetStatusDTO.class
    ).getAnnotation(PostMapping.class).value()).containsExactly("/{id}/status");
}
```

- [ ] **Step 2：运行测试并确认失败**

运行 `ReportDatasetServiceTest,ReportDatasetAdminControllerTest`。预期：保存 DTO 或状态更新尚未实现而失败。

- [ ] **Step 3：实现最小保存和状态更新**

Service 接口增加：

```java
ReportDatasetValidationVO validateCurrent(ReportDatasetSaveDTO dto);
ReportDatasetDetailVO saveCurrent(ReportDatasetSaveDTO dto, String operatorId);
void updateStatus(Long id, Boolean enabled, Integer version, String operatorId);
```

保存调用 Task 3 的统一 `prepare` 后复用现有事务写入。状态更新只读取当前行、校验 `id/version/enabled/operator`，设置必要字段后 `updateById`；不得整体覆盖其它配置字段。Controller 测试验证操作人来自当前登录用户。

Controller 固定使用现有 `Result` 包装，所有方法只做协议转换：

```java
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/agent/admin/report-datasets")
public class ReportDatasetAdminController {
    private final ReportDatasetService reportDatasetService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping("/pageList")
    public Result<Page<ReportDatasetListVO>> pageList(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String domainCode,
            @RequestParam(required = false) Boolean enabled) {
        return Result.success(reportDatasetService.pageCurrent(
                current, size, keyword, domainCode, enabled
        ));
    }

    @GetMapping("/detail/{id}")
    public Result<ReportDatasetDetailVO> detail(@PathVariable Long id) {
        return Result.success(reportDatasetService.detailCurrent(id));
    }

    @PostMapping("/validate")
    public Result<ReportDatasetValidationVO> validate(@RequestBody ReportDatasetSaveDTO dto) {
        return Result.success(reportDatasetService.validateCurrent(dto));
    }

    @PostMapping("/save")
    public Result<ReportDatasetDetailVO> save(@RequestBody ReportDatasetSaveDTO dto) {
        return Result.success(reportDatasetService.saveCurrent(
                dto, currentUserProvider.getRequiredUserId()
        ));
    }

    @PostMapping("/{id}/status")
    public Result<Void> updateStatus(
            @PathVariable Long id,
            @RequestBody ReportDatasetStatusDTO dto) {
        reportDatasetService.updateStatus(
                id, dto.getEnabled(), dto.getVersion(),
                currentUserProvider.getRequiredUserId()
        );
        return Result.success();
    }
}
```

- [ ] **Step 4：运行数据集完整后台测试**

```powershell
mvn -pl ai-agent -am '-Dtest=ReportDatasetServiceTest,ReportDatasetExecutionServiceTest,ReportDatasetAdminControllerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

预期：全部通过，0 failures，0 errors。

- [ ] **Step 5：提交后台管理闭环**

```powershell
git add ai-agent/src/main/java/org/example/ai/agent/business/dataset ai-agent/src/test/java/org/example/ai/agent/business/dataset
git commit -m "feat(agent): manage report dataset state"
```

## Task 5：建立前端 API 和表单转换

**Files:**
- Create: `src/api/reportDataset.js`
- Create: `src/utils/reportDatasetForm.js`
- Create: `test/reportDatasetForm.test.js`
- Create: `test/reportDatasetPageSource.test.js`

- [ ] **Step 1：先写纯转换和 API 失败测试**

```javascript
import test from 'node:test'
import assert from 'node:assert/strict'

const form = await import('../src/utils/reportDatasetForm.js').catch(() => ({}))

test('详情结构转换为可编辑表单并保留空时间映射', () => {
  const value = form.toReportDatasetForm({
    id: 10,
    version: 2,
    datasetCode: 'PERSON_TRAVEL',
    subjectTypes: ['PERSON'],
    queryInputMapping: { employeeNo: 'employee_no' },
    accessInputMapping: {},
    fields: [{ fieldId: 11, factCode: 'travel_amount', displayOrder: 1 }]
  })
  assert.deepEqual(value.queryInputMappings, [
    { source: 'employeeNo', target: 'employee_no' }
  ])
  assert.equal(value.queryInputMappings.some(item => item.source === 'startDate'), false)
})

test('表单转换为后台结构化请求', () => {
  const payload = form.toReportDatasetPayload({
    datasetCode: 'PERSON_TRAVEL',
    subjectTypes: ['PERSON'],
    queryInputMappings: [{ source: 'employeeNo', target: 'employee_no' }],
    accessInputMappings: [],
    fields: [{ fieldId: 11, factCode: 'travel_amount', displayOrder: 1 }]
  })
  assert.deepEqual(payload.queryInputMapping, { employeeNo: 'employee_no' })
  assert.deepEqual(payload.accessInputMapping, {})
  assert.equal('inputMappingJson' in payload, false)
})
```

源代码测试检查五个 API 均使用 `/api/agent/admin/report-datasets`，且不存在删除接口。

- [ ] **Step 2：运行测试并确认模块不存在**

```powershell
node --test test/reportDatasetForm.test.js test/reportDatasetPageSource.test.js
```

预期：失败，提示转换函数或 API 源文件不存在。

- [ ] **Step 3：实现最小 API 和纯转换工具**

```javascript
export function pageReportDatasets(params) {
  return request({ url: '/api/agent/admin/report-datasets/pageList', method: 'get', params })
}

export function getReportDatasetDetail(id) {
  return request({
    url: `/api/agent/admin/report-datasets/detail/${encodeURIComponent(id)}`,
    method: 'get'
  })
}

export function validateReportDataset(data) {
  return request({ url: '/api/agent/admin/report-datasets/validate', method: 'post', data })
}

export function saveReportDataset(data) {
  return request({ url: '/api/agent/admin/report-datasets/save', method: 'post', data })
}

export function updateReportDatasetStatus(id, data) {
  return request({
    url: `/api/agent/admin/report-datasets/${encodeURIComponent(id)}/status`,
    method: 'post',
    data
  })
}
```

转换工具使用 `Object.fromEntries` 组装映射，显式检查空 source/target、重复 source、重复 target、重复 factCode 和空字段集合；不增加表单框架依赖。

- [ ] **Step 4：运行纯工具测试**

运行 Task 5 指定测试。预期：全部通过。

- [ ] **Step 5：加入 Git 并提交**

```powershell
git add src/api/reportDataset.js src/utils/reportDatasetForm.js test/reportDatasetForm.test.js test/reportDatasetPageSource.test.js
git commit -m "feat(ui): add report dataset client"
```

## Task 6：实现数据集列表页

**Files:**
- Create: `src/views/knowledge/report-dataset/index.vue`
- Modify: `test/reportDatasetPageSource.test.js`

- [ ] **Step 1：先写列表页源代码失败测试**

测试锁定分页、筛选、路由恢复、失败回滚和无删除按钮：

```javascript
test('数据集列表支持筛选分页和安全启停', () => {
  assert.match(listSource, /pageReportDatasets/)
  assert.match(listSource, /updateReportDatasetStatus/)
  assert.match(listSource, /pagination\.current/)
  assert.match(listSource, /keyword/)
  assert.match(listSource, /domainCode/)
  assert.match(listSource, /enabled/)
  assert.match(listSource, /Object\.assign\(row, snapshot\)/)
  assert.doesNotMatch(listSource, /删除数据集|deleteReportDataset/)
})
```

- [ ] **Step 2：运行测试并确认页面不存在**

运行 `node --test test/reportDatasetPageSource.test.js`。预期：列表源码断言失败。

- [ ] **Step 3：实现列表页**

沿用现有 `pure-list-page`、`page-card`、`pure-data-table` 和分页样式。查询参数采用：

```javascript
const searchForm = reactive({ keyword: '', domainCode: '', enabled: '' })
const pagination = reactive({ current: 1, size: 10, total: 0 })

async function handleStatusChange(row, enabled) {
  const snapshot = { enabled: !enabled, version: row.version }
  try {
    await updateReportDatasetStatus(row.id, { enabled, version: row.version })
    row.version += 1
    ElMessage.success(enabled ? '数据集已启用' : '数据集已停用')
  } catch (error) {
    Object.assign(row, snapshot)
    ElMessage.error(getErrorMessage(error, '数据集状态保存失败'))
  }
}
```

表格列严格按设计文档实现；新增和编辑通过 Vue Router 跳转，并把分页筛选参数写入 query。

- [ ] **Step 4：运行列表源代码测试**

预期：列表相关断言通过。

- [ ] **Step 5：加入 Git 并提交**

```powershell
git add src/views/knowledge/report-dataset/index.vue test/reportDatasetPageSource.test.js
git commit -m "feat(ui): list report datasets"
```

## Task 7：实现数据集编辑页

**Files:**
- Create: `src/views/knowledge/report-dataset/save.vue`
- Modify: `test/reportDatasetForm.test.js`
- Modify: `test/reportDatasetPageSource.test.js`

- [ ] **Step 1：先写编辑页失败测试**

测试必须覆盖四个区域、工作流选择、字段字典选择、校验和保存按钮，并禁止原始 JSON 编辑器：

```javascript
test('编辑页提供四区块结构化配置且不暴露原始 JSON 编辑器', () => {
  assert.match(saveSource, /基础配置/)
  assert.match(saveSource, /查询与权限工作流/)
  assert.match(saveSource, /参数映射/)
  assert.match(saveSource, /字段与安全策略/)
  assert.match(saveSource, /validateReportDataset/)
  assert.match(saveSource, /saveReportDataset/)
  assert.match(saveSource, /pageWorkflows/)
  assert.match(saveSource, /pageFieldDictionaries/)
  assert.doesNotMatch(saveSource, /inputMappingJson|Codemirror|v-html/)
})
```

- [ ] **Step 2：运行测试并确认编辑页不存在**

运行 Task 5 两个测试文件。预期：编辑页源码断言失败。

- [ ] **Step 3：实现四区块编辑页**

页面状态保持简单：一个 `reactive(form)`、`loading`、`validating`、`saving`、工作流选项、字段选择弹窗和当前编辑行。工作流只请求 `publishStatus: 'PUBLISHED', enabled: 1`。

提交入口必须每次重新生成 payload：

```javascript
async function handleValidate() {
  const message = validateReportDatasetForm(form)
  if (message) return ElMessage.warning(message)
  validating.value = true
  try {
    await validateReportDataset(toReportDatasetPayload(form))
    ElMessage.success('配置校验通过')
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '配置校验失败'))
  } finally {
    validating.value = false
  }
}

async function handleSave() {
  const message = validateReportDatasetForm(form)
  if (message) return ElMessage.warning(message)
  saving.value = true
  try {
    await saveReportDataset(toReportDatasetPayload(form))
    ElMessage.success('数据集已保存')
    router.push({ path: '/knowledge/report-dataset/index', query: listQuery() })
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '数据集保存失败'))
  } finally {
    saving.value = false
  }
}
```

编辑现有数据集时禁用编码输入。参数映射行只包含 source、target；字段策略表直接编辑设计中的字段。关闭字段选择弹窗不能改变现有表单。

- [ ] **Step 4：运行转换和页面测试**

```powershell
node --test test/reportDatasetForm.test.js test/reportDatasetPageSource.test.js
```

预期：全部通过。

- [ ] **Step 5：加入 Git 并提交**

```powershell
git add src/views/knowledge/report-dataset/save.vue src/utils/reportDatasetForm.js test/reportDatasetForm.test.js test/reportDatasetPageSource.test.js
git commit -m "feat(ui): edit report datasets"
```

## Task 8：接入路由和侧栏

**Files:**
- Modify: `src/router/index.js`
- Modify: `src/layout/Sidebar.vue`
- Modify: `test/reportDatasetPageSource.test.js`

- [ ] **Step 1：先写路由菜单失败测试**

```javascript
test('报告数据集页面接入管理员路由和 AI 能力中心', () => {
  assert.match(routerSource, /path:\s*'report-dataset'/)
  assert.match(routerSource, /report-dataset\/index/)
  assert.match(routerSource, /report-dataset\/save\/:id\?/)
  assert.match(sidebarSource, /报告数据集管理/)
  assert.match(sidebarSource, /\/knowledge\/report-dataset\/index/)
})
```

- [ ] **Step 2：运行测试并确认路由不存在**

运行 `node --test test/reportDatasetPageSource.test.js`。预期：路由和菜单断言失败。

- [ ] **Step 3：增加嵌套路由、菜单和激活规则**

```javascript
{
  path: 'report-dataset',
  name: 'KnowledgeReportDataset',
  redirect: '/knowledge/report-dataset/index',
  children: [
    {
      path: 'index',
      name: 'KnowledgeReportDatasetIndex',
      meta: { title: '报告数据集管理' },
      component: () => import('@/views/knowledge/report-dataset/index.vue')
    },
    {
      path: 'save/:id?',
      name: 'KnowledgeReportDatasetSave',
      meta: { title: '新增/编辑报告数据集' },
      component: () => import('@/views/knowledge/report-dataset/save.vue')
    }
  ]
}
```

侧栏只增加一个子项，`activeMenu` 对所有 `/knowledge/report-dataset/` 地址返回列表路径。

- [ ] **Step 4：运行新增前端测试和生产构建**

```powershell
node --test test/reportDatasetForm.test.js test/reportDatasetPageSource.test.js
npm run build
```

预期：新增测试通过，Vite 构建退出码 0。

- [ ] **Step 5：提交路由接入**

```powershell
git add src/router/index.js src/layout/Sidebar.vue test/reportDatasetPageSource.test.js
git commit -m "feat(ui): route report dataset management"
```

## Task 9：全阶段验证和交付检查

**Files:**
- Verify only: backend and frontend worktrees

- [ ] **Step 1：检查两个工作树差异和新增文件跟踪状态**

```powershell
git status --short
git diff --check
git log --oneline -12
```

预期：计划内文件均已提交，没有未跟踪的新 Java、Vue 或测试文件。

- [ ] **Step 2：执行后台全模块干净测试**

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17.0.19'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -pl ai-agent -am clean test
```

预期：`BUILD SUCCESS`，0 failures，0 errors；已有显式跳过测试按实际数量报告。

- [ ] **Step 3：执行前端新增测试、全量测试和构建**

```powershell
node --test test/reportDatasetForm.test.js test/reportDatasetPageSource.test.js
npm test
npm run build
```

预期：Task19 新增测试全部通过，构建退出码 0。全量测试中的三个既有失败按文件名和实际错误单独报告，不声称全量测试全部通过。

- [ ] **Step 4：核对正式项目目录没有被隔离开发覆盖**

```powershell
git -C D:\IdeaProjects\mc-ai status --short
git -C D:\TraeProject\enterprise-vue-admin status --short
```

预期：只看到用户原有状态；前端正式目录中的 `vite.config.js` 和 `src/api/mdaes.js` 不被修改或清理。

- [ ] **Step 5：形成 Task19 完成报告并停在 Task20 门口**

完成报告必须包含：

- 实际修改文件和提交；
- 后台测试数量及结果；
- 前端新增测试、全量测试和构建结果；
- 三个延期基线问题；
- 未执行正式合并；
- “Task20 尚未开始，需要用户确认”的明确提示。
