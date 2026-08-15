package com.chatcrmlite.backend.repositories.email;

import com.chatcrmlite.backend.models.email.EmailRecipientEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface EmailRecipientEventRepository extends JpaRepository<EmailRecipientEvent, UUID> {
}
