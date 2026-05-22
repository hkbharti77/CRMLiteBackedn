package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * AP-3: Normalized ticket category (replaces comma-separated string in SupportFormConfig.categories).
 *
 * Each tenant owns their own list of categories.
 * A Ticket references a TicketCategory via FK, providing full referential integrity
 * and the ability to rename/delete categories centrally.
 */
@Entity
@Table(
    name = "ticket_categories",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_tc_owner_name", columnNames = {"owner_id", "name"})
    },
    indexes = {
        @Index(name = "idx_tc_owner_id", columnList = "owner_id")
    }
)
public class TicketCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_tc_owner"))
    private User owner;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "display_order", nullable = false)
    private int displayOrder = 0;

    @Column(name = "is_active", nullable = false, columnDefinition = "BOOLEAN DEFAULT TRUE")
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public TicketCategory() {}

    public TicketCategory(User owner, String name, int displayOrder) {
        this.owner = owner;
        this.name = name;
        this.displayOrder = displayOrder;
    }

    public UUID getId()                 { return id; }
    public User getOwner()              { return owner; }
    public String getName()             { return name; }
    public int getDisplayOrder()        { return displayOrder; }
    public boolean isActive()           { return active; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public void setId(UUID id)                    { this.id = id; }
    public void setOwner(User owner)              { this.owner = owner; }
    public void setName(String name)              { this.name = name; }
    public void setDisplayOrder(int order)        { this.displayOrder = order; }
    public void setActive(boolean active)         { this.active = active; }
    public void setCreatedAt(LocalDateTime t)     { this.createdAt = t; }

    public static TicketCategoryBuilder builder() { return new TicketCategoryBuilder(); }

    public static class TicketCategoryBuilder {
        private User owner;
        private String name;
        private int displayOrder = 0;
        private boolean active = true;

        public TicketCategoryBuilder owner(User owner)         { this.owner = owner; return this; }
        public TicketCategoryBuilder name(String name)         { this.name = name;   return this; }
        public TicketCategoryBuilder displayOrder(int order)   { this.displayOrder = order; return this; }
        public TicketCategoryBuilder active(boolean active)    { this.active = active; return this; }

        public TicketCategory build() {
            TicketCategory tc = new TicketCategory(owner, name, displayOrder);
            tc.setActive(active);
            return tc;
        }
    }
}
