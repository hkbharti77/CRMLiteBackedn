package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.EmailProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EmailProviderRepository extends JpaRepository<EmailProvider, String> {
    List<EmailProvider> findByBusinessId(String businessId);
    Optional<EmailProvider> findByIdAndBusinessId(String id, String businessId);
    Optional<EmailProvider> findByBusinessIdAndIsDefaultTrue(String businessId);
}
