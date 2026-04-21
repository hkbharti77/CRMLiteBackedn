package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContactRepository extends JpaRepository<Contact, UUID> {
    Optional<Contact> findByWaIdAndOwner(String waId, User owner);
    List<Contact> findAllByOwner(User owner);
    Optional<Contact> findByWaId(String waId);
    List<Contact> findByName(String name);
}
