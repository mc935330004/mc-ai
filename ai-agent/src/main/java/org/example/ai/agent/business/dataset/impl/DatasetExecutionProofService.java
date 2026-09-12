package org.example.ai.agent.business.dataset.impl;

import org.example.ai.agent.business.dataset.DatasetExecutionProofVerifier;
import org.example.ai.agent.business.dataset.ReportDatasetValidator;
import org.example.ai.agent.business.dataset.model.DatasetExecutionResult;
import org.example.ai.agent.business.dataset.model.DatasetExecutionSource;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 使用进程内随机密钥保护 Task6 到 Task7 的执行结果交接。
 *
 * 密钥不配置、不持久化，进程重启后旧证明自然失效。
 */
@Component
final class DatasetExecutionProofService implements DatasetExecutionProofVerifier {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final HexFormat HEX = HexFormat.of();

    private final byte[] key = new byte[32];

    DatasetExecutionProofService() {
        new SecureRandom().nextBytes(key);
    }

    DatasetExecutionResult sign(DatasetExecutionResult unsigned) {
        if (unsigned == null || unsigned.integrityProof() != null) {
            throw new IllegalArgumentException("只能签名未带完整性证明的执行结果");
        }
        return new DatasetExecutionResult(
                unsigned.source(),
                unsigned.status(),
                unsigned.dataComplete(),
                unsigned.safeFacts(),
                unsigned.workflowRunId(),
                unsigned.resultArtifactId(),
                unsigned.safeErrorCode(),
                unsigned.safeMessage(),
                HEX.formatHex(mac(unsigned))
        );
    }

    @Override
    public boolean verify(DatasetExecutionResult result) {
        if (result == null
                || result.integrityProof() == null
                || result.integrityProof().length() != 64) {
            return false;
        }
        try {
            byte[] provided = HEX.parseHex(result.integrityProof());
            return MessageDigest.isEqual(mac(result), provided);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private byte[] mac(DatasetExecutionResult result) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return mac.doFinal(signingMaterial(result).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前JDK不支持HmacSHA256", exception);
        } catch (java.security.InvalidKeyException exception) {
            throw new IllegalStateException("进程内完整性密钥无效", exception);
        }
    }

    private String signingMaterial(DatasetExecutionResult result) {
        DatasetExecutionSource source = result.source();
        Map<String, Object> sourceMaterial = new LinkedHashMap<>();
        sourceMaterial.put("userId", source.userId());
        sourceMaterial.put("sessionId", source.sessionId());
        sourceMaterial.put("subjectType", source.subjectType());
        sourceMaterial.put("subjectId", source.subjectId());
        sourceMaterial.put("datasetCode", source.datasetCode());
        sourceMaterial.put("canonicalInputHash", source.canonicalInputHash());
        sourceMaterial.put("queryWorkflowCode", source.queryWorkflowCode());
        sourceMaterial.put("queryWorkflowVersionId", source.queryWorkflowVersionId());
        sourceMaterial.put("datasetConfigChecksum", source.datasetConfigChecksum());
        sourceMaterial.put("fieldPolicyChecksum", source.fieldPolicyChecksum());

        Map<String, Object> material = new LinkedHashMap<>();
        material.put("source", sourceMaterial);
        material.put("status", result.status());
        material.put("dataComplete", result.dataComplete());
        material.put("safeFacts", result.safeFacts());
        material.put("workflowRunId", result.workflowRunId());
        material.put("resultArtifactId", result.resultArtifactId());
        material.put("safeErrorCode", result.safeErrorCode());
        material.put("safeMessage", result.safeMessage());
        return ReportDatasetValidator.canonicalSafeValue(material);
    }
}
