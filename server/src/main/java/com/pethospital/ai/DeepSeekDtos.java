package com.pethospital.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class DeepSeekDtos {
    private DeepSeekDtos() {
    }

    public record PromptMessage(String role, String content) {
        public PromptMessage {
            role = Objects.requireNonNull(role, "role").strip().toLowerCase(Locale.ROOT);
            if (!List.of("system", "user", "assistant").contains(role)) {
                throw new IllegalArgumentException("unsupported prompt role");
            }
            content = Objects.requireNonNull(content, "content").strip();
            if (content.isEmpty() || content.length() > 100_000) {
                throw new IllegalArgumentException("prompt content length is invalid");
            }
        }
    }

    public record Usage(Integer promptTokens, Integer completionTokens, Integer cachedTokens) {
    }

    public record Completion(
        String providerRequestId,
        String model,
        JsonNode content,
        Usage usage
    ) {
        public Completion {
            content = Objects.requireNonNull(content, "content").deepCopy();
        }
    }
}
