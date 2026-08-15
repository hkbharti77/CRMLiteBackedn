package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.LeadAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface LeadAssignmentRepository extends JpaRepository<LeadAssignment, UUID> {
}
