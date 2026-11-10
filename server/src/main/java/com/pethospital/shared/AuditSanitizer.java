package com.pethospital.shared;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public final class AuditSanitizer {
    private static final TextNode REDACTED = TextNode.valueOf("[REDACTED]");
    private static final Set<String> SENSITIVE_KEYS = Set.of(
        "password", "passwordhash", "temporarypassword", "currentpassword", "newpassword",
        "token", "accesstoken", "refreshtoken", "idtoken", "authorization",
        "secret", "appsecret", "clientsecret", "credential", "credentials",
        "cookie", "setcookie", "privatekey"
    );
    private static final Set<String> PHONE_KEYS = Set.of("phone", "mobile", "phonenumber");
    private static final Pattern BEARER = Pattern.compile(
        "(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{8,}"
    );
    private static final Pattern JWT = Pattern.compile(
        "\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}(?:\\.[A-Za-z0-9_-]{8,})?\\b"
    );
    private static final Pattern ASSIGNMENT = Pattern.compile(
        "(?i)\\b(password|token|secret|authorization)\\s*[:=]\\s*[^\\s,;]+"
    );
    private static final Pattern PRIVATE_KEY = Pattern.compile(
        "(?is)-----BEGIN(?: RSA| EC| OPENSSH)? PRIVATE KEY-----.*?-----END(?: RSA| EC| OPENSSH)? PRIVATE KEY-----"
    );

    private final ObjectMapper objectMapper;

    public AuditSanitizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode sanitize(Object value) {
        if (value == null) {
            return null;
        }
        JsonNode node = value instanceof JsonNode jsonNode
            ? jsonNode
            : objectMapper.valueToTree(value);
        return sanitizeNode(node);
    }

    public String sanitizeText(String value) {
        if (value == null) {
            return null;
        }
        String sanitized = PRIVATE_KEY.matcher(value).replaceAll("[REDACTED_PRIVATE_KEY]");
        sanitized = BEARER.matcher(sanitized).replaceAll("Bearer [REDACTED]");
        sanitized = JWT.matcher(sanitized).replaceAll("[REDACTED_JWT]");
        return ASSIGNMENT.matcher(sanitized).replaceAll("$1=[REDACTED]");
    }

    public boolean containsSensitiveData(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isObject()) {
            for (var field : node.properties()) {
                if (isSensitiveKey(field.getKey()) || containsSensitiveData(field.getValue())) {
                    return true;
                }
            }
            return false;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (containsSensitiveData(item)) {
                    return true;
                }
            }
            return false;
        }
        if (node.isTextual()) {
            String value = node.textValue();
            return BEARER.matcher(value).find()
                || JWT.matcher(value).find()
                || PRIVATE_KEY.matcher(value).find()
                || ASSIGNMENT.matcher(value).find();
        }
        return false;
    }

    private JsonNode sanitizeNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            node.properties().forEach(field -> {
                String normalizedKey = normalizeKey(field.getKey());
                if (SENSITIVE_KEYS.contains(normalizedKey)) {
                    result.set(field.getKey(), REDACTED);
                } else if (PHONE_KEYS.contains(normalizedKey) && field.getValue().isTextual()) {
                    result.put(field.getKey(), maskPhone(field.getValue().textValue()));
                } else {
                    result.set(field.getKey(), sanitizeNode(field.getValue()));
                }
            });
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            node.forEach(item -> result.add(sanitizeNode(item)));
            return result;
        }
        if (node.isTextual()) {
            return TextNode.valueOf(sanitizeText(node.textValue()));
        }
        return node.deepCopy();
    }

    private static boolean isSensitiveKey(String key) {
        return SENSITIVE_KEYS.contains(normalizeKey(key));
    }

    private static String normalizeKey(String key) {
        return key.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
    }

    private static String maskPhone(String value) {
        String trimmed = value.strip();
        if (trimmed.length() < 7) {
            return "[MASKED]";
        }
        return trimmed.substring(0, 3) + "****" + trimmed.substring(trimmed.length() - 4);
    }
}
