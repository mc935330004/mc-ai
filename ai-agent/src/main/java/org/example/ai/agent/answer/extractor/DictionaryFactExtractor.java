package org.example.ai.agent.answer.extractor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.answer.formatter.FactValueFormatter;
import org.example.ai.agent.answer.model.AnswerFact;
import org.example.ai.agent.tool.FieldMeta;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 根据字段字典从业务接口响应中提取标准事实。
 *
 * 当前支持：
 * 1. $.data.name
 * 2. $.data.records[].name
 * 3. $.data.groups[].records[].name
 * 4. 多层对象和多层数组组合
 */
@Component
@RequiredArgsConstructor
public class DictionaryFactExtractor {

    private final ObjectMapper objectMapper;
    private final FactValueFormatter valueFormatter;

    /**
     * 从业务接口原始响应中提取事实。
     *
     * @param capabilityCode 能力编码
     * @param raw 业务接口原始响应
     * @param fields 已发布字段字典
     * @return 标准事实列表
     */
    public List<AnswerFact> extract( String capabilityCode, Object raw,List<FieldMeta> fields ) {
        if (raw == null || fields == null || fields.isEmpty()) {
            return List.of();
        }

        JsonNode root = objectMapper.valueToTree(raw);
        List<AnswerFact> facts = new ArrayList<>();

        for (FieldMeta field : fields) {
            if (!shouldDisplay(field)) {
                continue;
            }
            if (!StringUtils.hasText(field.getPath())) {
                addRequiredMissingFact( facts, capabilityCode,field,"PATH_INVALID" );
                continue;
            }

            List<ResolvedValue> values = resolve(root, field.getPath());

            if (values.isEmpty()) {
                addRequiredMissingFact( facts,capabilityCode,field,"PATH_NOT_FOUND");
                continue;
            }

            for (ResolvedValue resolved : values) {
                if (resolved.value() == null || resolved.value().isMissingNode() || resolved.value().isNull()) {
                    /*
                     * 可选字段为空时不强制生成事实，
                     * 避免最终回答出现大量无意义空字段。
                     */
                    if (!isRequired(field)) {
                        continue;
                    }

                    facts.add(buildFact( capabilityCode,field, resolved,true,
                            "VALUE_NULL"));
                    continue;
                }
                facts.add(buildFact( capabilityCode,field,  resolved, false,
                        null ));
            }
        }

        return facts.stream().sorted((left, right) -> {
                    int leftOrder = left.getDisplayOrder() == null ? 0: left.getDisplayOrder();

                    int rightOrder = right.getDisplayOrder() == null ? 0 : right.getDisplayOrder();

                    return Integer.compare(leftOrder, rightOrder);
                }) .toList();
    }

    /**
     * 解析支持 [] 的字段路径。
     */
    private List<ResolvedValue> resolve(JsonNode root,String path ) {
        if (root == null|| !StringUtils.hasText(path) || !path.startsWith("$.")) {
            return List.of();
        }
        String[] segments = path.substring(2).split("\\.");
        List<ResolvedValue> result =new ArrayList<>();
        resolveRecursive( root, segments,0, "$",null,
                result );
        return result;
    }

    /**
     * 递归解析对象和数组字段。
     */
    private void resolveRecursive(JsonNode current,String[] segments,int segmentIndex,
            String currentPath,
            String latestRecordPath,
            List<ResolvedValue> result) {
        if (segmentIndex >= segments.length) {
            result.add(new ResolvedValue( current, latestRecordPath,
                    normalizeCollectionPath( latestRecordPath )));
            return;
        }

        if (current == null|| current.isMissingNode()) {
            return;
        }

        String segment = segments[segmentIndex];
        boolean arraySegment =segment.endsWith("[]");

        String fieldName = arraySegment ? segment.substring( 0, segment.length() - 2 ) : segment;

        JsonNode child = current.path(fieldName);
        String childPath = currentPath + "." + fieldName;

        if (!arraySegment) {
            resolveRecursive( child,segments,segmentIndex + 1,
                    childPath,
                    latestRecordPath,
                    result);
            return;
        }
        if (!child.isArray() || child.isEmpty()) {
            return;
        }
        for (int index = 0;index < child.size(); index++) {
            JsonNode arrayItem = child.get(index);
            String recordPath = childPath + "[" + index + "]";

            resolveRecursive( arrayItem, segments,
                    segmentIndex + 1,recordPath, recordPath,result);
        }
    }

    /**
     * 构建正常或缺失事实。
     */
    private AnswerFact buildFact(String capabilityCode,FieldMeta field,ResolvedValue resolved,
            boolean missing,
            String missingReason) {
        String recordPath =resolved.recordPath();
        String key = safeText(capabilityCode) + ":"+ safeText(field.getPath()) + ":" + safeText(recordPath);

        Object rawValue =missing || resolved.value() == null? null: objectMapper.convertValue(
                                resolved.value(),
                                Object.class);

        return AnswerFact.builder()
                .key(key)
                .capabilityCode(capabilityCode)
                .fieldName(field.getName())
                .fieldPath(field.getPath())
                .label(displayName(field))
                .value(rawValue)
                .displayValue(valueFormatter.format(resolved.value(),field) )
                .valueType(field.getType())
                .displayFormat(field.getFormat())
                .meaning(field.getMeaning())
                .displayGroup(field.getDisplayGroup())
                .displayOrder(field.getDisplayOrder())
                .required(isRequired(field))
                .missing(missing)
                .missingReason(missingReason)
                .recordPath(recordPath)
                .collectionKey(StringUtils.hasText(resolved.collectionPath()) ? safeText(capabilityCode) + ":"+ resolved.collectionPath()
                                : null)
                .build();
    }

    /**
     * 当整个路径不存在时，为必答字段生成缺失事实。
     */
    private void addRequiredMissingFact(List<AnswerFact> facts,String capabilityCode,FieldMeta field,
            String reason) {
        if (!isRequired(field)) {
            return;
        }

        ResolvedValue resolved = new ResolvedValue(
                        null,
                        null,
                        null );

        facts.add(buildFact(capabilityCode,field,resolved,true,reason));
    }

    /**
     * visible=0 的字段禁止进入事实模型。
     */
    private boolean shouldDisplay(FieldMeta field) {
        return field != null && !Integer.valueOf(0).equals(field.getVisible());
    }

    /**
     * 判断是否为必答字段。
     */
    private boolean isRequired(FieldMeta field) {
        return field != null && Integer.valueOf(1).equals(field.getRequiredOutput());
    }

    /**
     * 获取字段展示名称。
     */
    private String displayName(FieldMeta field) {
        if (field == null) {
            return "";
        }

        if (StringUtils.hasText(field.getCnName())) {
            return field.getCnName();
        }

        if (StringUtils.hasText(field.getName())) {
            return field.getName();
        }

        return field.getPath();
    }

    /**
     * 将数组下标还原为 []，形成集合唯一标识。
     *
     * 示例：
     * $.data.groups[0].records[2]
     * 转换为：
     * $.data.groups[].records[]
     */
    private String normalizeCollectionPath(
            String recordPath) {
        if (!StringUtils.hasText(recordPath)) {
            return null;
        }

        return recordPath.replaceAll("\\[\\d+]","[]");
    }

    /**
     * 空字符串安全处理。
     */
    private String safeText(String value) {
        return value == null ? "" : value;
    }

    /**
     * 字段路径解析结果。
     */
    private record ResolvedValue(JsonNode value,String recordPath, String collectionPath ) {

    }
}