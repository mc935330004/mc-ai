package org.example.ai.agent.business.dataset;

import org.example.ai.agent.business.dataset.dto.ReportDatasetSaveDTO;
import org.example.ai.agent.business.dataset.vo.ReportDatasetListVO;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReportDatasetContractTest {

    @Test
    void saveRequestShouldUseStructuredMappingsAndFieldPolicies() {
        ReportDatasetSaveDTO dto = new ReportDatasetSaveDTO();
        dto.setSubjectTypes(List.of("PERSON"));
        dto.setQueryInputMapping(Map.of("employeeNo", "employee_no"));
        dto.setAccessInputMapping(Map.of());
        dto.setFields(List.of(new ReportDatasetSaveDTO.FieldDTO()));

        assertThat(dto.getSubjectTypes()).containsExactly("PERSON");
        assertThat(dto.getQueryInputMapping())
                .containsEntry("employeeNo", "employee_no");
        assertThat(dto.getAccessInputMapping()).isEmpty();
        assertThat(dto.getFields()).hasSize(1);
        assertThat(fieldNames(ReportDatasetSaveDTO.class))
                .doesNotContain("subjectTypesJson", "inputMappingJson");
    }

    @Test
    void listViewShouldNotExposeRawConfigurationJson() {
        assertThat(fieldNames(ReportDatasetListVO.class))
                .doesNotContain(
                        "subjectTypesJson",
                        "inputMappingJson",
                        "configChecksum"
                );
    }

    private List<String> fieldNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .map(Field::getName)
                .toList();
    }
}
