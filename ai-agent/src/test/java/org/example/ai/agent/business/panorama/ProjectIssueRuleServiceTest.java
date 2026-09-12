package org.example.ai.agent.business.panorama;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.business.dataset.DatasetExecutionProofVerifier;
import org.example.ai.agent.business.dataset.entity.ReportDataset;
import org.example.ai.agent.business.dataset.entity.ReportDatasetField;
import org.example.ai.agent.business.dataset.mapper.ReportDatasetFieldMapper;
import org.example.ai.agent.business.dataset.mapper.ReportDatasetMapper;
import org.example.ai.agent.business.dataset.model.DatasetExecutionResult;
import org.example.ai.agent.business.dataset.model.DatasetExecutionSource;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.model.DatasetExecutionStatus;
import org.example.ai.agent.business.panorama.entity.ProjectIssueRule;
import org.example.ai.agent.business.panorama.mapper.ProjectIssueRuleMapper;
import org.example.ai.agent.business.panorama.model.IssueMatchStatus;
import org.example.ai.agent.business.panorama.model.ProjectIssueResult;
import org.example.ai.agent.business.panorama.model.ProjectPanoramaResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectIssueRuleServiceTest {

    @Test
    void missingCashFlowFactProducesUnknownInsteadOfZeroBasedWarning() {
        ProjectIssueRuleMapper mapper = mock(ProjectIssueRuleMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(rule(
                "NEGATIVE_CASH_FLOW",
                "HIGH",
                "{\"operator\":\"LT\",\"leftFact\":\"cashFlowAmount\",\"rightValue\":0}",
                "现金流为负"
        )));

        List<ProjectIssueResult> results = service(
                mapper,
                "contractAmount"
        ).evaluate(11L, List.of(moduleResult(Map.of(
                "contractAmount",
                new BigDecimal("100")
        ))));

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo(IssueMatchStatus.UNKNOWN);
            assertThat(result.message()).doesNotContain("现金流为负");
            assertThat(result.evidenceFactCodes()).containsExactly("cashFlowAmount");
        });
    }

    @Test
    void evaluatesCrossModuleRuleUsingStableFactCodes() {
        ProjectIssueRuleMapper mapper = mock(ProjectIssueRuleMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(rule(
                "CONTRACT_OVER_BUDGET",
                "HIGH",
                "{\"operator\":\"GT\",\"leftFact\":\"contractAmount\","
                        + "\"rightFact\":\"budgetAmount\"}",
                "合同金额超过概算"
        )));
        ProjectIssueRuleService service = service(mapper, "contractAmount", "budgetAmount");

        List<ProjectIssueResult> matched = service.evaluate(11L, List.of(moduleResult(Map.of(
                "contractAmount", new BigDecimal("8600"),
                "budgetAmount", new BigDecimal("8000")
        ))));
        List<ProjectIssueResult> notMatched = service.evaluate(11L, List.of(moduleResult(Map.of(
                "contractAmount", new BigDecimal("7600"),
                "budgetAmount", new BigDecimal("8000")
        ))));

        assertThat(matched).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo(IssueMatchStatus.MATCHED);
            assertThat(result.message()).isEqualTo("合同金额超过概算");
            assertThat(result.evidenceFactCodes())
                    .containsExactly("contractAmount", "budgetAmount");
        });
        assertThat(notMatched).singleElement().satisfies(result ->
                assertThat(result.status()).isEqualTo(IssueMatchStatus.NOT_MATCHED)
        );
    }

    @Test
    void jsonPathsAreNotAcceptedAsFactReferences() {
        ProjectIssueRuleMapper mapper = mock(ProjectIssueRuleMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(rule(
                "UNSAFE_PATH",
                "LOW",
                "{\"operator\":\"GT\",\"leftFact\":\"$.contract.amount\",\"rightValue\":0}",
                "不应命中"
        )));

        List<ProjectIssueResult> results = service(
                mapper,
                "contractAmount"
        ).evaluate(11L, List.of(moduleResult(Map.of(
                "contractAmount",
                new BigDecimal("1")
        ))));

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo(IssueMatchStatus.UNKNOWN);
            assertThat(result.message()).doesNotContain("不应命中");
        });
    }

    @Test
    void invalidRuleMetadataCannotProduceFormalIssue() {
        ProjectIssueRuleMapper mapper = mock(ProjectIssueRuleMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(rule(
                "invalid-code",
                "UNKNOWN_LEVEL",
                "{\"operator\":\"GT\",\"leftFact\":\"contractAmount\",\"rightValue\":0}",
                "不应命中"
        )));

        ProjectIssueResult result = service(
                mapper,
                "contractAmount"
        ).evaluate(11L, List.of(moduleResult(Map.of(
                "contractAmount",
                BigDecimal.ONE
        )))).get(0);

        assertThat(result.status()).isEqualTo(IssueMatchStatus.UNKNOWN);
        assertThat(result.message()).doesNotContain("不应命中");
    }

    @Test
    void nonCalculableDatasetFieldCannotDriveFormalIssue() {
        ProjectIssueRuleMapper ruleMapper = mock(ProjectIssueRuleMapper.class);
        ReportDatasetMapper datasetMapper = mock(ReportDatasetMapper.class);
        ReportDatasetFieldMapper fieldMapper = mock(ReportDatasetFieldMapper.class);
        DatasetExecutionProofVerifier proofVerifier = mock(DatasetExecutionProofVerifier.class);
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule(
                "FAKE_AMOUNT",
                "HIGH",
                "{\"operator\":\"GT\",\"leftFact\":\"fakeAmount\",\"rightValue\":0}",
                "不应命中"
        )));
        ReportDataset dataset = new ReportDataset();
        dataset.setId(1L);
        dataset.setDatasetCode("CONTRACT");
        dataset.setEnabled(true);
        dataset.setConfigChecksum("2".repeat(64));
        dataset.setFieldPolicyChecksum("3".repeat(64));
        when(datasetMapper.selectEnabledByCode("CONTRACT")).thenReturn(dataset);
        ReportDatasetField field = new ReportDatasetField();
        field.setFactCode("fakeAmount");
        field.setCalculable(false);
        when(fieldMapper.selectList(any())).thenReturn(List.of(field));
        DatasetExecutionResult executionResult = executionResult(Map.of("fakeAmount", 99));
        when(proofVerifier.verify(executionResult)).thenReturn(true);

        ProjectIssueResult result = new ProjectIssueRuleService(
                ruleMapper,
                datasetMapper,
                fieldMapper,
                proofVerifier,
                new ObjectMapper()
        ).evaluate(11L, List.of(new ProjectPanoramaResult.ModuleResult(
                "CONTRACT",
                true,
                DatasetExecutionStatus.SUCCESS,
                true,
                "snapshot-1",
                executionResult
        ))).get(0);

        assertThat(result.status()).isEqualTo(IssueMatchStatus.UNKNOWN);
    }

    @Test
    void trustedCalculableFactCanDriveFormalIssue() {
        ProjectIssueRuleMapper ruleMapper = mock(ProjectIssueRuleMapper.class);
        ReportDatasetMapper datasetMapper = mock(ReportDatasetMapper.class);
        ReportDatasetFieldMapper fieldMapper = mock(ReportDatasetFieldMapper.class);
        DatasetExecutionProofVerifier proofVerifier = mock(DatasetExecutionProofVerifier.class);
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule(
                "CONTRACT_POSITIVE",
                "HIGH",
                "{\"operator\":\"GT\",\"leftFact\":\"contractAmount\",\"rightValue\":0}",
                "合同金额大于零"
        )));
        ReportDataset dataset = new ReportDataset();
        dataset.setId(1L);
        dataset.setDatasetCode("CONTRACT");
        dataset.setEnabled(true);
        dataset.setConfigChecksum("2".repeat(64));
        dataset.setFieldPolicyChecksum("3".repeat(64));
        when(datasetMapper.selectEnabledByCode("CONTRACT")).thenReturn(dataset);
        ReportDatasetField field = new ReportDatasetField();
        field.setFactCode("contractAmount");
        field.setCalculable(true);
        when(fieldMapper.selectList(any())).thenReturn(List.of(field));
        DatasetExecutionResult executionResult = executionResult(Map.of("contractAmount", 99));
        when(proofVerifier.verify(executionResult)).thenReturn(true);

        ProjectIssueResult result = new ProjectIssueRuleService(
                ruleMapper,
                datasetMapper,
                fieldMapper,
                proofVerifier,
                new ObjectMapper()
        ).evaluate(11L, List.of(new ProjectPanoramaResult.ModuleResult(
                "CONTRACT",
                true,
                DatasetExecutionStatus.SUCCESS,
                true,
                "snapshot-1",
                executionResult
        ))).get(0);

        assertThat(result.status()).isEqualTo(IssueMatchStatus.MATCHED);
        assertThat(result.message()).isEqualTo("合同金额大于零");
    }

    private DatasetExecutionResult executionResult(Map<String, Object> calculation) {
        return new DatasetExecutionResult(
                new DatasetExecutionSource(
                        "user-1", "session-1", BusinessSubjectType.PROJECT, "project-id-1",
                        "CONTRACT", "1".repeat(64), "wf-contract", 1L,
                        "2".repeat(64), "3".repeat(64)
                ),
                DatasetExecutionStatus.SUCCESS,
                true,
                Map.of(
                        "calculation", calculation,
                        "display", Map.of(),
                        "export", Map.of(),
                        "model", Map.of()
                ),
                "workflow-run-1",
                null,
                null,
                null,
                "proof"
        );
    }

    private ProjectIssueRuleService service(
            ProjectIssueRuleMapper ruleMapper,
            String... calculableFactCodes) {
        ReportDatasetMapper datasetMapper = mock(ReportDatasetMapper.class);
        ReportDatasetFieldMapper fieldMapper = mock(ReportDatasetFieldMapper.class);
        DatasetExecutionProofVerifier proofVerifier = mock(DatasetExecutionProofVerifier.class);
        ReportDataset dataset = new ReportDataset();
        dataset.setId(1L);
        dataset.setDatasetCode("CONTRACT");
        dataset.setEnabled(true);
        dataset.setConfigChecksum("2".repeat(64));
        dataset.setFieldPolicyChecksum("3".repeat(64));
        when(datasetMapper.selectEnabledByCode("CONTRACT")).thenReturn(dataset);
        List<ReportDatasetField> fields = java.util.Arrays.stream(calculableFactCodes)
                .map(code -> {
                    ReportDatasetField field = new ReportDatasetField();
                    field.setFactCode(code);
                    field.setCalculable(true);
                    return field;
                })
                .toList();
        when(fieldMapper.selectList(any())).thenReturn(fields);
        when(proofVerifier.verify(any())).thenReturn(true);
        return new ProjectIssueRuleService(
                ruleMapper,
                datasetMapper,
                fieldMapper,
                proofVerifier,
                new ObjectMapper()
        );
    }

    private ProjectPanoramaResult.ModuleResult moduleResult(
            Map<String, Object> calculation) {
        return new ProjectPanoramaResult.ModuleResult(
                "CONTRACT",
                true,
                DatasetExecutionStatus.SUCCESS,
                true,
                "snapshot-1",
                executionResult(calculation)
        );
    }

    private ProjectIssueRule rule(
            String code,
            String severity,
            String condition,
            String message) {
        ProjectIssueRule rule = new ProjectIssueRule();
        rule.setId(1L);
        rule.setProfileId(11L);
        rule.setRuleCode(code);
        rule.setSeverity(severity);
        rule.setConditionJson(condition);
        rule.setMessageTemplate(message);
        rule.setEnabled(true);
        return rule;
    }
}
