package org.example.ai.agent.workflow.answer;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.entity.FieldDictionary;
import org.example.ai.agent.capability.mapper.FieldDictionaryMapper;
import org.example.ai.agent.workflow.runtime.PublishedWorkflow;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionOutcome;
import org.example.ai.agent.workflow.runtime.WorkflowRuntimeSnapshotResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 为工作流最终回答加载中文字段语义。
 *
 * 核心原则：
 * 1. 工作流内部继续使用英文机器字段；
 * 2. 只读取本次执行版本实际使用的能力；
 * 3. 只发送结果中实际出现的字段；
 * 4. visible=0的字段绝不发送给大模型；
 * 5. 不修改工作流执行结果本身。
 */
@Component
@RequiredArgsConstructor
public class WorkflowAnswerFieldContextResolver {

    private final WorkflowRuntimeSnapshotResolver snapshotResolver;
    private final WorkflowCapabilityCodeCollector capabilityCodeCollector;
    private final FieldDictionaryMapper fieldDictionaryMapper;
    private final ObjectMapper objectMapper;

    public List<WorkflowAnswerFieldContext> resolve(
            WorkflowExecutionOutcome outcome) {
        return resolvePolicy(outcome).visibleFields();
    }
    /**
     * 同时生成可展示字段和禁止发送给模型的字段。
     */
    public WorkflowAnswerFieldPolicy resolvePolicy(
            WorkflowExecutionOutcome outcome) {

        if (outcome == null || outcome.versionId() == null
                || !StringUtils.hasText(
                outcome.workflowCode())
                || outcome.result() == null) {
            return WorkflowAnswerFieldPolicy.empty();
        }

        PublishedWorkflow workflow =
                snapshotResolver.resolveExactVersion(
                        outcome.workflowCode(),
                        outcome.versionId()
                );

        List<String> capabilityCodes =
                capabilityCodeCollector.collect(
                        workflow.compiledGraph()
                );

        if (capabilityCodes.isEmpty()) {
            return WorkflowAnswerFieldPolicy.empty();
        }

        Set<String> returnedFieldNames =
                collectReturnedFieldNames(
                        outcome.result()
                );

        if (returnedFieldNames.isEmpty()) {
            return WorkflowAnswerFieldPolicy.empty();
        }

        List<FieldDictionary> dictionaries =
                fieldDictionaryMapper.selectList(
                        Wrappers
                                .<FieldDictionary>lambdaQuery()
                                .in(
                                        FieldDictionary
                                                ::getCapabilityCode,
                                        capabilityCodes
                                )
                                .eq(
                                        FieldDictionary
                                                ::getPublishStatus,
                                        "PUBLISHED"
                                )
                                .orderByAsc(
                                        FieldDictionary
                                                ::getCapabilityCode
                                )
                                .orderByAsc(
                                        FieldDictionary
                                                ::getDisplayOrder
                                )
                                .orderByAsc(
                                        FieldDictionary
                                                ::getId
                                )
                );

        if (dictionaries == null
                || dictionaries.isEmpty()) {

            /*
             * 工作流包含业务能力却没有发布字段字典时，
             * 不能把无法判断可见性的业务数据直接交给模型。
             */
            throw new IllegalStateException(
                    "工作流使用的能力没有可用的已发布字段字典"
            );
        }

        Map<String, WorkflowAnswerFieldContext>
                visibleFields = new LinkedHashMap<>();

        Set<String> hiddenFieldNames =
                new LinkedHashSet<>();

        for (FieldDictionary dictionary :
                dictionaries) {

            if (dictionary == null) {
                continue;
            }

            String fieldName =
                    resolveMachineFieldName(dictionary);

            if (!StringUtils.hasText(fieldName)
                    || !returnedFieldNames.contains(
                    fieldName)) {
                continue;
            }

            /*
             * visible=0：
             * 字段仍可存在于workflowData中，
             * 但是必须从模型输入中删除。
             */
            if (Integer.valueOf(0).equals(
                    dictionary.getVisible())) {

                hiddenFieldNames.add(fieldName);
                continue;
            }

            String capabilityCode =
                    trimToNull(
                            dictionary.getCapabilityCode()
                    );

            String uniqueKey =
                    capabilityCode + ":" + fieldName;

            visibleFields.putIfAbsent(
                    uniqueKey,
                    new WorkflowAnswerFieldContext(
                            capabilityCode,
                            fieldName,
                            StringUtils.hasText(
                                    dictionary.getFieldCnName())
                                    ? dictionary
                                    .getFieldCnName()
                                    .trim()
                                    : fieldName,
                            trimToNull(
                                    dictionary
                                            .getBusinessMeaning()
                            ),
                            trimToNull(
                                    dictionary
                                            .getDisplayFormat()
                            ),
                            trimToNull(
                                    dictionary
                                            .getDisplayGroup()
                            )
                    )
            );
        }
        return new WorkflowAnswerFieldPolicy(
                List.copyOf(
                        visibleFields.values()
                ),
                hiddenFieldNames
        );
    }
    /**
     * 递归收集结果对象中出现过的所有字段名称。
     */
    private Set<String> collectReturnedFieldNames(
            Object result) {

        JsonNode root =
                objectMapper.valueToTree(result);

        Set<String> names =
                new LinkedHashSet<>();

        collectNodeFieldNames(root, names);

        return names;
    }

    private void collectNodeFieldNames(
            JsonNode node,
            Set<String> names) {

        if (node == null
                || node.isNull()
                || node.isMissingNode()) {
            return;
        }

        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                names.add(field.getKey());
                collectNodeFieldNames(
                        field.getValue(),
                        names
                );
            }

            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collectNodeFieldNames(
                        child,
                        names
                );
            }
        }
    }

    /**
     * 字段英文名称为空时，与现有投影器保持一致，
     * 从字段路径最后一段提取机器字段名。
     */
    private String resolveMachineFieldName(
            FieldDictionary dictionary) {

        if (StringUtils.hasText(
                dictionary.getFieldName())) {
            return dictionary
                    .getFieldName()
                    .trim();
        }

        String path =
                dictionary.getFieldPath();

        if (!StringUtils.hasText(path)) {
            return null;
        }

        String normalized =
                path.replace("[]", "");

        int index =
                normalized.lastIndexOf('.');

        return index >= 0
                ? normalized
                .substring(index + 1)
                .trim()
                : normalized.trim();
    }

    private String trimToNull(
            String value) {

        return StringUtils.hasText(value)
                ? value.trim()
                : null;
    }
}