package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "ticket_comments")
public class TicketComment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id", nullable = false)
    private Ticket ticket;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id")
    private User author;

    private String authorName;

    @Enumerated(EnumType.STRING)
    private AuthorType authorType = AuthorType.AGENT;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String message;

    @Column(nullable = false)
    private boolean internal = false;

    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;

    @Column(nullable = false, columnDefinition = "BOOLEAN DEFAULT FALSE")
    private boolean deleted = false;

    private LocalDateTime deletedAt;

    private UUID deletedBy;

    public TicketComment() {}

    public TicketComment(UUID id, Ticket ticket, User author, String authorName, AuthorType authorType, String message, boolean internal, LocalDateTime createdAt, LocalDateTime updatedAt, boolean deleted, LocalDateTime deletedAt, UUID deletedBy) {
        this.id = id;
        this.ticket = ticket;
        this.author = author;
        this.authorName = authorName;
        this.authorType = (authorType != null) ? authorType : AuthorType.AGENT;
        this.message = message;
        this.internal = internal;
        this.createdAt = (createdAt != null) ? createdAt : LocalDateTime.now();
        this.updatedAt = updatedAt;
        this.deleted = deleted;
        this.deletedAt = deletedAt;
        this.deletedBy = deletedBy;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Ticket getTicket() { return ticket; }
    public void setTicket(Ticket ticket) { this.ticket = ticket; }
    public User getAuthor() { return author; }
    public void setAuthor(User author) { this.author = author; }
    public String getAuthorName() { return authorName; }
    public void setAuthorName(String authorName) { this.authorName = authorName; }
    public AuthorType getAuthorType() { return authorType; }
    public void setAuthorType(AuthorType authorType) { this.authorType = authorType; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public boolean isInternal() { return internal; }
    public void setInternal(boolean internal) { this.internal = internal; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
    public UUID getDeletedBy() { return deletedBy; }
    public void setDeletedBy(UUID deletedBy) { this.deletedBy = deletedBy; }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum AuthorType {
        AGENT, CUSTOMER, SYSTEM
    }

    public static TicketCommentBuilder builder() { return new TicketCommentBuilder(); }

    public static class TicketCommentBuilder {
        private UUID id;
        private Ticket ticket;
        private User author;
        private String authorName;
        private AuthorType authorType = AuthorType.AGENT;
        private String message;
        private boolean internal = false;
        private LocalDateTime createdAt = LocalDateTime.now();
        private LocalDateTime updatedAt;
        private boolean deleted = false;
        private LocalDateTime deletedAt;
        private UUID deletedBy;

        public TicketCommentBuilder id(UUID id) { this.id = id; return this; }
        public TicketCommentBuilder ticket(Ticket ticket) { this.ticket = ticket; return this; }
        public TicketCommentBuilder author(User author) { this.author = author; return this; }
        public TicketCommentBuilder authorName(String authorName) { this.authorName = authorName; return this; }
        public TicketCommentBuilder authorType(AuthorType authorType) { this.authorType = authorType; return this; }
        public TicketCommentBuilder message(String message) { this.message = message; return this; }
        public TicketCommentBuilder internal(boolean internal) { this.internal = internal; return this; }
        public TicketCommentBuilder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public TicketCommentBuilder updatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; return this; }
        public TicketCommentBuilder deleted(boolean deleted) { this.deleted = deleted; return this; }
        public TicketCommentBuilder deletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; return this; }
        public TicketCommentBuilder deletedBy(UUID deletedBy) { this.deletedBy = deletedBy; return this; }

        public TicketComment build() {
            return new TicketComment(id, ticket, author, authorName, authorType, message, internal, createdAt, updatedAt, deleted, deletedAt, deletedBy);
        }
    }
}
