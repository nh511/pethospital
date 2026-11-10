package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethospital.ai.DeepSeekAdapter;
import com.pethospital.ai.DeepSeekCallException;
import com.pethospital.ai.DeepSeekDtos.PromptMessage;
import com.pethospital.ai.DeepSeekPromptSanitizer;
import com.pethospital.testing.TestTags;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag(TestTags.HTTP_STUB)
class M1203IT {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sendsNonThinkingJsonRequestWithDeidentifiedInputAndCapturesUsage() throws Exception {
        try (DeepSeekStub stub = DeepSeekStub.start()) {
            stub.mode.set(Mode.SUCCESS);
            var result = adapter(stub, 2_000).complete(List.of(
                new PromptMessage("system", "只输出JSON"),
                new PromptMessage(
                    "user",
                    "联系人13800138000，邮箱owner@example.com，身份证110105199001011234，宠物今天精神差"
                )
            ));

            assertThat(result.providerRequestId()).isEqualTo("request-1203");
            assertThat(result.model()).isEqualTo("deepseek-flash");
            assertThat(result.content().path("risk").asText()).isEqualTo("ROUTINE");
            assertThat(result.usage().promptTokens()).isEqualTo(21);
            assertThat(result.usage().completionTokens()).isEqualTo(7);
            assertThat(result.usage().cachedTokens()).isEqualTo(3);
            assertThat(stub.requests.get()).isOne();
            assertThat(stub.authorization.get()).isEqualTo("Bearer test-deepseek-key");
            assertThat(stub.requestBody.get())
                .doesNotContain("13800138000", "owner@example.com", "110105199001011234")
                .contains("[PHONE_REDACTED]", "[EMAIL_REDACTED]", "[ID_REDACTED]");
            JsonNode sent = objectMapper.readTree(stub.requestBody.get());
            assertThat(sent.path("model").asText()).isEqualTo("deepseek-flash");
            assertThat(sent.path("thinking").path("type").asText()).isEqualTo("disabled");
            assertThat(sent.path("response_format").path("type").asText()).isEqualTo("json_object");
            assertThat(sent.path("stream").asBoolean()).isFalse();
        }
    }

    @Test
    void rejectsMalformedAssistantJsonWithoutRetry() throws Exception {
        try (DeepSeekStub stub = DeepSeekStub.start()) {
            stub.mode.set(Mode.BAD_JSON_CONTENT);
            assertThatThrownBy(() -> adapter(stub, 2_000).complete(messages()))
                .isInstanceOfSatisfying(DeepSeekCallException.class,
                    error -> assertThat(error.code()).isEqualTo("DEEPSEEK_INVALID_RESPONSE"));
            assertThat(stub.requests.get()).isOne();
        }
    }

    @Test
    void keepsUnknownUsageFieldsNull() throws Exception {
        try (DeepSeekStub stub = DeepSeekStub.start()) {
            stub.mode.set(Mode.SUCCESS_WITHOUT_USAGE);
            var result = adapter(stub, 2_000).complete(messages());

            assertThat(result.usage().promptTokens()).isNull();
            assertThat(result.usage().completionTokens()).isNull();
            assertThat(result.usage().cachedTokens()).isNull();
            assertThat(stub.requests.get()).isOne();
        }
    }

    @Test
    void maps429AndRetryAfterWithoutAutomaticRetry() throws Exception {
        try (DeepSeekStub stub = DeepSeekStub.start()) {
            stub.mode.set(Mode.RATE_LIMITED);
            assertThatThrownBy(() -> adapter(stub, 2_000).complete(messages()))
                .isInstanceOfSatisfying(DeepSeekCallException.class, error -> {
                    assertThat(error.code()).isEqualTo("DEEPSEEK_RATE_LIMITED");
                    assertThat(error.status()).isEqualTo(429);
                    assertThat(error.retryAfterSeconds()).isEqualTo(2);
                });
            assertThat(stub.requests.get()).isOne();
        }
    }

    @Test
    void mapsServerFailureWithoutAutomaticRetryOrResponseBodyLeak() throws Exception {
        try (DeepSeekStub stub = DeepSeekStub.start()) {
            stub.mode.set(Mode.SERVER_ERROR);
            assertThatThrownBy(() -> adapter(stub, 2_000).complete(messages()))
                .isInstanceOfSatisfying(DeepSeekCallException.class, error -> {
                    assertThat(error.code()).isEqualTo("DEEPSEEK_UNAVAILABLE");
                    assertThat(error.status()).isEqualTo(503);
                    assertThat(error.getMessage()).doesNotContain("provider-secret-detail");
                });
            assertThat(stub.requests.get()).isOne();
        }
    }

    @Test
    void enforcesReadTimeoutWithoutAutomaticRetry() throws Exception {
        try (DeepSeekStub stub = DeepSeekStub.start()) {
            stub.mode.set(Mode.SLOW);
            assertThatThrownBy(() -> adapter(stub, 100).complete(messages()))
                .isInstanceOfSatisfying(DeepSeekCallException.class,
                    error -> assertThat(error.code()).isEqualTo("DEEPSEEK_TIMEOUT"));
            assertThat(stub.requests.get()).isOne();
        }
    }

    @Test
    void refusesCallsWhenApiKeyIsMissingBeforeNetworkAccess() throws Exception {
        try (DeepSeekStub stub = DeepSeekStub.start()) {
            DeepSeekAdapter adapter = new DeepSeekAdapter(
                objectMapper,
                new DeepSeekPromptSanitizer(),
                stub.baseUri().toString(),
                "",
                "deepseek-flash",
                1_000,
                1_000
            );
            assertThatThrownBy(() -> adapter.complete(messages()))
                .isInstanceOfSatisfying(DeepSeekCallException.class,
                    error -> assertThat(error.code()).isEqualTo("DEEPSEEK_NOT_CONFIGURED"));
            assertThat(stub.requests.get()).isZero();
        }
    }

    private DeepSeekAdapter adapter(DeepSeekStub stub, int readTimeoutMs) {
        return new DeepSeekAdapter(
            objectMapper,
            new DeepSeekPromptSanitizer(),
            stub.baseUri().toString(),
            "test-deepseek-key",
            "deepseek-flash",
            1_000,
            readTimeoutMs
        );
    }

    private static List<PromptMessage> messages() {
        return List.of(new PromptMessage("user", "宠物今天食欲下降"));
    }

    private enum Mode {
        SUCCESS,
        SUCCESS_WITHOUT_USAGE,
        BAD_JSON_CONTENT,
        RATE_LIMITED,
        SERVER_ERROR,
        SLOW
    }

    private static final class DeepSeekStub implements AutoCloseable {
        private final HttpServer server;
        private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.SUCCESS);
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicReference<String> requestBody = new AtomicReference<>();
        private final AtomicReference<String> authorization = new AtomicReference<>();

        private DeepSeekStub(HttpServer server) {
            this.server = server;
        }

        static DeepSeekStub start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            DeepSeekStub stub = new DeepSeekStub(server);
            server.createContext("/chat/completions", stub::handle);
            server.start();
            return stub;
        }

        URI baseUri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        private void handle(HttpExchange exchange) throws IOException {
            requests.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            switch (mode.get()) {
                case SUCCESS -> respond(exchange, 200, """
                    {"id":"request-1203","model":"deepseek-flash",
                     "choices":[{"message":{"role":"assistant","content":"{\\"risk\\":\\"ROUTINE\\"}"}}],
                     "usage":{"prompt_tokens":21,"completion_tokens":7,
                     "prompt_tokens_details":{"cached_tokens":3}}}
                    """);
                case SUCCESS_WITHOUT_USAGE -> respond(exchange, 200, """
                    {"id":"request-no-usage","model":"deepseek-flash",
                     "choices":[{"message":{"role":"assistant","content":"{\\"risk\\":\\"ROUTINE\\"}"}}]}
                    """);
                case BAD_JSON_CONTENT -> respond(exchange, 200, """
                    {"id":"request-bad","model":"deepseek-flash",
                     "choices":[{"message":{"content":"not-json"}}]}
                    """);
                case RATE_LIMITED -> {
                    exchange.getResponseHeaders().add("Retry-After", "2");
                    respond(exchange, 429, "{\"error\":{\"message\":\"rate limited\"}}");
                }
                case SERVER_ERROR -> respond(
                    exchange,
                    503,
                    "{\"error\":{\"message\":\"provider-secret-detail\"}}"
                );
                case SLOW -> {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    respond(exchange, 200, "{}" );
                }
            }
        }

        private static void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (IOException ignored) {
                // Expected when the client times out before the slow response is written.
            } finally {
                exchange.close();
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
