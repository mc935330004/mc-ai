package org.example.ai.agent.capability.invocation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.version.CapabilityVersionSnapshotFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 能力配置校验和计算器。
 *
 * 不包含 enabled、publishStatus、validatedAt 等运行状态，
 * 只计算会影响能力语义和调用行为的配置。
 */
@Component
@RequiredArgsConstructor
public class CapabilityConfigChecksumCalculator {
    private final CapabilityVersionSnapshotFactory snapshotFactory;

    public String calculate(CapabilityDefinition capability) {
        return snapshotFactory
                .create(capability)
                .configChecksum();
    }

}