package org.example.ai.agent.business.person;

import org.example.ai.agent.business.person.PersonBusinessQueryService.DatasetType;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 将人员查询语义转换为用户请求的数据类型和实际执行的数据集闭包。
 */
@Component
public class PersonDatasetSelectionService {

    private static final String TRAVEL = "TRAVEL";
    private static final String ATTENDANCE = "ATTENDANCE";
    private static final String PUNCH = "PUNCH";
    private static final String REIMBURSEMENT = "REIMBURSEMENT";
    private static final List<String> SEMANTIC_ORDER =
            List.of(TRAVEL, ATTENDANCE, REIMBURSEMENT);
    private static final Set<DatasetType> ATTENDANCE_DEPENDENCIES = EnumSet.of(
            DatasetType.TRAVEL,
            DatasetType.PUNCH,
            DatasetType.LEAVE,
            DatasetType.SCHEDULE,
            DatasetType.CALENDAR
    );

    /**
     * 选择人员业务数据集。空语义表示默认查询出差、考勤和报销全览。
     */
    public Selection select(List<String> datasetCodes) {
        List<String> codes = datasetCodes == null || datasetCodes.isEmpty()
                ? SEMANTIC_ORDER
                : datasetCodes;
        Set<String> selectedSemantics = new HashSet<>();
        EnumSet<DatasetType> requestedTypes = EnumSet.noneOf(DatasetType.class);
        EnumSet<DatasetType> executionTypes = EnumSet.noneOf(DatasetType.class);

        for (String code : codes) {
            addSelection(code, selectedSemantics, requestedTypes, executionTypes);
        }

        List<String> semanticCodes = new ArrayList<>();
        for (String semantic : SEMANTIC_ORDER) {
            if (selectedSemantics.contains(semantic)) {
                semanticCodes.add(semantic);
            }
        }

        List<DatasetType> orderedExecutionTypes = new ArrayList<>();
        for (DatasetType type : DatasetType.values()) {
            if (executionTypes.contains(type)) {
                orderedExecutionTypes.add(type);
            }
        }
        return new Selection(semanticCodes, requestedTypes, orderedExecutionTypes);
    }

    private void addSelection(
            String code,
            Set<String> selectedSemantics,
            Set<DatasetType> requestedTypes,
            Set<DatasetType> executionTypes
    ) {
        if (TRAVEL.equals(code)) {
            selectedSemantics.add(TRAVEL);
            requestedTypes.add(DatasetType.TRAVEL);
            executionTypes.add(DatasetType.TRAVEL);
            return;
        }
        if (ATTENDANCE.equals(code) || PUNCH.equals(code)) {
            selectedSemantics.add(ATTENDANCE);
            requestedTypes.add(DatasetType.PUNCH);
            executionTypes.addAll(ATTENDANCE_DEPENDENCIES);
            return;
        }
        if (REIMBURSEMENT.equals(code)) {
            selectedSemantics.add(REIMBURSEMENT);
            requestedTypes.add(DatasetType.REIMBURSEMENT);
            executionTypes.add(DatasetType.REIMBURSEMENT);
            return;
        }
        throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                "暂不支持该人员业务数据类型，请查询出差、考勤或报销"
        );
    }

    /**
     * 语义选择结果。用户请求类型与依赖执行类型分开保存，避免依赖数据被误当成用户诉求。
     */
    public record Selection(
            List<String> semanticCodes,
            Set<DatasetType> requestedTypes,
            List<DatasetType> executionTypes
    ) {

        public Selection {
            semanticCodes = semanticCodes == null ? List.of() : List.copyOf(semanticCodes);
            EnumSet<DatasetType> requestedCopy = EnumSet.noneOf(DatasetType.class);
            if (requestedTypes != null) {
                requestedCopy.addAll(requestedTypes);
            }
            requestedTypes = Collections.unmodifiableSet(requestedCopy);
            executionTypes = executionTypes == null ? List.of() : List.copyOf(executionTypes);
        }

        /**
         * 判断该类型是否由用户明确请求，而不是仅作为计算依赖执行。
         */
        public boolean requested(DatasetType type) {
            return requestedTypes.contains(type);
        }
    }
}
