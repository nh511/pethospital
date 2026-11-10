package com.pethospital.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@Tag("real-s3")
@SpringBootTest(properties = {
    "spring.flyway.enabled=false",
    "springdoc.api-docs.enabled=false",
    "identity.jwt.allow-ephemeral=true",
    "storage.s3.enabled=true"
})
class M0402IT {
    private static final String KEY = "m0402/object-storage-adapter-probe.txt";
    private static final byte[] CONTENT = "M04-02真实S3字节校验".getBytes(StandardCharsets.UTF_8);

    @Autowired
    ObjectStorageAdapter storage;

    @Autowired
    S3StorageProperties properties;

    @Test
    void uploadAndReadPreserveExactByteHash() throws Exception {
        storage.put(KEY, CONTENT, "text/plain; charset=utf-8");

        assertThat(sha256(storage.get(KEY))).isEqualTo(sha256(CONTENT));
    }

    @Test
    void anonymousReadIsDeniedAndPublicTicketUsesConfiguredAuthorityForSixtySeconds()
        throws Exception {
        storage.put(KEY, CONTENT, "text/plain; charset=utf-8");
        URI anonymous = properties.getPublicEndpoint().resolve("/" + properties.getBucket() + "/" + KEY);
        assertThat(get(anonymous).statusCode()).isIn(401, 403);

        var ticket = storage.presignDownload(KEY);
        assertThat(ticket.url().getHost()).isEqualToIgnoringCase(properties.getPublicEndpoint().getHost());
        assertThat(ticket.url().getPort()).isEqualTo(properties.getPublicEndpoint().getPort());
        assertThat(ticket.url().getQuery()).contains("X-Amz-Expires=60");
        HttpResponse<byte[]> response = get(ticket.url());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(CONTENT);
    }

    @Test
    void expiredSignatureIsRejectedByRealStorage() throws Exception {
        storage.put(KEY, CONTENT, "text/plain; charset=utf-8");
        URI expired;
        try (var shortLived = new ObjectStorageAdapter(
            properties,
            Clock.systemUTC(),
            Duration.ofSeconds(1)
        )) {
            expired = shortLived.presignDownload(KEY).url();
        }
        Thread.sleep(2_200);

        assertThat(get(expired).statusCode()).isIn(401, 403);
    }

    @Test
    void nonLocalInternalEndpointCannotLeakAsPublicEndpoint() {
        var unsafe = new S3StorageProperties();
        unsafe.setEndpoint(URI.create("http://storage:8333"));
        unsafe.setPublicEndpoint(URI.create("http://storage:8333"));
        unsafe.setRegion("us-east-1");
        unsafe.setBucket("pet-private");
        unsafe.setAccessKey("test-access");
        unsafe.setSecretKey("test-secret");

        assertThatThrownBy(() -> new ObjectStorageAdapter(unsafe, Clock.systemUTC()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("public endpoint");
    }

    private static HttpResponse<byte[]> get(URI uri) throws Exception {
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()
            .send(
                HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()
            );
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
