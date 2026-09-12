package org.example.ai.agent.business.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalInputMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CanonicalInputMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new CanonicalInputMapper(new ReportDatasetValidator());
    }

    @Test
    void mapsProjectCodeAndPageNumberForListWorkflow() throws Exception {
        Map<String, Object> result = mapper.map(
                Map.of("projectCode", "XXXT2674040", "pageNo", 2),
                Map.of("projectCode", "project_no", "pageNo", "page_index"),
                schema("""
                        {
                          "type": "object",
                          "properties": {
                            "project_no": {"type": "string"},
                            "page_index": {"type": "integer"}
                          },
                          "required": ["project_no", "page_index"]
                        }
                        """)
        );

        assertThat(result)
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "project_no", "XXXT2674040",
                        "page_index", 2
                ));
        assertThatThrownBy(() -> result.put("extra", true))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void keepsDetailAndAccessMappingsIndependent() throws Exception {
        Map<String, Object> detail = mapper.map(
                Map.of("id", 91L),
                Map.of("id", "project_id"),
                schema("""
                        {"type":"object","properties":{"project_id":{"type":"integer"}},"required":["project_id"]}
                        """)
        );
        Map<String, Object> access = mapper.map(
                Map.of("employeeNo", "E1001"),
                Map.of("employeeNo", "target_employee_no"),
                schema("""
                        {"type":"object","properties":{"target_employee_no":{"type":"string"}},"required":["target_employee_no"]}
                        """)
        );

        assertThat(detail)
                .containsOnlyKeys("project_id")
                .containsEntry("project_id", 91L);
        assertThat(access)
                .containsOnlyKeys("target_employee_no")
                .containsEntry("target_employee_no", "E1001");
    }

    @Test
    void rejectsCanonicalInputWithoutExplicitMappingInsteadOfFallingBackByName() throws Exception {
        assertThatThrownBy(() -> mapper.map(
                Map.of("projectCode", "XXXT2674040"),
                Map.of(),
                schema("""
                        {"type":"object","properties":{"projectCode":{"type":"string"}},"required":["projectCode"]}
                        """)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("projectCode")
                .hasMessageContaining("显式映射");
    }

    @Test
    void rejectsMappingTargetAbsentFromPublishedWorkflowSchema() throws Exception {
        assertThatThrownBy(() -> mapper.map(
                Map.of("id", 91L),
                Map.of("id", "unknown_id"),
                schema("""
                        {"type":"object","properties":{"project_id":{"type":"integer"}}}
                        """)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown_id")
                .hasMessageContaining("properties");
    }

    @Test
    void rejectsMissingValueForRequiredTargetEvenWhenMapsAreNull() throws Exception {
        assertThatThrownBy(() -> mapper.map(
                null,
                null,
                schema("""
                        {"type":"object","properties":{"project_id":{"type":"integer"}},"required":["project_id"]}
                        """)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("project_id")
                .hasMessageContaining("required");
    }

    @Test
    void rejectsTwoCanonicalInputsMappedToSameWorkflowParameter() throws Exception {
        assertThatThrownBy(() -> mapper.map(
                Map.of("projectCode", "XXXT2674040", "id", 91L),
                Map.of("projectCode", "project_id", "id", "project_id"),
                schema("""
                        {"type":"object","properties":{"project_id":{"type":"string"}}}
                        """)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("project_id")
                .hasMessageContaining("重复");
    }

    @Test
    void rejectsReservedExecutionOrSecurityFieldsCaseInsensitively() throws Exception {
        JsonNode reservedSchema = schema("""
                {
                  "type":"object",
                  "properties":{
                    "Authorization":{"type":"string"},
                    "safe_id":{"type":"integer"}
                  }
                }
                """);

        assertThatThrownBy(() -> mapper.map(
                Map.of("workflowCode", "ATTACKER_WORKFLOW"),
                Map.of("workflowCode", "safe_id"),
                reservedSchema
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workflowCode")
                .hasMessageContaining("保留");

        assertThatThrownBy(() -> mapper.map(
                Map.of("id", 91L),
                Map.of("id", "Authorization"),
                reservedSchema
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Authorization")
                .hasMessageContaining("保留");

        assertThatThrownBy(() -> mapper.map(
                Map.of("UsErCoNtExT", Map.of("role", "admin")),
                Map.of("UsErCoNtExT", "safe_id"),
                reservedSchema
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UsErCoNtExT")
                .hasMessageContaining("保留");

        assertThatThrownBy(() -> mapper.map(
                Map.of("id", 91L),
                Map.of("id", "uSeRcOnTeXt"),
                schema("""
                        {"type":"object","properties":{"uSeRcOnTeXt":{"type":"object"}}}
                        """)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uSeRcOnTeXt")
                .hasMessageContaining("保留");
    }

    @Test
    void snapshotsInputMapStructureBeforeProducingResult() throws Exception {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("id", 91L);
        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put("id", "project_id");

        Map<String, Object> result = mapper.map(
                canonical,
                mapping,
                schema("""
                        {"type":"object","properties":{"project_id":{"type":"integer"}},"required":["project_id"]}
                        """)
        );
        canonical.put("id", 100L);
        mapping.put("id", "other_id");

        assertThat(result)
                .containsOnlyKeys("project_id")
                .containsEntry("project_id", 91L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void recursivelyFreezesMappedValuesAndAllowsRepeatedSharedChildren() throws Exception {
        List<Object> shared = new ArrayList<>(List.of("first"));
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("primary", shared);
        nested.put("secondary", shared);

        Map<String, Object> result = mapper.map(
                Map.of("payload", nested),
                Map.of("payload", "request_body"),
                schema("""
                        {"type":"object","properties":{"request_body":{"type":"object"}}}
                        """)
        );
        Map<String, Object> frozen = (Map<String, Object>) result.get("request_body");
        shared.add("late");
        nested.put("late", true);

        assertThat(frozen).containsOnlyKeys("primary", "secondary");
        assertThat((List<Object>) frozen.get("primary")).containsExactly("first");
        assertThat((List<Object>) frozen.get("secondary")).containsExactly("first");
        assertThatThrownBy(() -> frozen.put("extra", true))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((List<Object>) frozen.get("primary")).add("extra"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsCyclicMappedValueWithControlledBusinessException() throws Exception {
        Map<String, Object> cyclic = new LinkedHashMap<>();
        cyclic.put("self", cyclic);

        assertThatThrownBy(() -> mapper.map(
                Map.of("payload", cyclic),
                Map.of("payload", "request_body"),
                schema("""
                        {"type":"object","properties":{"request_body":{"type":"object"}}}
                        """)
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("循环");
    }

    @Test
    void rejectsUnsupportedMappedValueInsteadOfRetainingMutableObject() throws Exception {
        assertThatThrownBy(() -> mapper.map(
                Map.of("payload", new StringBuilder("mutable")),
                Map.of("payload", "request_body"),
                schema("""
                        {"type":"object","properties":{"request_body":{"type":"object"}}}
                        """)
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("StringBuilder")
                .hasMessageContaining("不支持");
    }

    @Test
    void rejectsMappedValueBeyondMaximumSafeDepth() throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> cursor = root;
        for (int index = 0; index < 66; index++) {
            Map<String, Object> child = new LinkedHashMap<>();
            cursor.put("child", child);
            cursor = child;
        }

        assertThatThrownBy(() -> mapper.map(
                Map.of("payload", root),
                Map.of("payload", "request_body"),
                schema("""
                        {"type":"object","properties":{"request_body":{"type":"object"}}}
                        """)
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("深度");
    }

    private JsonNode schema(String json) throws Exception {
        return objectMapper.readTree(json);
    }
}
