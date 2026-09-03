package org.example.ai.agent.business.subject;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.subject.model.SubjectCandidate;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class SubjectSelectionTokenServiceTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes();
    private static final Instant NOW = Instant.parse("2026-09-03T01:00:00Z");

    @Test
    void candidateJsonContainsOnlyOpaqueTokenAndNeverRawSubjectId() throws Exception {
        String rawEmployeeNo = "E100001";
        SubjectSelectionTokenService tokenService = serviceAt(NOW);
        String token = tokenService.issue(
                rawEmployeeNo,
                "user-1",
                "session-1",
                BusinessSubjectType.PERSON
        );
        SubjectCandidate candidate = new SubjectCandidate(
                BusinessSubjectType.PERSON,
                token,
                "张三",
                "E***01",
                "集团/工程部",
                null,
                null
        );

        String json = new ObjectMapper().writeValueAsString(candidate);

        assertThat(json).contains("selectionToken");
        assertThat(json).doesNotContain("subjectId", rawEmployeeNo);
        assertThat(token).doesNotContain(rawEmployeeNo);
    }

    @Test
    void tokenIsBoundToUserSessionAndSubjectType() {
        SubjectSelectionTokenService service = serviceAt(NOW);
        String token = service.issue(
                "E100001",
                "user-1",
                "session-1",
                BusinessSubjectType.PERSON
        );

        assertThat(service.resolve(
                token, "user-1", "session-1", BusinessSubjectType.PERSON
        )).contains("E100001");
        assertThat(service.resolve(
                token, "user-2", "session-1", BusinessSubjectType.PERSON
        )).isEmpty();
        assertThat(service.resolve(
                token, "user-1", "session-2", BusinessSubjectType.PERSON
        )).isEmpty();
        assertThat(service.resolve(
                token, "user-1", "session-1", BusinessSubjectType.PROJECT
        )).isEmpty();
    }

    @Test
    void rejectsTamperedAndExpiredTokens() {
        SubjectSelectionTokenService issuer = serviceAt(NOW);
        String token = issuer.issue(
                "E100001",
                "user-1",
                "session-1",
                BusinessSubjectType.PERSON
        );
        byte[] bytes = Base64.getUrlDecoder().decode(token);
        bytes[bytes.length - 1] ^= 0x01;
        String tampered = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        assertThat(issuer.resolve(
                tampered, "user-1", "session-1", BusinessSubjectType.PERSON
        )).isEmpty();

        SubjectSelectionTokenService afterExpiry = serviceAt(
                NOW.plus(Duration.ofMinutes(11))
        );
        assertThat(afterExpiry.resolve(
                token, "user-1", "session-1", BusinessSubjectType.PERSON
        )).isEmpty();
    }

    private SubjectSelectionTokenService serviceAt(Instant instant) {
        return new SubjectSelectionTokenService(
                KEY,
                Clock.fixed(instant, ZoneOffset.UTC),
                Duration.ofMinutes(10)
        );
    }
}
