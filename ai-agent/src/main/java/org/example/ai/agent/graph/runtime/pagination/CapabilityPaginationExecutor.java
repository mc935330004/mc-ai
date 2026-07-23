package org.example.ai.agent.graph.runtime.pagination;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.answer.model.AnswerFact;
import org.example.ai.agent.capability.invocation.runtime.SimpleJsonPathReader;
import org.example.ai.agent.graph.config.CapabilityPaginationConfig;
import org.example.ai.agent.graph.runtime.GraphExecutionException;
import org.example.ai.agent.plan.PlanStep;
import org.example.ai.agent.tool.BusinessCapabilityExecutor;
import org.example.ai.agent.tool.FieldMeta;
import org.example.ai.agent.tool.ToolExecutionContext;
import org.example.ai.agent.tool.ToolResult;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 业务能力自动分页执行器。
 *
 * 核心原则：
 * 1. 每一页继续复用BusinessCapabilityExecutor；
 * 2. 不直接调用HTTP接口；
 * 3. 不修改业务系统；
 * 4. 不限制业务记录总数；
 * 5. 中途失败时不返回部分数据；
 * 6. 检测业务接口忽略页码造成的重复页；
 * 7. 只聚合经过P0字段投影后的安全数据。
 */
@Component
@RequiredArgsConstructor
public class CapabilityPaginationExecutor {

    /**
     * 防止错误接口无限返回不同的满页数据。
     *
     * 该限制不是记录截断：
     * 达到上限时会明确失败，不会把部分结果标记为成功。
     */
    private static final int MAX_PAGE_REQUESTS = 100_000;

    private final BusinessCapabilityExecutor businessCapabilityExecutor;

    private final ObjectMapper objectMapper;

    private final SimpleJsonPathReader jsonPathReader;

    /**
     * 执行自动分页查询。
     */
    public ToolResult execute(
            ToolExecutionContext context,
            PlanStep baseStep,
            CapabilityPaginationConfig config) {

        if (config == null || !config.isEnabled()) {

            /*
             * 防御性兼容。
             *
             * 正常情况下非分页能力不会进入本方法。
             */
            return businessCapabilityExecutor.execute(
                    context,
                    baseStep
            );
        }

        ToolResult lastPageResult = null;
        try {
            Map<String, Object> baseInput =
                    baseStep.getInput() == null
                            ? Map.of()
                            : baseStep.getInput();

            int startPage =
                    requireInteger(
                            PaginationInputMapper.read(
                                    baseInput,
                                    config.pageNumberInputPath()
                            ),
                            config.pageNumberInputPath(),
                            true
                    );

            int pageSize =
                    requireInteger(
                            PaginationInputMapper.read(
                                    baseInput,
                                    config.pageSizeInputPath()
                            ),
                            config.pageSizeInputPath(),
                            false
                    );

            List<Object> workflowRecords =
                    new ArrayList<>();

            List<Object> displayRecords =
                    new ArrayList<>();

            List<AnswerFact> allFacts =
                    new ArrayList<>();

            List<FieldMeta> fields =
                    null;

            Object firstAuditInput =
                    null;

            Long expectedTotal =
                    null;

            int pageCount =
                    0;

            Set<String> pageFingerprints =
                    new LinkedHashSet<>();

            while (true) {

                if (pageCount >= MAX_PAGE_REQUESTS) {
                    return failure(
                            baseStep,
                            lastPageResult,
                            "GRAPH_PAGINATION_PAGE_LIMIT_EXCEEDED",
                            "自动分页请求次数超过安全上限，"
                                    + "未返回不完整数据"
                    );
                }

                int currentPage;

                try {
                    currentPage =
                            Math.addExact(
                                    startPage,
                                    pageCount
                            );
                } catch (ArithmeticException exception) {

                    return failure(
                            baseStep,
                            lastPageResult,
                            "GRAPH_PAGINATION_PAGE_OVERFLOW",
                            "自动分页页码超过整数范围"
                    );
                }

                Map<String, Object> pageInput =
                        PaginationInputMapper.writeCopy(
                                baseInput,
                                config.pageNumberInputPath(),
                                currentPage
                        );

                pageInput =
                        PaginationInputMapper.writeCopy(
                                pageInput,
                                config.pageSizeInputPath(),
                                pageSize
                        );

                /*
                 * 每一页创建独立PlanStep，
                 * 不修改原始baseStep。
                 */
                PlanStep pageStep =
                        baseStep.toBuilder()
                                .input(pageInput)
                                .build();

                ToolResult pageResult =
                        businessCapabilityExecutor.execute(
                                context,
                                pageStep
                        );

                if (pageResult == null) {
                    return failure(
                            baseStep,
                            lastPageResult,
                            "GRAPH_PAGINATION_EMPTY_RESULT",
                            "自动分页第"
                                    + currentPage
                                    + "页返回空执行结果"
                    );
                }

                if (!pageResult.isSuccess()) {
                    return pageFailure(
                            baseStep,
                            pageResult,
                            currentPage
                    );
                }

                lastPageResult =
                        pageResult;

                if (firstAuditInput == null) {
                    firstAuditInput =
                            pageResult.getInput();
                }

                if (fields == null
                        && pageResult.getFields() != null) {

                    fields =
                            pageResult.getFields();
                }

                if (pageResult.getFacts() != null) {
                    allFacts.addAll(
                            pageResult.getFacts()
                    );
                }

                PageData pageData =
                        readPageData(
                                pageResult,
                                config
                        );

                /*
                 * 如果接口忽略current参数，
                 * 第二页会与第一页完全相同。
                 */
                if (!pageData.workflowRecords().isEmpty()
                        && !pageFingerprints.add(
                        pageData.fingerprint())) {

                    return failure(
                            baseStep,
                            pageResult,
                            "GRAPH_PAGINATION_REPEATED_PAGE",
                            "业务接口连续返回重复分页数据，"
                                    + "可能没有正确处理页码参数："
                                    + currentPage
                    );
                }

                if (pageData.total() != null) {

                    if (expectedTotal == null) {
                        expectedTotal =
                                pageData.total();

                    } else if (!expectedTotal.equals(
                            pageData.total())) {

                        return failure(
                                baseStep,
                                pageResult,
                                "GRAPH_PAGINATION_TOTAL_CHANGED",
                                "分页过程中业务总数发生变化，"
                                        + "未返回可能不一致的数据"
                        );
                    }
                }

                workflowRecords.addAll(
                        pageData.workflowRecords()
                );

                displayRecords.addAll(
                        pageData.displayRecords()
                );

                pageCount++;

                if (expectedTotal != null
                        && workflowRecords.size()
                        > expectedTotal) {

                    return failure(
                            baseStep,
                            pageResult,
                            "GRAPH_PAGINATION_TOTAL_INCONSISTENT",
                            "实际聚合记录数超过业务接口声明的total"
                    );
                }

                boolean emptyPage =
                        pageData.workflowRecords()
                                .isEmpty();

                boolean shortPage =
                        pageData.workflowRecords()
                                .size() < pageSize;

                boolean reachedTotal =
                        expectedTotal != null
                                && workflowRecords.size()
                                == expectedTotal;

                /*
                 * total存在时必须严格以total校验完整性。
                 */
                if (expectedTotal != null) {

                    if (reachedTotal) {
                        return success(
                                baseStep,
                                lastPageResult,
                                workflowRecords,
                                displayRecords,
                                allFacts,
                                fields,
                                firstAuditInput,
                                expectedTotal,
                                pageCount,
                                startPage,
                                pageSize
                        );
                    }

                    if (emptyPage || shortPage) {
                        return failure(
                                baseStep,
                                pageResult,
                                "GRAPH_PAGINATION_INCOMPLETE",
                                "分页提前结束：已获取"
                                        + workflowRecords.size()
                                        + "条，但业务接口声明共有"
                                        + expectedTotal
                                        + "条"
                        );
                    }

                    continue;
                }

                /*
                 * 没有total时，只能使用空页或短页判断结束。
                 *
                 * 如果最后一页刚好等于pageSize，
                 * 会再请求一次空页进行确认，不会漏数据。
                 */
                if (emptyPage || shortPage) {
                    return success(
                            baseStep,
                            lastPageResult,
                            workflowRecords,
                            displayRecords,
                            allFacts,
                            fields,
                            firstAuditInput,
                            (long) workflowRecords.size(),
                            pageCount,
                            startPage,
                            pageSize
                    );
                }
            }

        } catch (GraphExecutionException exception) {

            return failure(
                    baseStep,
                    lastPageResult,
                    exception.getErrorCode(),
                    exception.getMessage()
            );

        } catch (IllegalArgumentException exception) {

            return failure(
                    baseStep,
                    lastPageResult,
                    "GRAPH_PAGINATION_INPUT_INVALID",
                    "自动分页输入参数配置不正确："
                            + exception.getMessage()
            );

        } catch (Exception exception) {

            /*
             * 不返回原始异常消息，
             * 避免业务URL、请求参数或响应正文泄露。
             */
            return failure(
                    baseStep,
                    lastPageResult,
                    "GRAPH_PAGINATION_EXECUTION_FAILED",
                    "自动分页执行失败"
            );
        }
    }

    /**
     * 从单页ToolResult读取机器数据、展示数据和total。
     */
    private PageData readPageData(
            ToolResult pageResult,
            CapabilityPaginationConfig config) {

        JsonNode workflowRoot =
                objectMapper.valueToTree(
                        pageResult.getWorkflowData()
                );

        SimpleJsonPathReader.ReadResult
                recordsReadResult =
                jsonPathReader.read(
                        workflowRoot,
                        config.recordsPath()
                );

        if (!recordsReadResult.found()
                || recordsReadResult.value() == null
                || !recordsReadResult.value().isArray()) {

            throw new GraphExecutionException(
                    "GRAPH_PAGINATION_RECORDS_INVALID",
                    "分页recordsPath没有指向数组："
                            + config.recordsPath()
            );
        }

        List<Object> machineRecords =
                convertArray(
                        recordsReadResult.value()
                );

        Long total =
                readTotal(
                        workflowRoot,
                        config.totalPath()
                );

        /*
         * 展示数据优先使用displayData。
         *
         * 如果展示视图没有相同的records容器，
         * 回退到经过安全投影的workflowData，
         * 绝不回退到raw。
         */
        List<Object> pageDisplayRecords =
                readOptionalArray(
                        pageResult.getDisplayData(),
                        config.recordsPath()
                );

        if (pageDisplayRecords == null
                || pageDisplayRecords.size()
                != machineRecords.size()) {

            pageDisplayRecords =
                    new ArrayList<>(
                            machineRecords
                    );
        }

        return new PageData(
                machineRecords,
                pageDisplayRecords,
                total,
                fingerprint(
                        recordsReadResult.value()
                )
        );
    }

    private Long readTotal(
            JsonNode workflowRoot,
            String totalPath) {

        if (!StringUtils.hasText(totalPath)) {
            return null;
        }

        SimpleJsonPathReader.ReadResult totalResult =
                jsonPathReader.read(
                        workflowRoot,
                        totalPath
                );

        if (!totalResult.found()
                || totalResult.value() == null
                || !totalResult.value()
                .isIntegralNumber()) {

            throw new GraphExecutionException(
                    "GRAPH_PAGINATION_TOTAL_INVALID",
                    "分页totalPath没有指向整数："
                            + totalPath
            );
        }

        long total =
                totalResult.value()
                        .longValue();

        if (total < 0) {
            throw new GraphExecutionException(
                    "GRAPH_PAGINATION_TOTAL_INVALID",
                    "分页total不能小于0"
            );
        }

        return total;
    }

    private List<Object> readOptionalArray(
            Object source,
            String path) {

        if (source == null) {
            return null;
        }

        JsonNode root =
                objectMapper.valueToTree(source);

        SimpleJsonPathReader.ReadResult result =
                jsonPathReader.read(
                        root,
                        path
                );

        if (!result.found()
                || result.value() == null
                || !result.value().isArray()) {

            return null;
        }

        return convertArray(
                result.value()
        );
    }

    private List<Object> convertArray(
            JsonNode arrayNode) {

        List<Object> result =
                new ArrayList<>(
                        arrayNode.size()
                );

        for (JsonNode item : arrayNode) {
            result.add(
                    objectMapper.convertValue(
                            item,
                            Object.class
                    )
            );
        }

        return result;
    }

    /**
     * 将页码和分页大小转换为整数。
     */
    private int requireInteger(
            Object value,
            String path,
            boolean allowZero) {

        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(
                    path + "必须是数字"
            );
        }

        long longValue =
                number.longValue();

        long minimum =
                allowZero ? 0L : 1L;

        if (longValue < minimum
                || longValue > Integer.MAX_VALUE) {

            throw new IllegalArgumentException(
                    path + "超出允许范围"
            );
        }

        return (int) longValue;
    }

    /**
     * 计算单页记录指纹，用于检测重复分页。
     */
    private String fingerprint(
            JsonNode recordsNode) {

        try {
            MessageDigest digest =
                    MessageDigest.getInstance(
                            "SHA-256"
                    );

            byte[] hash =
                    digest.digest(
                            recordsNode.toString()
                                    .getBytes(
                                            StandardCharsets.UTF_8
                                    )
                    );

            return HexFormat.of()
                    .formatHex(hash);

        } catch (NoSuchAlgorithmException exception) {

            throw new IllegalStateException(
                    "当前JDK不支持SHA-256",
                    exception
            );
        }
    }

    private ToolResult pageFailure(
            PlanStep baseStep,
            ToolResult pageResult,
            int currentPage) {

        String errorCode =
                StringUtils.hasText(
                        pageResult.getErrorCode()
                )
                        ? pageResult.getErrorCode()
                        : "GRAPH_PAGINATION_PAGE_FAILED";

        String errorMessage =
                "自动分页第"
                        + currentPage
                        + "页执行失败";

        if (StringUtils.hasText(
                pageResult.getErrorMessage())) {

            errorMessage += "："
                    + pageResult.getErrorMessage();
        }

        return failure(
                baseStep,
                pageResult,
                errorCode,
                errorMessage
        );
    }

    /**
     * 构造完整分页成功结果。
     */
    private ToolResult success(
            PlanStep baseStep,
            ToolResult lastPageResult,
            List<Object> workflowRecords,
            List<Object> displayRecords,
            List<AnswerFact> allFacts,
            List<FieldMeta> fields,
            Object firstAuditInput,
            long total,
            int pageCount,
            int startPage,
            int pageSize) {

        Map<String, Object> workflowData =
                aggregate(
                        workflowRecords,
                        total,
                        pageCount,
                        startPage,
                        pageSize
                );

        Map<String, Object> displayData =
                aggregate(
                        displayRecords,
                        total,
                        pageCount,
                        startPage,
                        pageSize
                );

        return ToolResult.builder()
                .success(true)
                .capabilityCode(
                        baseStep.getCapabilityCode()
                )
                .outputKey(
                        baseStep.getOutputKey()
                )

                /*
                 * data继续保持展示视图，兼容旧逻辑。
                 */
                .data(displayData)
                .workflowData(workflowData)
                .displayData(displayData)
                .businessCode(
                        lastPageResult == null
                                ? null
                                : lastPageResult
                                .getBusinessCode()
                )
                .businessMessage(
                        lastPageResult == null
                                ? null
                                : lastPageResult
                                .getBusinessMessage()
                )
                .emptyData(
                        workflowRecords.isEmpty()
                )
                .fields(fields)
                .facts(
                        Collections.unmodifiableList(
                                new ArrayList<>(
                                        allFacts
                                )
                        )
                )
                .input(firstAuditInput)
                .summary(
                        "自动分页查询完成，共请求"
                                + pageCount
                                + "页，完整聚合"
                                + workflowRecords.size()
                                + "条记录"
                )
                .build();
    }

    /**
     * 分页失败结果不携带已聚合的部分数据。
     */
    private ToolResult failure(
            PlanStep baseStep,
            ToolResult referenceResult,
            String errorCode,
            String errorMessage) {

        return ToolResult.builder()
                .success(false)
                .capabilityCode(
                        baseStep.getCapabilityCode()
                )
                .outputKey(
                        baseStep.getOutputKey()
                )
                .data(null)
                .workflowData(null)
                .displayData(null)
                .businessCode(
                        referenceResult == null
                                ? null
                                : referenceResult
                                .getBusinessCode()
                )
                .businessMessage(
                        referenceResult == null
                                ? null
                                : referenceResult
                                .getBusinessMessage()
                )
                .emptyData(true)
                .fields(
                        referenceResult == null
                                ? null
                                : referenceResult.getFields()
                )
                .facts(
                        referenceResult == null
                                ? List.of()
                                : referenceResult.getFacts()
                )
                .input(
                        referenceResult == null
                                ? null
                                : referenceResult.getInput()
                )
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .summary(errorMessage)
                .build();
    }

    private Map<String, Object> aggregate(
            List<Object> records,
            long total,
            int pageCount,
            int startPage,
            int pageSize) {

        Map<String, Object> result =
                new LinkedHashMap<>();

        result.put(
                "records",
                Collections.unmodifiableList(
                        new ArrayList<>(records)
                )
        );

        result.put("total", total);
        result.put("pageCount", pageCount);
        result.put("startPage", startPage);
        result.put("pageSize", pageSize);

        /*
         * 只有全部页面都成功后才会生成该对象，
         * 因此complete始终为true。
         */
        result.put("complete", true);

        return Collections.unmodifiableMap(
                result
        );
    }

    private record PageData(
            List<Object> workflowRecords,
            List<Object> displayRecords,
            Long total,
            String fingerprint) {
    }
}