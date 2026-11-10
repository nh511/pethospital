package com.pethospital.identity;

import com.pethospital.identity.ConsentDtos.PolicyDocumentView;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class PolicyCatalog {
    private final Map<ConsentPurpose, PolicyDocumentView> policies;

    public PolicyCatalog(
        @Value("${identity.policies.privacy.version}") String privacyVersion,
        @Value("${identity.policies.privacy.text}") String privacyText,
        @Value("${identity.policies.ai-service.version}") String aiVersion,
        @Value("${identity.policies.ai-service.text}") String aiText
    ) {
        EnumMap<ConsentPurpose, PolicyDocumentView> configured = new EnumMap<>(ConsentPurpose.class);
        configured.put(
            ConsentPurpose.PRIVACY,
            document(ConsentPurpose.PRIVACY, privacyVersion, privacyText)
        );
        configured.put(
            ConsentPurpose.AI_SERVICE,
            document(ConsentPurpose.AI_SERVICE, aiVersion, aiText)
        );
        policies = Map.copyOf(configured);
    }

    public PolicyDocumentView current(ConsentPurpose purpose) {
        return policies.get(purpose);
    }

    public List<PolicyDocumentView> currentPolicies() {
        return List.of(current(ConsentPurpose.PRIVACY), current(ConsentPurpose.AI_SERVICE));
    }

    public boolean matchesCurrent(ConsentPurpose purpose, String version, String noticeHash) {
        PolicyDocumentView current = current(purpose);
        return current.policyVersion().equals(version) && current.noticeHash().equals(noticeHash);
    }

    private static PolicyDocumentView document(ConsentPurpose purpose, String version, String text) {
        String normalizedVersion = requireText(version, "政策版本", 32);
        String normalizedText = requireText(text, "政策正文", 100_000);
        String canonical = purpose.name() + "\n" + normalizedVersion + "\n" + normalizedText;
        return new PolicyDocumentView(
            purpose,
            normalizedVersion,
            sha256(canonical),
            normalizedText
        );
    }

    private static String requireText(String value, String label, int maxLength) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalStateException(label + "配置为空或超长");
        }
        return normalized;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM不支持SHA-256", ex);
        }
    }
}
