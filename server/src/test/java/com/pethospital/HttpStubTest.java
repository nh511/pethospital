package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;

import com.pethospital.testing.FixedHttpStub;
import com.pethospital.testing.TestTags;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag(TestTags.HTTP_STUB)
class HttpStubTest {
    @Test
    void returnsDeterministic429And5xxResponsesOverHttp() throws Exception {
        try (FixedHttpStub stub = FixedHttpStub.start(); HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> rateLimited = get(client, stub, FixedHttpStub.RATE_LIMITED_PATH);
            HttpResponse<String> internalError = get(client, stub, FixedHttpStub.INTERNAL_ERROR_PATH);
            HttpResponse<String> unavailable = get(client, stub, FixedHttpStub.UNAVAILABLE_PATH);

            assertThat(rateLimited.statusCode()).isEqualTo(429);
            assertThat(rateLimited.headers().firstValue("Retry-After")).contains("2");
            assertThat(rateLimited.body()).isEqualTo("{\"code\":\"RATE_LIMITED\",\"message\":\"stub rate limit\"}");
            assertThat(internalError.statusCode()).isEqualTo(500);
            assertThat(internalError.body()).contains("STUB_INTERNAL_ERROR");
            assertThat(unavailable.statusCode()).isEqualTo(503);
            assertThat(unavailable.body()).contains("DEPENDENCY_UNAVAILABLE");
            assertThat(stub.requestCount(FixedHttpStub.RATE_LIMITED_PATH)).isEqualTo(1);
            assertThat(TestTags.HTTP_STUB).isNotEqualTo(TestTags.REAL_SERVICE);
        }
    }

    private static HttpResponse<String> get(HttpClient client, FixedHttpStub stub, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(stub.uri(path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
