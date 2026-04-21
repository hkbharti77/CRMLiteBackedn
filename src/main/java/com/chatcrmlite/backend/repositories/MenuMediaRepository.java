package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.MenuMedia;
import com.chatcrmlite.backend.models.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MenuMediaRepository extends JpaRepository<MenuMedia, UUID> {
    Optional<MenuMedia> findByIdAndOwner(UUID id, User owner);
}
