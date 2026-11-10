package com.pethospital.shared;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "storage.s3")
public class S3StorageProperties {
    private URI endpoint = URI.create("http://127.0.0.1:8333");
    private URI publicEndpoint = URI.create("http://127.0.0.1:8333");
    private String region = "us-east-1";
    private String bucket = "pet-private";
    private String accessKey = "";
    private String secretKey = "";

    public URI getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(URI endpoint) {
        this.endpoint = endpoint;
    }

    public URI getPublicEndpoint() {
        return publicEndpoint;
    }

    public void setPublicEndpoint(URI publicEndpoint) {
        this.publicEndpoint = publicEndpoint;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }
}
