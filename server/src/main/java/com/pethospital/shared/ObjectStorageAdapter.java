package com.pethospital.shared;

import java.net.InetAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

@Component
@ConditionalOnProperty(prefix = "storage.s3", name = "enabled", havingValue = "true")
public class ObjectStorageAdapter implements AutoCloseable {
    public static final Duration DOWNLOAD_TTL = Duration.ofSeconds(60);

    private final S3StorageProperties properties;
    private final Clock clock;
    private final Duration downloadTtl;
    private final S3Client client;
    private final S3Presigner presigner;

    @Autowired
    public ObjectStorageAdapter(S3StorageProperties properties, Clock clock) {
        this(properties, clock, DOWNLOAD_TTL);
    }

    ObjectStorageAdapter(S3StorageProperties properties, Clock clock, Duration downloadTtl) {
        this.properties = validate(properties);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.downloadTtl = Objects.requireNonNull(downloadTtl, "downloadTtl");
        if (downloadTtl.isNegative() || downloadTtl.isZero() || downloadTtl.compareTo(DOWNLOAD_TTL) > 0) {
            throw new IllegalArgumentException("download TTL must be between 1 and 60 seconds");
        }
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(
            properties.getAccessKey(), properties.getSecretKey()
        ));
        var s3Configuration = S3Configuration.builder().pathStyleAccessEnabled(true).build();
        this.client = S3Client.builder()
            .endpointOverride(properties.getEndpoint())
            .region(Region.of(properties.getRegion()))
            .credentialsProvider(credentials)
            .serviceConfiguration(s3Configuration)
            .httpClient(UrlConnectionHttpClient.builder().build())
            .build();
        this.presigner = S3Presigner.builder()
            .endpointOverride(properties.getPublicEndpoint())
            .region(Region.of(properties.getRegion()))
            .credentialsProvider(credentials)
            .serviceConfiguration(s3Configuration)
            .build();
    }

    public void put(String objectKey, byte[] bytes, String contentType) {
        String key = validKey(objectKey);
        Objects.requireNonNull(bytes, "bytes");
        client.putObject(
            PutObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(key)
                .contentType(contentType == null ? "application/octet-stream" : contentType)
                .build(),
            RequestBody.fromBytes(bytes)
        );
    }

    public byte[] get(String objectKey) {
        ResponseBytes<?> response = client.getObjectAsBytes(GetObjectRequest.builder()
            .bucket(properties.getBucket())
            .key(validKey(objectKey))
            .build());
        return response.asByteArray();
    }

    public void delete(String objectKey) {
        client.deleteObject(DeleteObjectRequest.builder()
            .bucket(properties.getBucket())
            .key(validKey(objectKey))
            .build());
    }

    public DownloadTicket presignDownload(String objectKey) {
        var request = GetObjectPresignRequest.builder()
            .signatureDuration(downloadTtl)
            .getObjectRequest(GetObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(validKey(objectKey))
                .build())
            .build();
        URI uri = URI.create(presigner.presignGetObject(request).url().toString());
        if (!sameAuthority(uri, properties.getPublicEndpoint())) {
            throw new IllegalStateException("presigned URL escaped the configured public endpoint");
        }
        return new DownloadTicket(uri, clock.instant().plus(downloadTtl));
    }

    @Override
    public void close() {
        presigner.close();
        client.close();
    }

    private static S3StorageProperties validate(S3StorageProperties properties) {
        Objects.requireNonNull(properties, "properties");
        validateEndpoint(properties.getEndpoint(), "internal endpoint");
        validateEndpoint(properties.getPublicEndpoint(), "public endpoint");
        requireText(properties.getRegion(), "region");
        requireText(properties.getBucket(), "bucket");
        requireText(properties.getAccessKey(), "access key");
        requireText(properties.getSecretKey(), "secret key");
        String internalHost = properties.getEndpoint().getHost();
        if (sameAuthority(properties.getEndpoint(), properties.getPublicEndpoint())
            && !isLocalHost(internalHost)) {
            throw new IllegalArgumentException(
                "public endpoint must differ from non-local internal endpoint"
            );
        }
        return properties;
    }

    private static void validateEndpoint(URI endpoint, String name) {
        if (endpoint == null || endpoint.getHost() == null || endpoint.getUserInfo() != null
            || !("http".equalsIgnoreCase(endpoint.getScheme())
                || "https".equalsIgnoreCase(endpoint.getScheme()))) {
            throw new IllegalArgumentException(name + " must be an absolute HTTP(S) URI");
        }
    }

    private static boolean isLocalHost(String host) {
        try {
            return "localhost".equalsIgnoreCase(host) || InetAddress.getByName(host).isLoopbackAddress();
        } catch (Exception exception) {
            return false;
        }
    }

    private static boolean sameAuthority(URI first, URI second) {
        return first.getScheme().equalsIgnoreCase(second.getScheme())
            && first.getHost().equalsIgnoreCase(second.getHost())
            && effectivePort(first) == effectivePort(second);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String validKey(String value) {
        String key = requireText(value, "object key");
        if (key.length() > 512 || key.startsWith("/") || key.contains("\\")
            || key.contains("../") || key.contains("/..") || key.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("object key has invalid format");
        }
        return key;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.strip();
    }

    public record DownloadTicket(URI url, Instant expiresAt) {
        public DownloadTicket {
            Objects.requireNonNull(url, "url");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }
}
