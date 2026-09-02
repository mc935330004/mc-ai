package org.example.ai.agent.business.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.business.dataset.BusinessFactSanitizer.MissingValue;
import org.example.ai.agent.business.dataset.BusinessFactSanitizer.SanitizedFacts;
import org.example.ai.agent.business.dataset.model.FieldPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessFactSanitizerTest {

    private BusinessFactSanitizer sanitizer;

    @BeforeEach
    void setUp() {
        sanitizer = new BusinessFactSanitizer(
                new ReportDatasetValidator(),
                new ObjectMapper().findAndRegisterModules()
        );
    }

    @Test
    void filtersUnknownFactsAndKeepsFourChannelsIndependent() {
        BigDecimal amount = new BigDecimal("1250.50");
        Map<String, Object> rawFacts = new LinkedHashMap<>();
        rawFacts.put("employee.name", "王小明");
        rawFacts.put("employee.bankAccount", "6222021234567890");
        rawFacts.put("expense.approvedAmount", amount);
        rawFacts.put("raw.secret", "must-not-escape");

        SanitizedFacts result = sanitizer.sanitize(rawFacts, List.of(
                policy("employee.name", true, true, false, true, "PARTIAL"),
                policy("employee.bankAccount", false, true, true, false, "PARTIAL"),
                policy("expense.approvedAmount", true, false, true, false, "NONE")
        ));

        assertThat(result.calculationFacts())
                .containsOnlyKeys("employee.name", "expense.approvedAmount")
                .containsEntry("employee.name", "王小明")
                .containsEntry("expense.approvedAmount", amount);
        assertThat(result.displayFacts())
                .containsOnlyKeys("employee.name", "employee.bankAccount")
                .containsEntry("employee.name", "王**")
                .containsEntry("employee.bankAccount", "************7890");
        assertThat(result.exportFacts())
                .containsOnlyKeys("employee.bankAccount", "expense.approvedAmount")
                .containsEntry("employee.bankAccount", "************7890")
                .containsEntry("expense.approvedAmount", amount);
        assertThat(result.modelFacts())
                .containsOnlyKeys("employee.name")
                .containsEntry("employee.name", "王**");
        assertThat(List.of(
                result.calculationFacts(),
                result.displayFacts(),
                result.exportFacts(),
                result.modelFacts()
        )).allSatisfy(channel -> assertThat(channel).doesNotContainKey("raw.secret"));
    }

    @Test
    void appliesPartialHashAndSummaryOnlyDeterministically() {
        Map<String, Object> firstObject = new LinkedHashMap<>();
        firstObject.put("b", 2);
        firstObject.put("a", 1);
        Map<String, Object> reorderedObject = new LinkedHashMap<>();
        reorderedObject.put("a", 1);
        reorderedObject.put("b", 2);

        SanitizedFacts first = sanitizer.sanitize(Map.of(
                "employee.name", "王小明",
                "employee.bankAccount", "6222021234567890",
                "employee.attributes", firstObject,
                "payroll.total", new BigDecimal("5000")
        ), List.of(
                policy("employee.name", false, true, false, false, "PARTIAL"),
                policy("employee.bankAccount", false, true, false, false, "PARTIAL"),
                policy("employee.attributes", false, true, false, true, "HASH"),
                policy("payroll.total", true, true, true, true, "SUMMARY_ONLY")
        ));
        SanitizedFacts second = sanitizer.sanitize(Map.of(
                "employee.attributes", reorderedObject
        ), List.of(policy("employee.attributes", false, true, false, true, "HASH")));

        assertThat(first.displayFacts())
                .containsEntry("employee.name", "王**")
                .containsEntry("employee.bankAccount", "************7890")
                .containsEntry("payroll.total", "仅用于汇总");
        String firstHash = (String) first.displayFacts().get("employee.attributes");
        assertThat(firstHash)
                .hasSize(64)
                .matches("[0-9a-f]{64}")
                .isEqualTo(second.displayFacts().get("employee.attributes"));
        assertThat(first.modelFacts().get("employee.attributes")).isEqualTo(firstHash);
        assertThat(first.calculationFacts()).containsEntry("payroll.total", new BigDecimal("5000"));
        assertThat(first.exportFacts()).containsEntry("payroll.total", "仅用于汇总");
        assertThat(first.modelFacts()).containsEntry("payroll.total", "仅用于汇总");
    }

    @Test
    void preservesMissingMarkerForAbsentOrNullAllowedFactsAndNeverUsesZero() {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("expense.approvedAmount", null);

        SanitizedFacts result = sanitizer.sanitize(facts, List.of(
                policy("expense.approvedAmount", true, true, true, true, "NONE"),
                policy("attendance.days", true, false, true, false, "NONE")
        ));

        assertThat(result.calculationFacts())
                .containsEntry("expense.approvedAmount", MissingValue.INSTANCE)
                .containsEntry("attendance.days", MissingValue.INSTANCE)
                .doesNotContainValue(0);
        assertThat(result.displayFacts())
                .containsOnlyKeys("expense.approvedAmount")
                .containsEntry("expense.approvedAmount", MissingValue.INSTANCE);
        assertThat(result.exportFacts())
                .containsEntry("expense.approvedAmount", MissingValue.INSTANCE)
                .containsEntry("attendance.days", MissingValue.INSTANCE);
        assertThat(result.modelFacts())
                .containsOnlyKeys("expense.approvedAmount")
                .containsEntry("expense.approvedAmount", MissingValue.INSTANCE);
    }

    @Test
    void returnsImmutableMapsAndSnapshotsInputMapStructure() {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("expense.approvedAmount", new BigDecimal("10"));

        SanitizedFacts result = sanitizer.sanitize(facts, List.of(
                policy("expense.approvedAmount", true, true, true, true, "NONE")
        ));
        facts.put("expense.approvedAmount", new BigDecimal("99"));
        facts.put("raw.secret", "late value");

        assertThat(result.calculationFacts())
                .containsOnlyKeys("expense.approvedAmount")
                .containsEntry("expense.approvedAmount", new BigDecimal("10"));
        assertThatThrownBy(() -> result.displayFacts().put("extra", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void recursivelyFreezesNestedJsonStyleFactsWithoutChangingScalarTypes() {
        List<Object> details = new ArrayList<>();
        details.add(new BigDecimal("12.50"));
        Set<String> labels = new LinkedHashSet<>(Set.of("approved"));
        Object[] attachments = {new ArrayList<>(List.of("receipt.pdf"))};
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("details", details);
        nested.put("labels", labels);
        nested.put("attachments", attachments);
        nested.put("missing", null);

        SanitizedFacts result = sanitizer.sanitize(
                Map.of("expense.record", nested),
                List.of(policy("expense.record", true, true, true, true, "NONE"))
        );
        Map<String, Object> frozen = (Map<String, Object>) result
                .calculationFacts()
                .get("expense.record");

        details.add("late-detail");
        labels.add("late-label");
        ((List<String>) attachments[0]).add("late-file.pdf");
        attachments[0] = "replaced";
        nested.put("late", "value");

        assertThat(frozen)
                .containsOnlyKeys("details", "labels", "attachments", "missing")
                .containsEntry("missing", null);
        assertThat((List<Object>) frozen.get("details"))
                .containsExactly(new BigDecimal("12.50"));
        assertThat(((List<Object>) frozen.get("details")).get(0))
                .isInstanceOf(BigDecimal.class);
        assertThat((Set<String>) frozen.get("labels")).containsExactly("approved");
        List<Object> frozenAttachments = (List<Object>) frozen.get("attachments");
        assertThat((List<String>) frozenAttachments.get(0)).containsExactly("receipt.pdf");

        assertThat(result.displayFacts().get("expense.record")).isSameAs(frozen);
        assertThat(result.exportFacts().get("expense.record")).isSameAs(frozen);
        assertThat(result.modelFacts().get("expense.record")).isSameAs(frozen);
        assertThatThrownBy(() -> frozen.put("extra", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((List<Object>) frozen.get("details")).add("value"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((Set<String>) frozen.get("labels")).add("value"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> frozenAttachments.add("value"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((List<String>) frozenAttachments.get(0)).add("value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsInvalidOrDuplicateFieldPolicies() {
        assertThatThrownBy(() -> sanitizer.sanitize(Map.of(), List.of(
                policy("employee.name", false, true, false, true, "NONE"),
                policy("employee.name", false, true, false, true, "NONE")
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("factCode")
                .hasMessageContaining("重复");

        assertThatThrownBy(() -> sanitizer.sanitize(Map.of(), List.of(
                policy("employee.name", false, true, false, true, "REDACT")
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maskStrategy")
                .hasMessageContaining("REDACT");
    }

    private FieldPolicy policy(
            String factCode,
            boolean calculable,
            boolean displayable,
            boolean exportable,
            boolean modelVisible,
            String maskStrategy) {
        return new FieldPolicy(
                factCode,
                "STRING",
                calculable,
                displayable,
                exportable,
                modelVisible,
                maskStrategy,
                "PERSON"
        );
    }
}
