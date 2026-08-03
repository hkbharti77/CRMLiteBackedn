package com.chatcrmlite.backend.services.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.UUID;

@Slf4j
@Service
public class S3StorageService {

    @Value("${aws.access-key-id:}")
    private String accessKeyId;

    @Value("${aws.secret-access-key:}")
    private String secretAccessKey;

    @Value("${aws.s3.bucket-name:gyanvaniai-prod-bucket}")
    private String bucketName;

    @Value("${aws.region:ap-south-1}")
    private String regionStr;

    private S3Client s3Client;

    @PostConstruct
    public void init() {
        if (accessKeyId != null && !accessKeyId.isBlank() && secretAccessKey != null && !secretAccessKey.isBlank()) {
            try {
                AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKeyId, secretAccessKey);
                this.s3Client = S3Client.builder()
                        .region(Region.of(regionStr))
                        .credentialsProvider(StaticCredentialsProvider.create(credentials))
                        .httpClient(UrlConnectionHttpClient.create())
                        .build();
                log.info("Initialized AWS S3 Client for bucket: {} in region: {}", bucketName, regionStr);
            } catch (Exception e) {
                log.error("Failed to initialize AWS S3 Client: {}", e.getMessage());
            }
        } else {
            log.warn("AWS Credentials not provided. S3StorageService running in unconfigured mode.");
        }
    }

    public boolean isConfigured() {
        return s3Client != null;
    }

    public String uploadFile(String key, MultipartFile file) throws Exception {
        if (!isConfigured()) {
            throw new IllegalStateException("AWS S3 client is not configured.");
        }

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .contentType(file.getContentType())
                .build();

        try (InputStream inputStream = file.getInputStream()) {
            s3Client.putObject(putObjectRequest, RequestBody.fromInputStream(inputStream, file.getSize()));
            log.info("Successfully uploaded object to S3: {}/{}", bucketName, key);
            return key;
        }
    }

    public byte[] downloadFile(String key) throws Exception {
        if (!isConfigured()) {
            throw new IllegalStateException("AWS S3 client is not configured.");
        }

        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();

        return s3Client.getObjectAsBytes(getObjectRequest).asByteArray();
    }

    public void deleteFile(String key) {
        if (!isConfigured()) return;
        try {
            DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();
            s3Client.deleteObject(deleteObjectRequest);
            log.info("Successfully deleted object from S3: {}/{}", bucketName, key);
        } catch (Exception e) {
            log.error("Failed to delete S3 object {}: {}", key, e.getMessage());
        }
    }

    public String getBucketName() {
        return bucketName;
    }
}
