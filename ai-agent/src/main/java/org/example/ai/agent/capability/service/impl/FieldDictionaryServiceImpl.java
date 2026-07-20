package org.example.ai.agent.capability.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.dto.FieldDictionaryBatchConfirmDTO;
import org.example.ai.agent.capability.dto.FieldDictionaryCandidateDTO;
import org.example.ai.agent.capability.dto.FieldDictionaryGenerateDTO;
import org.example.ai.agent.capability.dto.FieldDictionarySaveDTO;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.entity.FieldDictionary;
import org.example.ai.agent.capability.mapper.CapabilityDefinitionMapper;
import org.example.ai.agent.capability.mapper.FieldDictionaryMapper;
import org.example.ai.agent.capability.service.CapabilityDefinitionService;
import org.example.ai.agent.capability.service.FieldDictionaryService;
import org.example.ai.agent.capability.vo.FieldDictionaryBatchSaveResultVO;
import org.example.ai.agent.capability.vo.FieldDictionaryGenerateResultVO;
import org.example.ai.agent.common.exception.BusinessException;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AI 字段字典 Service 实现。
 */
@Service
@RequiredArgsConstructor
public class FieldDictionaryServiceImpl extends ServiceImpl<FieldDictionaryMapper, FieldDictionary>
        implements FieldDictionaryService {
    private final ObjectMapper objectMapper;

    private final CapabilityDefinitionMapper capabilityDefinitionMapper;
    /**
     * 自动生成字段字典时忽略的技术字段。
     *
     * ponytail: 先用固定集合，够用再说；以后真需要再做成配置表。
     */
    private static final Set<String> IGNORE_FIELD_NAMES = Set.of(
            "createBy","createTime", "updateBy", "updateTime",
            "delFlag",
            "version",
            "sort","optimizeCountSql","code","pages","searchCount","msg",
            "remark"
    );
    @Override
    public Page<FieldDictionary> listByCapabilityCode(Page<FieldDictionary>page,String capabilityCode) {
        return baseMapper.listByCapabilityCode(page, capabilityCode);
    }

    @Override
    public Boolean saveField(FieldDictionarySaveDTO dto) {
        validateDisplayConfig(dto);
        boolean exists = lambdaQuery()
                .eq(FieldDictionary::getCapabilityCode, dto.getCapabilityCode())
                .eq(FieldDictionary::getFieldPath, dto.getFieldPath())
                .ne(dto.getId() != null, FieldDictionary::getId, dto.getId())
                .count() > 0;
        if (exists) {
            throw new BusinessException(400, "同一能力下字段路径已存在：" + dto.getFieldPath());
        }
        FieldDictionary entity = new FieldDictionary();
        BeanUtils.copyProperties(dto, entity);
        entity.setCreatedAt(LocalDateTime.now());
        if (entity.getSearchable() == null) {
            entity.setSearchable(0);
        }
        if (entity.getAggregatable() == null) {
            entity.setAggregatable(0);
        }
        entity.setRequiredOutput(dto.getRequiredOutput() == null? 0 : dto.getRequiredOutput());
        entity.setVisible( dto.getVisible() == null? 1: dto.getVisible());

        entity.setDisplayOrder( dto.getDisplayOrder() == null? 0 : dto.getDisplayOrder());

        entity.setDisplayGroup(dto.getDisplayGroup());

        entity.setNullDisplayText(StringUtils.hasText(dto.getNullDisplayText()) ? dto.getNullDisplayText().trim(): "当前数据中未提供");
        return saveOrUpdate(entity);
    }

    @Override
    public Boolean removeField(Long id) {
        if (getById(id) == null) {
            throw new BusinessException(404, "字段字典不存在：" + id);
        }
        return removeById(id);
    }

    @Override
    public FieldDictionary detail(Long id) {
        return this.lambdaQuery()
                .eq(FieldDictionary::getId, id)
                .one();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void generateFromJson(FieldDictionaryGenerateDTO dto) {
        List<FieldDictionary> fields = detectFromJson(dto.getCapabilityCode(), dto.getJson());
        saveGeneratedFields( dto.getCapabilityCode(),fields);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveGeneratedFields(String capabilityCode, List<FieldDictionary> fields) {
        if (!StringUtils.hasText(capabilityCode)) {
            throw new BusinessException(400, "能力编码不能为空");
        }
        if (fields == null || fields.isEmpty()) {
            return true;
        }

        // 一次查询当前能力已存在的全部路径，避免逐字段查询数据库。
        Set<String> existingPaths = lambdaQuery()
                .eq(FieldDictionary::getCapabilityCode,capabilityCode)
                .select(FieldDictionary::getFieldPath)
                .list()
                .stream()
                .map(FieldDictionary::getFieldPath)
                .collect(Collectors.toSet());

        List<FieldDictionary> needSaveList = fields.stream()
                .filter(field -> StringUtils.hasText(field.getFieldPath()))
                .filter(field -> !existingPaths.contains(field.getFieldPath()))
                // 防止同一批数据内部存在重复路径。
                .collect(Collectors.toMap( FieldDictionary::getFieldPath,field -> field,
                        (first, duplicate) -> first,
                        LinkedHashMap::new
                ))
                .values()
                .stream()
                .peek(field -> {
                    field.setId(null);
                    field.setCapabilityCode(capabilityCode);
                    if (!StringUtils.hasText( field.getFieldCnName())) {
                        field.setFieldCnName( field.getFieldName());
                    }
                    if (!StringUtils.hasText(field.getBusinessMeaning())) {
                        field.setBusinessMeaning(field.getFieldName());
                    }
                    if (field.getCreatedAt() == null) {
                        field.setCreatedAt(LocalDateTime.now());
                    }
                }).toList();

        return needSaveList.isEmpty() || saveBatch(needSaveList);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FieldDictionaryGenerateResultVO generateFromOutputSchema(String capabilityCode) {
        CapabilityDefinition capability = capabilityDefinitionMapper.selectOne(
                new LambdaQueryWrapper<CapabilityDefinition>()
                        .eq(CapabilityDefinition::getCapabilityCode, capabilityCode));
        if (capability == null) {
            throw new BusinessException(404,"能力不存在：" + capabilityCode);
        }

        if (!StringUtils.hasText(capability.getOutputSchemaJson())) {
            return buildGenerateResult(capabilityCode,0,0,
                    "SKIPPED",
                    "能力未配置 outputSchemaJson"
            );
        }

        try {
            JsonNode schema = objectMapper.readTree(capability.getOutputSchemaJson());

            List<FieldDictionary> detectedFields = new ArrayList<>();

            // 从 JSON 根节点开始递归解析。
            walkSchema(capabilityCode,"$",null,schema,detectedFields);
            if (detectedFields.isEmpty()) {
                return buildGenerateResult(capabilityCode,0,0,
                        "SKIPPED",
                        "输出Schema中没有可生成的字段");
            }
            // 统计原始字段数
            long beforeCount = lambdaQuery()
                    .eq(FieldDictionary::getCapabilityCode,capabilityCode)
                    .count();
            saveGeneratedFields(capabilityCode,detectedFields);
            // 统计生成的字段数
            long afterCount = lambdaQuery()
                    .eq(FieldDictionary::getCapabilityCode,capabilityCode).count();
            // 计算生成的字段数
            int createdCount = Math.toIntExact(afterCount - beforeCount);

            return buildGenerateResult(capabilityCode,detectedFields.size(),createdCount,
                    "SUCCESS",
                    "字段字典生成完成");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(400,"outputSchemaJson解析失败：" + e.getMessage());
        }
    }

    @Override
    public List<FieldDictionaryGenerateResultVO> batchGenerateFromOutputSchema(List<String> capabilityCodes) {
        List<FieldDictionaryGenerateResultVO> results =
                new ArrayList<>();
        for (String capabilityCode : capabilityCodes) {
            try {
                results.add(generateFromOutputSchema(capabilityCode));
            } catch (Exception e) {
                results.add(buildGenerateResult(capabilityCode,0,0,
                                "FAILED",
                                e.getMessage()));
            }
        }
        return results;
    }

    @Override
    public List<FieldDictionary> detectFromJson(String capabilityCode, String json) {
        if (!StringUtils.hasText(capabilityCode)) {
            throw new BusinessException( 400,"能力编码不能为空" );
        }
        if (!StringUtils.hasText(json)) {
            throw new BusinessException(400,"JSON不能为空");
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            List<FieldDictionary> fields = new ArrayList<>();
            walkJson(capabilityCode,"$",root,fields);

            return fields;
        } catch (Exception e) {
            throw new BusinessException(400, "JSON解析失败：" + e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FieldDictionaryBatchSaveResultVO confirmGeneratedFields(FieldDictionaryBatchConfirmDTO dto) {
        CapabilityDefinition capability =
                capabilityDefinitionMapper.selectOne(new LambdaQueryWrapper<CapabilityDefinition>()
                                .eq(CapabilityDefinition::getCapabilityCode,dto.getCapabilityCode()));
        if (capability == null) {
            throw new BusinessException(404,"能力不存在：" + dto.getCapabilityCode());
        }

        // 同一次提交内按照fieldPath去重。
        Map<String, FieldDictionaryCandidateDTO> uniqueFields =
                dto.getFields().stream()
                        .filter(item ->StringUtils.hasText(item.getFieldPath()))
                        .collect(Collectors.toMap(
                                item -> item.getFieldPath().trim(),
                                item -> item,
                                (first, duplicate) -> first,
                                LinkedHashMap::new
                        ));

        // 一次查询数据库中已经存在的路径。
        Set<String> existingPaths = lambdaQuery()
                .eq( FieldDictionary::getCapabilityCode,dto.getCapabilityCode())
                .select(FieldDictionary::getFieldPath)
                .list()
                .stream()
                .map(FieldDictionary::getFieldPath)
                .collect(Collectors.toSet());

        List<FieldDictionary> entities = new ArrayList<>();

        uniqueFields.forEach((fieldPath, item) -> {
            validateGeneratedFieldPath(fieldPath);

            if (existingPaths.contains(fieldPath)) {
                return;
            }

            FieldDictionary entity = new FieldDictionary();
            entity.setCapabilityCode(dto.getCapabilityCode());
            entity.setFieldPath(fieldPath);
            entity.setFieldName(item.getFieldName().trim());

            // 中文名暂时没有时使用英文名占位，后续可人工调整。
            entity.setFieldCnName(StringUtils.hasText(item.getFieldCnName())? item.getFieldCnName().trim()
                            : item.getFieldName().trim());

            entity.setFieldType(StringUtils.hasText(item.getFieldType()) ? item.getFieldType().trim()
                            : "string");

            entity.setBusinessMeaning(StringUtils.hasText(item.getBusinessMeaning()) ? item.getBusinessMeaning().trim()
                    : entity.getFieldCnName());
            entity.setDisplayFormat(
                    StringUtils.hasText(item.getDisplayFormat()) ? item.getDisplayFormat().trim()
                            : "text" );

            entity.setExampleValue(item.getExampleValue());

            // 沿用当前项目约定：0是，1否。
            entity.setSearchable( item.getSearchable() == null ? 1
                            : item.getSearchable());
            entity.setAggregatable( item.getAggregatable() == null ? 1 : item.getAggregatable());
            entity.setCreatedAt(LocalDateTime.now());
            entities.add(entity);
            });

        if (!entities.isEmpty()) {
            saveBatch(entities);
        }
        return FieldDictionaryBatchSaveResultVO.builder()
                .capabilityCode(dto.getCapabilityCode())
                .submittedCount(uniqueFields.size())
                .createdCount(entities.size())
                .skippedCount( uniqueFields.size() - entities.size())
                .build();
    }

    /**
     * 校验自动发现的JSON字段路径。
     */
    private void validateGeneratedFieldPath(String fieldPath) {
        if (!fieldPath.startsWith("$.")) {
            throw new BusinessException(400,  "字段路径必须以$.开头：" + fieldPath);
        }
        if (fieldPath.contains("..") || fieldPath.contains("[*]") || fieldPath.contains("?(")) {
            throw new BusinessException( 400, "字段路径包含不支持的表达式：" + fieldPath);
        }
        if (fieldPath.length() > 500) {
            throw new BusinessException( 400, "字段路径长度不能超过500" );
        }
    }


    /**
     * 递归展开 JSON 字段。
     *
     * 对象字段：$.data.projectName
     * 数组字段：$.data.records[].contractAmount
     */
    private void walkJson(String capabilityCode, String path, JsonNode node, List<FieldDictionary> fields) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String childPath = "$".equals(path)
                        ? "$." + entry.getKey()
                        : path + "." + entry.getKey();
                walkJson(capabilityCode, childPath, entry.getValue(), fields);
            });
            return;
        }
        if (node.isArray()) {
            if (!node.isEmpty()) {
                walkJson(capabilityCode, path + "[]", node.get(0), fields);
            }
            return;
        }
        String fieldName = path.substring(path.lastIndexOf('.') + 1).replace("[]", "");
            // 技术字段不生成字典，避免每个接口都出现一堆无意义字段。
        if (IGNORE_FIELD_NAMES.contains(fieldName)) {
            return;
        }
        FieldDictionary field = new FieldDictionary();
        field.setCapabilityCode(capabilityCode);
        field.setFieldPath(path);
        field.setFieldName(fieldName);
        field.setFieldType(detectType(node));
        field.setExampleValue(node.asText());
        // 中文名和业务含义先留空，交给你人工确认。
        field.setFieldCnName("");
        field.setBusinessMeaning("");
        field.setDisplayFormat(guessDisplayFormat(fieldName, node));
        fields.add(field);
    }
    /**
     * 判断 JSON 字段类型。
     */
    private String detectType(JsonNode node) {
        if (node.isNumber()) {
            return "number";
        }
        if (node.isBoolean()) {
            return "boolean";
        }
        return "string";
    }

    /**
     * 根据字段类型给一个默认展示格式。
     */
    private String defaultFormat(JsonNode node) {
        if (node.isNumber()) {
            return "number";
        }
        if (node.isBoolean()) {
            return "boolean";
        }
        return "text";
    }
    /**
     * 根据字段名和 JSON 类型推断展示格式。
     *
     * ponytail: 固定规则先够用，不做配置表。
     */
    private String guessDisplayFormat(String fieldName, JsonNode node) {
        String name = fieldName == null ? "" : fieldName.toLowerCase(Locale.ROOT);

        if (name.contains("amount")
                || name.contains("money")
                || name.contains("price")
                || name.contains("fee")
                || name.contains("cost")
                || name.contains("amt")) {
            return "amount";
        }

        if (name.contains("rate")
                || name.contains("percent")
                || name.contains("ratio")) {
            return "percent";
        }

        if (name.contains("date")
                || name.contains("time")
                || name.endsWith("at")) {
            return "date";
        }

        if (name.contains("status")
                || name.contains("state")) {
            return "status";
        }

        if (node.isNumber()) {
            return "number";
        }
        if (node.isBoolean()) {
            return "boolean";
        }
        return "text";
    }
    /**
     * 递归解析标准 JSON Schema。
     *
     * 普通字段：
     * $.data.projectName
     *
     * 数组字段：
     * $.data.records[].projectName
     */
    private void walkSchema(String capabilityCode,String path,
            String fieldName,JsonNode schema,List<FieldDictionary> fields) {
        if (schema == null || schema.isNull()) {
            return;
        }
        // 当前阶段不直接处理未展开的引用。
        if (schema.has("$ref")) {
            return;
        }
        String type = schema.path("type").asText("");
        JsonNode properties = schema.get("properties");
        if ("object".equals(type) || properties != null && properties.isObject()) {
            if (properties == null || !properties.isObject()) {
                return;
            }
            properties.fields().forEachRemaining(entry -> {
                String childPath = "$".equals(path)
                        ? "$." + entry.getKey()
                        : path + "." + entry.getKey();

                walkSchema(capabilityCode,childPath,
                        entry.getKey(),entry.getValue(),fields);
            });
            return;
        }

        if ("array".equals(type) || schema.has("items")) {
            JsonNode items = schema.get("items");

            if (items != null) {
                walkSchema(capabilityCode,path + "[]",fieldName,
                        items,fields);
            }
            return;
        }

        // 只保存最终叶子字段，不保存 data、records 等容器节点。
        if (!StringUtils.hasText(fieldName)
                || IGNORE_FIELD_NAMES.contains(fieldName)) {
            return;
        }

        FieldDictionary field = new FieldDictionary();
        field.setCapabilityCode(capabilityCode);
        field.setFieldPath(path);
        field.setFieldName(fieldName);
        field.setFieldType(StringUtils.hasText(type) ? type : "string");
        String description = schema.path("description").asText("");

        // 短描述可以直接作为候选中文名称。
        field.setFieldCnName(StringUtils.hasText(description) && description.length() <= 30
                        ? description
                        : fieldName);

        // 完整描述作为业务含义。
        field.setBusinessMeaning(StringUtils.hasText(description) ? description
                        : fieldName);

        field.setDisplayFormat(
                guessSchemaDisplayFormat(fieldName,type,
                        schema.path("format").asText("")));

        if (schema.has("example")) {
            field.setExampleValue(schema.get("example").asText());
        } else if (schema.has("default")) {
            field.setExampleValue(schema.get("default").asText());
        }
        // 按现有约定，1表示默认不开放搜索和聚合。
        field.setSearchable(1);
        field.setAggregatable(1);
        field.setCreatedAt(LocalDateTime.now());
        fields.add(field);
    }
    /**
     * 根据 Schema 类型、格式和字段名推断展示格式。
     */
    private String guessSchemaDisplayFormat(String fieldName,String type,
            String format) {
        String name = fieldName == null ? ""  : fieldName.toLowerCase(Locale.ROOT);

        String schemaFormat = format == null ? "" : format.toLowerCase(Locale.ROOT);

        if ("date".equals(schemaFormat) || "date-time".equals(schemaFormat) || name.contains("date")
                || name.contains("time") || name.endsWith("at")) {
            return "date";
        }

        if (name.contains("amount") || name.contains("money") || name.contains("price")
                || name.contains("fee") || name.contains("cost") || name.contains("amt")) {
            return "amount";
        }

        if (name.contains("rate") || name.contains("percent") || name.contains("ratio")) {
            return "percent";
        }

        if (name.contains("status") || name.contains("state")) {
            return "status";
        }

        if ("integer".equals(type) || "number".equals(type)) {
            return "number";
        }

        if ("boolean".equals(type)) {
            return "boolean";
        }
        return "text";
    }

    private FieldDictionaryGenerateResultVO buildGenerateResult(String capabilityCode,
            int detectedCount, int createdCount, String status, String message) {
        return FieldDictionaryGenerateResultVO.builder()
                .capabilityCode(capabilityCode)
                .detectedCount(detectedCount)
                .createdCount(createdCount)
                .skippedCount(Math.max(detectedCount - createdCount, 0))
                .status(status)
                .message(message)
                .build();
    }

    /**
     * 校验字段展示配置。
     */
    private void validateDisplayConfig(FieldDictionarySaveDTO dto) {
        if (dto == null) {
            return;
        }
        /*
         * 禁止出现“必答但不可展示”的矛盾配置。
         */
        if (Integer.valueOf(1).equals(dto.getRequiredOutput()) && Integer.valueOf(0).equals(dto.getVisible())) {
            throw new BusinessException( 400, "必答字段必须允许展示" );
        }
        /*
         * 展示顺序不能小于零。
         */
        if (dto.getDisplayOrder() != null && dto.getDisplayOrder() < 0) {
            throw new BusinessException( 400,"字段展示顺序不能小于0");
        }
    }
}