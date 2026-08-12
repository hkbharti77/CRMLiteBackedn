package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "lead_assignments", indexes = {
    @Index(name = "idx_lead_assign_agent_date", columnList = "agent_id, assigned_at, lead_id")
})
public class LeadAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lead_id", nullable = false)
    private Lead lead;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_id", nullable = false)
    private User agent;

    @Column(name = "assigned_at", nullable = false)
    private LocalDateTime assignedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_by_id")
    private User assignedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "assignment_source", nullable = false)
    private AssignmentSource assignmentSource;

    public LeadAssignment() {}

    public LeadAssignment(Lead lead, User agent, LocalDateTime assignedAt, User assignedBy, AssignmentSource assignmentSource) {
        this.lead = lead;
        this.agent = agent;
        this.assignedAt = assignedAt != null ? assignedAt : LocalDateTime.now();
        this.assignedBy = assignedBy;
        this.assignmentSource = assignmentSource;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public Lead getLead() { return lead; }
    public void setLead(Lead lead) { this.lead = lead; }

    public User getAgent() { return agent; }
    public void setAgent(User agent) { this.agent = agent; }

    public LocalDateTime getAssignedAt() { return assignedAt; }
    public void setAssignedAt(LocalDateTime assignedAt) { this.assignedAt = assignedAt; }

    public User getAssignedBy() { return assignedBy; }
    public void setAssignedBy(User assignedBy) { this.assignedBy = assignedBy; }

    public AssignmentSource getAssignmentSource() { return assignmentSource; }
    public void setAssignmentSource(AssignmentSource assignmentSource) { this.assignmentSource = assignmentSource; }
}
