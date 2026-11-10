package com.pethospital.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethospital.ai.DeepSeekDtos.Completion;
import com.pethospital.ai.DeepSeekDtos.PromptMessage;
import com.pethospital.ai.DeepSeekDtos.Usage;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Component
public final class DeepSeekAdapter {
    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final DeepSeekPromptSanitizer sanitizer;
    private final String apiKey;
    private final String model;

    @Autowired
    public DeepSeekAdapter(
        ObjectMapper objectMapper,
        DeepSeekPromptSanitizer sanitizer,
        @Value("${ai.deepseek.endpoint:https://api.deepseek.com}") String endpoint,
        @Value("${ai.deepseek.api-key:}") String apiKey,
        @Value("${ai.deepseek.model:deepseek-flash}") String model,
        @Value("${ai.deepseek.connect-timeout-ms:3000}") int connectTimeoutMs,
        @Value("${ai.deepseek.read-timeout-ms:60000}") int readTimeoutMs
    ) {
        this.objectMapper = objectMapper;
        this.sanitizer = sanitizer;
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.model = model.strip();
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(connectTimeoutMs))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.client = RestClient.builder()
            .baseUrl(endpoint.strip())
            .requestFactory(factory)
            .build();
    }

    public Completion complete(List<PromptMessage> messages) {
        if (apiKey.isEmpty()) {
            throw new DeepSeekCallException(
                "DEEPSEEK_NOT_CONFIGURED",
                "DeepSeek API key is not configured",
                null,
                null
            );
        }
        if (messages == null || messages.isEmpty() || messages.size() > 100) {
            throw new IllegalArgumentException("messages must contain between 1 and 100 entries");
        }
        List<Map<String, String>> sanitizedMessages = messages.stream()
            .map(message -> Map.of(
                "role", message.role(),
                "content", sanitizer.sanitize(message.content())
            ))
            .toList();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("messages", sanitizedMessages);
        request.put("thinking", Map.of("type", "disabled"));
        request.put("response_format", Map.of("type", "json_object"));
        request.put("stream", false);
        try {
            return client.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .body(request)
                .exchange((httpRequest, response) -> decode(
                    response.getStatusCode().value(),
                    response.getHeaders().getFirst("Retry-After"),
                    response.getBody() == null
                        ? "" : StreamUtils.copyToString(response.getBody(), java.nio.charset.StandardCharsets.UTF_8)
                ));
        } catch (ResourceAccessException exception) {
            throw new DeepSeekCallException(
                "DEEPSEEK_TIMEOUT",
                "DeepSeek request timed out or could not connect",
                exception
            );
        } catch (DeepSeekCallException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new DeepSeekCallException(
                "DEEPSEEK_TRANSPORT_ERROR",
                "DeepSeek transport failed",
                exception
            );
        }
    }

    private Completion decode(int status, String retryAfter, String body) {
        if (status == 429) {
            throw new DeepSeekCallException(
                "DEEPSEEK_RATE_LIMITED",
                "DeepSeek rate limited the request",
                status,
                parseRetryAfter(retryAfter)
            );
        }
        if (status < 200 || status >= 300) {
            throw new DeepSeekCallException(
                status >= 500 ? "DEEPSEEK_UNAVAILABLE" : "DEEPSEEK_REJECTED",
                "DeepSeek returned HTTP " + status,
                status,
                null
            );
        }
        try {
            JsonNode response = objectMapper.readTree(body);
            String content = response.path("choices").path(0).path("message").path("content").asText(null);
            if (content == null || content.isBlank()) {
                throw invalidResponse("DeepSeek response has no assistant content", null);
            }
            JsonNode parsedContent = objectMapper.readTree(content);
            if (parsedContent == null || !parsedContent.isObject()) {
                throw invalidResponse("DeepSeek assistant content is not a JSON object", null);
            }
            JsonNode usageNode = response.path("usage");
            Usage usage = usageNode.isObject() ? new Usage(
                integerOrNull(usageNode.get("prompt_tokens")),
                integerOrNull(usageNode.get("completion_tokens")),
                integerOrNull(usageNode.path("prompt_tokens_details").get("cached_tokens"))
            ) : new Usage(null, null, null);
            return new Completion(
                textOrNull(response.get("id")),
                textOrDefault(response.get("model"), model),
                parsedContent,
                usage
            );
        } catch (JsonProcessingException exception) {
            throw invalidResponse("DeepSeek returned invalid JSON", exception);
        }
    }

    private static DeepSeekCallException invalidResponse(String message, Throwable cause) {
        return new DeepSeekCallException("DEEPSEEK_INVALID_RESPONSE", message, cause);
    }

    private static Integer integerOrNull(JsonNode node) {
        return node == null || node.isNull() || !node.canConvertToInt() ? null : node.intValue();
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private static String textOrDefault(JsonNode node, String fallback) {
        String value = textOrNull(node);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Long parseRetryAfter(String value) {
        try {
            return value == null ? null : Long.parseLong(value.strip());
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
