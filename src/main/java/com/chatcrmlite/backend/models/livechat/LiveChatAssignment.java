package com.chatcrmlite.backend.models.livechat;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import jakarta.persistence.*;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "live_chat_assignments", indexes = {
    @Index(name = "idx_assignment_tenant_status", columnList = "tenant_id, status"),
    @Index(name = "idx_assignment_user_status", columnList = "assigned_to_id, status"),
    @Index(name = "idx_assignment_contact_status", columnList = "contact_id, status")
})
public class LiveChatAssignment implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum AssignmentStatus {
        ACTIVE,
        RESOLVED,
        CANCELLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contact_id", nullable = false)
    private Contact contact;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to_id", nullable = false)
    private User assignedTo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_by_id")
    private User assignedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AssignmentStatus status = AssignmentStatus.ACTIVE;

    @Column(name = "assigned_at", nullable = false)
    private LocalDateTime assignedAt = LocalDateTime.now();

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transfer_from_id")
    private User transferFrom;

    @Column(name = "transfer_reason", length = 500)
    private String transferReason;

    @Column(name = "capacity_override", nullable = false)
    private boolean capacityOverride = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public LiveChatAssignment() {}

    public LiveChatAssignment(Tenant tenant, Contact contact, User assignedTo, User assignedBy, AssignmentStatus status) {
        this.tenant = tenant;
        this.contact = contact;
        this.assignedTo = assignedTo;
        this.assignedBy = assignedBy != null ? assignedBy : assignedTo;
        this.status = status != null ? status : AssignmentStatus.ACTIVE;
        this.assignedAt = LocalDateTime.now();
        this.createdAt = LocalDateTime.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public Tenant getTenant() { return tenant; }
    public void setTenant(Tenant tenant) { this.tenant = tenant; }

    public Contact getContact() { return contact; }
    public void setContact(Contact contact) { this.contact = contact; }

    public User getAssignedTo() { return assignedTo; }
    public void setAssignedTo(User assignedTo) { this.assignedTo = assignedTo; }

    public User getAssignedBy() { return assignedBy; }
    public void setAssignedBy(User assignedBy) { this.assignedBy = assignedBy; }

    public AssignmentStatus getStatus() { return status; }
    public void setStatus(AssignmentStatus status) { this.status = status; }

    public LocalDateTime getAssignedAt() { return assignedAt; }
    public void setAssignedAt(LocalDateTime assignedAt) { this.assignedAt = assignedAt; }

    public LocalDateTime getReleasedAt() { return releasedAt; }
    public void setReleasedAt(LocalDateTime releasedAt) { this.releasedAt = releasedAt; }

    public User getTransferFrom() { return transferFrom; }
    public void setTransferFrom(User transferFrom) { this.transferFrom = transferFrom; }

    public String getTransferReason() { return transferReason; }
    public void setTransferReason(String transferReason) { this.transferReason = transferReason; }

    public boolean isCapacityOverride() { return capacityOverride; }
    public void setCapacityOverride(boolean capacityOverride) { this.capacityOverride = capacityOverride; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
