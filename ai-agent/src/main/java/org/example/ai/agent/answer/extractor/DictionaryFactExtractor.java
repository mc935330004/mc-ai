package org.example.ai.agent.answer.extractor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.answer.formatter.FactValueFormatter;
import org.example.ai.agent.answer.model.AnswerFact;
import org.example.ai.agent.common.enums.FactSourceType;
import org.example.ai.agent.answer.model.UnifiedFactSet;
import org.example.ai.agent.tool.FieldMeta;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.example.ai.agent.answer.model.FactValueCandidate;
import java.util.ArrayList;
import java.util.List;

/**
 * 根据字段字典提取统一业务事实。
 *
 * 当前支持：
 * 1. $.data.name
 * 2. $.data.records[].name
 * 3. $.data.groups[].records[].name
 * 4. 多层对象和数组组合
 */
@Component
@RequiredArgsConstructor
public class DictionaryFactExtractor {

    private final ObjectMapper objectMapper;
    private final FactValueFormatter valueFormatter;

    /**
     * 从业务接口响应中提取统一事实集合。
     */
    public UnifiedFactSet extract(
            String capabilityCode,
            Object raw,
            List<FieldMeta> fields) {

        if (raw == null
                || fields == null
                || fields.isEmpty()) {

            return UnifiedFactSet.empty();
        }

        JsonNode root =
                objectMapper.valueToTree(raw);

        List<AnswerFact> facts =
                new ArrayList<>();

        for (FieldMeta field : fields) {

            if (!shouldDisplay(field)) {
                continue;
            }

            if (!StringUtils.hasText(
                    field.getPath())) {

                addRequiredMissingFact(
                        facts,
                        capabilityCode,
                        field,
                        "PATH_INVALID"
                );

                continue;
            }

            List<ResolvedValue> values =
                    resolve(
                            root,
                            field.getPath()
                    );

            if (values.isEmpty()) {

                addRequiredMissingFact(
                        facts,
                        capabilityCode,
                        field,
                        "PATH_NOT_FOUND"
                );

                continue;
            }

            for (ResolvedValue resolved : values) {

                JsonNode value =
                        resolved.value();

                if (value == null
                        || value.isMissingNode()
                        || value.isNull()) {

                    /*
                     * 可选字段为空时不生成无意义事实。
                     * 必答字段必须保留明确缺失原因。
                     */
                    if (!isRequired(field)) {
                        continue;
                    }

                    facts.add(
                            buildFact(
                                    capabilityCode,
                                    field,
                                    resolved,
                                    true,
                                    "VALUE_NULL"
                            )
                    );

                    continue;
                }

                facts.add(
                        buildFact(
                                capabilityCode,
                                field,
                                resolved,
                                false,
                                null
                        )
                );
            }
        }

        return UnifiedFactSet.from(facts);
    }

    /**
     * 解析包含[]的字段路径。
     */
    private List<ResolvedValue> resolve(
            JsonNode root,
            String path) {

        if (root == null
                || !StringUtils.hasText(path)
                || !path.startsWith("$.")) {

            return List.of();
        }

        String[] segments =
                path.substring(2)
                        .split("\\.");

        List<ResolvedValue> result =
                new ArrayList<>();

        resolveRecursive(
                root,
                segments,
                0,
                "$",
                null,
                result
        );

        return result;
    }

    /**
     * 递归解析对象和数组路径。
     */
    private void resolveRecursive(
            JsonNode current,
            String[] segments,
            int segmentIndex,
            String currentPath,
            String latestRecordPath,
            List<ResolvedValue> result) {

        if (segmentIndex >= segments.length) {

            result.add(
                    new ResolvedValue(
                            current,
                            latestRecordPath,
                            normalizeCollectionPath(
                                    latestRecordPath
                            )
                    )
            );

            return;
        }

        if (current == null
                || current.isMissingNode()) {

            return;
        }

        String segment =
                segments[segmentIndex];

        boolean arraySegment =
                segment.endsWith("[]");

        String fieldName =
                arraySegment
                        ? segment.substring(
                        0,
                        segment.length() - 2
                )
                        : segment;

        JsonNode child =
                current.path(fieldName);

        String childPath =
                currentPath
                        + "."
                        + fieldName;

        if (!arraySegment) {

            resolveRecursive(
                    child,
                    segments,
                    segmentIndex + 1,
                    childPath,
                    latestRecordPath,
                    result
            );

            return;
        }

        if (!child.isArray()
                || child.isEmpty()) {

            return;
        }

        for (int index = 0;
             index < child.size();
             index++) {

            JsonNode arrayItem =
                    child.get(index);

            String recordPath =
                    childPath
                            + "["
                            + index
                            + "]";

            resolveRecursive(
                    arrayItem,
                    segments,
                    segmentIndex + 1,
                    recordPath,
                    recordPath,
                    result
            );
        }
    }

    /**
     * 将工作流等上游已经定位好的字段值转换为统一事实。
     */
    public UnifiedFactSet extractCandidates(
            List<FactValueCandidate> candidates) {

        if (candidates == null
                || candidates.isEmpty()) {

            return UnifiedFactSet.empty();
        }

        List<AnswerFact> facts =
                candidates.stream()
                        .filter(candidate ->
                                candidate != null
                                        && candidate.field() != null
                        )
                        .map(this::buildFact)
                        .toList();

        return UnifiedFactSet.from(facts);
    }

    /**
     * 构建普通能力路径解析产生的事实。
     */
    private AnswerFact buildFact(
            String capabilityCode,
            FieldMeta field,
            ResolvedValue resolved,
            boolean missing,
            String missingReason) {

        return buildFact(
                new FactValueCandidate(
                        capabilityCode,
                        field,
                        resolved.value(),
                        resolved.recordPath(),
                        resolved.collectionPath(),
                        missing,
                        missingReason,
                        FactSourceType.RAW
                )
        );
    }

    /**
     * 将候选字段值转换为标准事实。
     */
    private AnswerFact buildFact(
            FactValueCandidate candidate) {

        FieldMeta field =
                candidate.field();

        JsonNode value =
                candidate.value();

        Object rawValue =
                candidate.missing()
                        || value == null
                        || value.isMissingNode()
                        || value.isNull()
                        ? null
                        : objectMapper.convertValue(
                        value,
                        Object.class
                );

        String factKey =
                safeText(candidate.capabilityCode())
                        + ":"
                        + safeText(field.getPath())
                        + ":"
                        + safeText(candidate.recordPath());

        return AnswerFact.builder()
                .factKey(factKey)
                .capabilityCode(
                        candidate.capabilityCode()
                )
                .fieldCode(resolveFieldCode(field))
                .fieldName(field.getName())
                .fieldPath(field.getPath())
                .label(displayName(field))
                .rawValue(rawValue)
                .formattedValue(candidate.missing()
                                ? valueFormatter.nullText(field)
                                : valueFormatter.format(
                                value,
                                field
                        )
                )
                .valueType(field.getType())
                .displayFormat(field.getFormat())
                .meaning(field.getMeaning())
                .displayGroup(field.getDisplayGroup())
                .importance(field.getImportance())
                .displayComponent(field.getDisplayComponent())
                .summary(Integer.valueOf(1).equals(field.getSummaryFlag()))
                .unit(field.getUnit())
                .precisionScale(field.getPrecisionScale())
                .displayOrder(field.getDisplayOrder())
                .requiredOutput(isRequired(field))
                .missing(candidate.missing())
                .missingReason(candidate.missingReason())
                .recordPath(candidate.recordPath())
                .collectionKey(StringUtils.hasText(candidate.collectionPath()) ? safeText(candidate.capabilityCode()) + ":"
                                  + candidate.collectionPath()
                                : null)
                .sourceType(candidate.sourceType())
                .modelVisible(!Integer.valueOf(0).equals(field.getModelVisible()))
                .userVisible(!Integer.valueOf(0).equals(field.getUserVisible()))
                .build();
    }

    /**
     * 字段语义编码为空时使用机器字段名。
     */
    private String resolveFieldCode(FieldMeta field) {
        if (field == null) {
            return "";
        }
        if (StringUtils.hasText(field.getFieldCode())) {
            return field.getFieldCode().trim();
        }
        return StringUtils.hasText(
                field.getName())
                ? field.getName().trim()
                : "";
    }

    /**
     * 必答字段路径不存在时生成缺失事实。
     */
    private void addRequiredMissingFact(
            List<AnswerFact> facts,
            String capabilityCode,
            FieldMeta field,
            String reason) {

        if (!isRequired(field)) {
            return;
        }

        facts.add(
                buildFact(
                        capabilityCode,
                        field,
                        new ResolvedValue(
                                null,
                                null,
                                null
                        ),
                        true,
                        reason
                )
        );
    }

    /**
     * 所有已发布字段都可以进入内部事实层。
     *
     * 是否发送给模型、是否展示给用户，
     * 由事实自身的权限字段控制。
     */
    private boolean shouldDisplay(FieldMeta field) {
        return field != null;
    }

    private boolean isRequired(
            FieldMeta field) {

        return field != null
                && Integer.valueOf(1)
                .equals(
                        field.getRequiredOutput()
                );
    }

    private String displayName(
            FieldMeta field) {

        if (field == null) {
            return "";
        }

        if (StringUtils.hasText(
                field.getCnName())) {

            return field.getCnName()
                    .trim();
        }

        if (StringUtils.hasText(
                field.getName())) {

            return field.getName()
                    .trim();
        }

        return field.getPath();
    }

    /**
     * 将实际数组下标转换为稳定集合路径。
     */
    private String normalizeCollectionPath(
            String recordPath) {

        if (!StringUtils.hasText(
                recordPath)) {

            return null;
        }

        return recordPath.replaceAll(
                "\\[\\d+]",
                "[]"
        );
    }

    private String safeText(
            String value) {

        return value == null
                ? ""
                : value;
    }

    /**
     * 单个字段路径解析结果。
     */
    private record ResolvedValue(
            JsonNode value,
            String recordPath,
            String collectionPath) {
    }
}