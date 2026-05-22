package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.CustomEmail;
import com.chatcrmlite.backend.models.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CustomEmailRepository extends JpaRepository<CustomEmail, UUID> {

    Page<CustomEmail> findAllByOwnerOrderByCreatedAtDesc(User owner, Pageable pageable);

    List<CustomEmail> findAllByOwnerOrderByCreatedAtDesc(User owner);

    long countByOwner(User owner);
}
