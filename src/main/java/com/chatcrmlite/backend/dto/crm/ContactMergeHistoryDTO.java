package com.chatcrmlite.backend.dto.crm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContactMergeHistoryDTO {
    private UUID id;
    private UUID primaryContactId;
    private String primaryContactName;
    private UUID secondaryContactId;
    private String secondaryContactName;
    private UUID mergedByUserId;
    private String mergedByUserEmail;
    private String preMergeSnapshotJson;
    private String fieldsResolvedJson;
    private String transferredEntitiesJson;
    private String auditNote;
    private Instant mergedAt;
}
