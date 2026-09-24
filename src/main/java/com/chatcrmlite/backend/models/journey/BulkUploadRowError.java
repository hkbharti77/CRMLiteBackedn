package com.chatcrmlite.backend.models.journey;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.ZonedDateTime;
import java.util.UUID;

@Entity
@Table(name = "bulk_upload_row_errors")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BulkUploadRowError {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "batch_id", nullable = false)
    private UUID batchId;

    @Column(name = "business_id", nullable = false, length = 50)
    private String businessId;

    @Column(name = "row_number", nullable = false)
    private Integer rowNumber;

    @Column(name = "raw_data", columnDefinition = "jsonb")
    private String rawData;

    @Column(name = "error_code", nullable = false, length = 100)
    private String errorCode;

    @Column(name = "error_message", nullable = false, columnDefinition = "text")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private ZonedDateTime createdAt;
}
