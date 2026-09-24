package com.chatcrmlite.backend.models.journey;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.ZonedDateTime;
import java.util.UUID;

@Entity
@Table(name = "bulk_upload_batches")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BulkUploadBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "business_id", nullable = false, length = 50)
    private String businessId;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "file_type", nullable = false, length = 20)
    private String fileType;

    @Column(name = "file_checksum", nullable = false, length = 64)
    private String fileChecksum;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Column(name = "uploaded_by", nullable = false, length = 100)
    private String uploadedBy;

    @Column(name = "column_mapping", nullable = false, columnDefinition = "jsonb")
    private String columnMapping;

    @Builder.Default
    @Column(name = "status", length = 50)
    private String status = "QUEUED";

    @Builder.Default
    @Column(name = "total_rows")
    private Integer totalRows = 0;

    @Builder.Default
    @Column(name = "valid_rows")
    private Integer validRows = 0;

    @Builder.Default
    @Column(name = "invalid_rows")
    private Integer invalidRows = 0;

    @Builder.Default
    @Column(name = "imported_contacts")
    private Integer importedContacts = 0;

    @Builder.Default
    @Column(name = "updated_contacts")
    private Integer updatedContacts = 0;

    @Builder.Default
    @Column(name = "duplicate_rows")
    private Integer duplicateRows = 0;

    @Builder.Default
    @Column(name = "failed_rows")
    private Integer failedRows = 0;

    @Column(name = "auto_trigger_journey_id")
    private UUID autoTriggerJourneyId;

    @Column(name = "storage_file_path", columnDefinition = "text")
    private String storageFilePath;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private ZonedDateTime createdAt;

    @Column(name = "completed_at")
    private ZonedDateTime completedAt;
}
