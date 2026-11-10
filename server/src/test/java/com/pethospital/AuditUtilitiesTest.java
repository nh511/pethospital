package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.pethospital.shared.AuditSanitizer;
import com.pethospital.shared.RequestHasher;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuditUtilitiesTest {
    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private final AuditSanitizer sanitizer = new AuditSanitizer(objectMapper);
    private final RequestHasher hasher = new RequestHasher(objectMapper);

    @Test
    void requestHashCanonicalizesObjectKeyOrder() {
        var first = new LinkedHashMap<String, Object>();
        first.put("version", 2);
        first.put("payload", Map.of("name", "Mimi", "species", "CAT"));
        var second = new LinkedHashMap<String, Object>();
        second.put("payload", Map.of("species", "CAT", "name", "Mimi"));
        second.put("version", 2);

        assertThat(hasher.hash("POST", "/api/v1/pets", first))
            .isEqualTo(hasher.hash("post", "/api/v1/pets", second));
    }

    @Test
    void requestHashSeparatesPathsMethodsAndPayloads() {
        String baseline = hasher.hash("POST", "/api/v1/pets", Map.of("name", "Mimi"));

        assertThat(hasher.hash("PUT", "/api/v1/pets", Map.of("name", "Mimi")))
            .isNotEqualTo(baseline);
        assertThat(hasher.hash("POST", "/api/v1/pets/1", Map.of("name", "Mimi")))
            .isNotEqualTo(baseline);
        assertThat(hasher.hash("POST", "/api/v1/pets", Map.of("name", "Nana")))
            .isNotEqualTo(baseline);
    }

    @Test
    void sanitizerRedactsSecretsAndMasksPhoneRecursively() {
        var input = objectMapper.valueToTree(Map.of(
            "password", "NeverStoreMe",
            "profile", Map.of("phone", "13800138000", "name", "Mimi"),
            "authorization", "Bearer abcdefghijklmnopqrstuvwxyz"
        ));

        var sanitized = sanitizer.sanitize(input);

        assertThat(sanitized.at("/password").asText()).isEqualTo("[REDACTED]");
        assertThat(sanitized.at("/profile/phone").asText()).isEqualTo("138****8000");
        assertThat(sanitized.at("/profile/name").asText()).isEqualTo("Mimi");
        assertThat(sanitized.toString()).doesNotContain("NeverStoreMe", "abcdefghijklmnopqrstuvwxyz");
    }

    @Test
    void sanitizerDetectsUnsafeReplayPayloadWithoutRejectingTokenVersion() {
        assertThat(sanitizer.containsSensitiveData(
            objectMapper.valueToTree(Map.of("accessToken", "secret-value"))
        )).isTrue();
        assertThat(sanitizer.containsSensitiveData(
            objectMapper.valueToTree(Map.of("tokenVersion", 3, "status", "ACTIVE"))
        )).isFalse();
    }

    @Test
    void freeTextRedactionRemovesBearerJwtAndAssignments() {
        String value = "password=abc Bearer abcdefghijklmnop token:xyz "
            + "eyJabcdefghijk.abcdefghijklmnop.qrstuvwxyz12";

        assertThat(sanitizer.sanitizeText(value))
            .doesNotContain("password=abc", "abcdefghijklmnop", "token:xyz")
            .contains("[REDACTED]");
    }
}
