package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.BusinessService;
import com.chatcrmlite.backend.models.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BusinessServiceRepository extends JpaRepository<BusinessService, UUID> {
    List<BusinessService> findByOwner(User owner);
    Page<BusinessService> findByOwner(User owner, Pageable pageable);
    Optional<BusinessService> findByIdAndOwner(UUID id, User owner);
}
