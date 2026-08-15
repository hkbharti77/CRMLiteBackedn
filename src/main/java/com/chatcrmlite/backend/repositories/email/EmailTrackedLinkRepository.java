package com.chatcrmlite.backend.repositories.email;

import com.chatcrmlite.backend.models.email.EmailTrackedLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmailTrackedLinkRepository extends JpaRepository<EmailTrackedLink, UUID> {
    Optional<EmailTrackedLink> findByLinkToken(String linkToken);
}
