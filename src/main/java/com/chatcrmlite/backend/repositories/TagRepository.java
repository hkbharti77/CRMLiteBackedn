package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.Tag;
import com.chatcrmlite.backend.models.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TagRepository extends JpaRepository<Tag, UUID> {
    List<Tag> findAllByOwnerAndEntityType(User owner, String entityType);
    Optional<Tag> findByOwnerAndEntityTypeAndName(User owner, String entityType, String name);
}
