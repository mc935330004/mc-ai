package org.example.ai.agent.business.person;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.business.dataset.entity.ReportDataset;
import org.example.ai.agent.business.person.PersonBusinessQueryService.DatasetPlan;
import org.example.ai.agent.business.person.PersonBusinessQueryService.DatasetType;
import org.example.ai.agent.business.person.PersonDatasetSelectionService.Selection;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 将人员语义选择和当前可用配置转换为可执行计划，并披露无法执行的用户语义。
 */
@Component
public class PersonDatasetPlanService {

    private static final String TRAVEL = "TRAVEL";
    private static final String ATTENDANCE = "ATTENDANCE";
    private static final String REIMBURSEMENT = "REIMBURSEMENT";
    private static final Set<DatasetType> ATTENDANCE_DEPENDENCIES = EnumSet.of(
            DatasetType.TRAVEL,
            DatasetType.PUNCH,
            DatasetType.LEAVE,
            DatasetType.SCHEDULE,
            DatasetType.CALENDAR
    );

    private final ObjectMapper objectMapper;

    public PersonDatasetPlanService(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper不能为空");
    }

    public PlanResult plan(
            Selection selection,
            List<ReportDataset> configurations,
            Map<String, Object> canonicalQuery) {
        Objects.requireNonNull(selection, "selection不能为空");
        Map<String, Object> query = canonicalQuery == null ? Map.of() : canonicalQuery;
        EnumMap<DatasetType, ReportDataset> configured = configuredDatasets(
                selection.executionTypes(), configurations
        );
        EnumSet<DatasetType> executableTypes = EnumSet.noneOf(DatasetType.class);
        List<String> unavailable = new ArrayList<>();

        for (String semanticCode : selection.semanticCodes()) {
            if (TRAVEL.equals(semanticCode)) {
                addSingleSemantic(DatasetType.TRAVEL, semanticCode, configured, executableTypes, unavailable);
            } else if (ATTENDANCE.equals(semanticCode)) {
                if (configured.keySet().containsAll(ATTENDANCE_DEPENDENCIES)) {
                    executableTypes.addAll(ATTENDANCE_DEPENDENCIES);
                } else {
                    unavailable.add(semanticCode);
                }
            } else if (REIMBURSEMENT.equals(semanticCode)) {
                addSingleSemantic(
                        DatasetType.REIMBURSEMENT,
                        semanticCode,
                        configured,
                        executableTypes,
                        unavailable
                );
            }
        }

        List<DatasetPlan> plans = selection.executionTypes().stream()
                .filter(executableTypes::contains)
                .map(type -> new DatasetPlan(
                        type,
                        configured.get(type).getDatasetCode(),
                        query,
                        query.containsKey("startDate") ? "DAY" : null,
                        Set.of(factCode(type)),
                        selection.requested(type)
                ))
                .toList();
        return new PlanResult(plans, unavailable);
    }

    private void addSingleSemantic(
            DatasetType type,
            String semanticCode,
            EnumMap<DatasetType, ReportDataset> configured,
            Set<DatasetType> executableTypes,
            List<String> unavailable) {
        if (configured.containsKey(type)) {
            executableTypes.add(type);
        } else {
            unavailable.add(semanticCode);
        }
    }

    private EnumMap<DatasetType, ReportDataset> configuredDatasets(
            List<DatasetType> requiredTypes,
            List<ReportDataset> configurations) {
        Set<DatasetType> required = Set.copyOf(requiredTypes);
        EnumMap<DatasetType, ReportDataset> configured = new EnumMap<>(DatasetType.class);
        if (configurations == null) {
            return configured;
        }
        for (ReportDataset dataset : configurations) {
            DatasetType type = personType(dataset);
            if (type == null || !required.contains(type)) {
                continue;
            }
            if (configured.putIfAbsent(type, dataset) != null) {
                throw new IllegalStateException("人员业务数据集逻辑类型配置重复：" + type.name());
            }
        }
        return configured;
    }

    private DatasetType personType(ReportDataset dataset) {
        if (dataset == null || !Boolean.TRUE.equals(dataset.getEnabled())
                || !StringUtils.hasText(dataset.getDomainCode()) || !allowsPerson(dataset)) {
            return null;
        }
        String domain = dataset.getDomainCode().trim().toUpperCase(Locale.ROOT);
        for (DatasetType type : DatasetType.values()) {
            if (domain.equals(type.name()) || domain.equals("PERSON_" + type.name())) {
                return type;
            }
        }
        return null;
    }

    private boolean allowsPerson(ReportDataset dataset) {
        try {
            JsonNode types = objectMapper.readTree(dataset.getSubjectTypesJson());
            if (types == null || !types.isArray()) {
                return false;
            }
            for (JsonNode type : types) {
                if (type.isTextual()
                        && "PERSON".equals(type.textValue().trim().toUpperCase(Locale.ROOT))) {
                    return true;
                }
            }
            return false;
        } catch (Exception ignored) {
            // 无法证明允许 PERSON 的配置不参与计划。
            return false;
        }
    }

    private String factCode(DatasetType type) {
        return switch (type) {
            case TRAVEL -> PersonBusinessQueryService.TRAVEL_RECORDS;
            case PUNCH -> PersonBusinessQueryService.PUNCH_RECORDS;
            case LEAVE -> PersonBusinessQueryService.LEAVE_RECORDS;
            case SCHEDULE -> PersonBusinessQueryService.SCHEDULE_RECORDS;
            case CALENDAR -> PersonBusinessQueryService.CALENDAR_RECORDS;
            case REIMBURSEMENT -> PersonBusinessQueryService.REIMBURSEMENT_RECORDS;
        };
    }

    public record PlanResult(
            List<DatasetPlan> plans,
            List<String> unavailableSemanticCodes) {

        public PlanResult {
            plans = plans == null ? List.of() : List.copyOf(plans);
            unavailableSemanticCodes = unavailableSemanticCodes == null
                    ? List.of()
                    : List.copyOf(unavailableSemanticCodes);
        }
    }
}
