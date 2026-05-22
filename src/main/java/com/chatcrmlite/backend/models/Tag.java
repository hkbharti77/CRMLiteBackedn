package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * AP-2: Normalized Tag entity.
 *
 * Replaces @ElementCollection String tags on Contact, Lead, and Message.
 * Each tag is scoped to an owner (tenant) and an entity type so tags
 * remain entity-specific while sharing a single lookup table.
 *
 * The unique constraint (owner_id, entity_type, name) prevents duplicate
 * tags per entity domain within a tenant.
 */
@Entity
@Table(
    name = "tags",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_tag_owner_type_name",
                          columnNames = {"owner_id", "entity_type", "name"})
    },
    indexes = {
        @Index(name = "idx_tag_owner_type", columnList = "owner_id, entity_type")
    }
)
public class Tag implements Serializable {
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_tag_owner"))
    private User owner;

    /**
     * Discriminator: CONTACT | LEAD | MESSAGE
     * Keeps tags entity-scoped so "VIP" on a Contact is distinct from "VIP" on a Lead.
     */
    @Column(name = "entity_type", nullable = false, length = 30)
    private String entityType;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "color", length = 20)
    private String color;  // Optional hex color for UI badge, e.g. "#22c55e"

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public Tag() {}

    public Tag(User owner, String entityType, String name) {
        this.owner = owner;
        this.entityType = entityType;
        this.name = name;
    }

    public static final String TYPE_CONTACT = "CONTACT";
    public static final String TYPE_LEAD    = "LEAD";
    public static final String TYPE_MESSAGE = "MESSAGE";

    public UUID getId()                 { return id; }
    public User getOwner()              { return owner; }
    public String getEntityType()       { return entityType; }
    public String getName()             { return name; }
    public String getColor()            { return color; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public void setId(UUID id)                { this.id = id; }
    public void setOwner(User owner)          { this.owner = owner; }
    public void setEntityType(String t)       { this.entityType = t; }
    public void setName(String name)          { this.name = name; }
    public void setColor(String color)        { this.color = color; }
    public void setCreatedAt(LocalDateTime t) { this.createdAt = t; }

    public static TagBuilder builder() { return new TagBuilder(); }

    public static class TagBuilder {
        private User owner;
        private String entityType;
        private String name;
        private String color;

        public TagBuilder owner(User owner)           { this.owner = owner;         return this; }
        public TagBuilder entityType(String type)     { this.entityType = type;     return this; }
        public TagBuilder name(String name)           { this.name = name;           return this; }
        public TagBuilder color(String color)         { this.color = color;         return this; }

        public Tag build() {
            Tag t = new Tag(owner, entityType, name);
            t.setColor(color);
            return t;
        }
    }
}
