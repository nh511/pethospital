package com.pethospital.shared;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

@Component
public final class RequestHasher {
    private final ObjectMapper objectMapper;

    public RequestHasher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String hash(String method, String path, Object body) {
        String normalizedMethod = method == null ? "" : method.strip().toUpperCase(Locale.ROOT);
        String normalizedPath = path == null ? "" : path.strip();
        if (!normalizedMethod.matches("POST|PUT|PATCH|DELETE") || !normalizedPath.startsWith("/")) {
            throw new IllegalArgumentException("invalid write method or path");
        }
        JsonNode raw = body == null ? objectMapper.nullNode() : objectMapper.valueToTree(body);
        byte[] canonicalBody;
        try {
            canonicalBody = objectMapper.writeValueAsBytes(canonicalize(raw));
        } catch (Exception exception) {
            throw new IllegalArgumentException("request body cannot be canonicalized", exception);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, normalizedMethod.getBytes(StandardCharsets.UTF_8));
            update(digest, normalizedPath.getBytes(StandardCharsets.UTF_8));
            update(digest, canonicalBody);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            var sorted = new TreeMap<String, JsonNode>();
            node.properties().forEach(field -> sorted.put(field.getKey(), field.getValue()));
            sorted.forEach((key, value) -> result.set(key, canonicalize(value)));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            node.forEach(value -> result.add(canonicalize(value)));
            return result;
        }
        return node.deepCopy();
    }

    private static void update(MessageDigest digest, byte[] value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
        digest.update(value);
    }
}
