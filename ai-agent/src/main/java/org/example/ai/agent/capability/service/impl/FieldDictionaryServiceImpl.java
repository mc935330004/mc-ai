package org.example.ai.agent.capability.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.dto.FieldDictionaryGenerateDTO;
import org.example.ai.agent.capability.dto.FieldDictionarySaveDTO;
import org.example.ai.agent.capability.entity.FieldDictionary;
import org.example.ai.agent.capability.mapper.FieldDictionaryMapper;
import org.example.ai.agent.capability.service.FieldDictionaryService;
import org.example.ai.agent.common.exception.BusinessException;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * AI 字段字典 Service 实现。
 */
@Service
@RequiredArgsConstructor
public class FieldDictionaryServiceImpl extends ServiceImpl<FieldDictionaryMapper, FieldDictionary>
        implements FieldDictionaryService {
    private final ObjectMapper objectMapper;
    /**
     * 自动生成字段字典时忽略的技术字段。
     *
     * ponytail: 先用固定集合，够用再说；以后真需要再做成配置表。
     */
    private static final Set<String> IGNORE_FIELD_NAMES = Set.of(
            "id","createBy","createTime", "updateBy", "updateTime",
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
        if (!StringUtils.hasText(dto.getCapabilityCode())) {
            throw new BusinessException(400, "能力编码不能为空");
        }
        if (!StringUtils.hasText(dto.getJson())) {
            throw new BusinessException(400, "JSON 不能为空");
        }
        try {
            JsonNode root = objectMapper.readTree(dto.getJson());
            List<FieldDictionary> fields = new ArrayList<>();
            // 从根节点开始展开字段路径。
            walkJson(dto.getCapabilityCode(), "$", root, fields);
            saveGeneratedFields(dto.getCapabilityCode(), fields);
        } catch (Exception e) {
            throw new BusinessException(400, "JSON 解析失败：" + e.getMessage());
        }
    }

    @Override
    public Boolean saveGeneratedFields(String capabilityCode, List<FieldDictionary> fields) {
        if (!StringUtils.hasText(capabilityCode)) {
            throw new BusinessException(400, "能力编码不能为空");
        }
        if (fields == null || fields.isEmpty()) {
            throw new BusinessException(400, "字段列表不能为空");
        }
        List<FieldDictionary> needSaveList = new ArrayList<>();
        for (FieldDictionary field : fields) {
            boolean exists = lambdaQuery()
                    .eq(FieldDictionary::getCapabilityCode, capabilityCode)
                    .eq(FieldDictionary::getFieldPath, field.getFieldPath())
                    .count() > 0;
            if (exists) {
                continue;
            }
            field.setId(null);
            field.setCapabilityCode(capabilityCode);

            // 中文名不能为空，先用英文名占位，后续人工改。
            if (!StringUtils.hasText(field.getFieldCnName())) {
                field.setFieldCnName(field.getFieldName());
            }
            // 业务含义先用字段名占位，避免数据库非空或页面空白。
            if (!StringUtils.hasText(field.getBusinessMeaning())) {
                field.setBusinessMeaning(field.getFieldName());
            }
            if (field.getSearchable() == null) {
                field.setSearchable(1);
            }
            if (field.getAggregatable() == null) {
                field.setAggregatable(1);
            }
            needSaveList.add(field);
        }
        if (needSaveList.isEmpty()) {
            return true;
        }
        return saveBatch(needSaveList);
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
}