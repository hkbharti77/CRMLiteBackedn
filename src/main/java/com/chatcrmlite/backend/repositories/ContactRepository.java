package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContactRepository extends JpaRepository<Contact, UUID> {

    /**
     * Check if a contact with the given email exists for the given tenant.
     * Uses the ManyToOne tenant relationship on BaseTenantEntity (column: tenant_id).
     */
    boolean existsByEmailAndTenant_Id(String email, UUID tenantId);
    Optional<Contact> findByWaIdAndOwner(String waId, User owner);
    List<Contact> findAllByOwner(User owner);
    Optional<Contact> findByWaId(String waId);
    List<Contact> findByName(String name);
    
    /**
     * Fetch all contacts for a user matching any of the given tag IDs.
     */
    List<Contact> findByOwnerAndTags_IdIn(User owner, List<UUID> tagIds);

    /**
     * Fetch all contacts for a user with tags eagerly loaded.
     * Prevents LazyInitializationException when accessing tags outside transaction.
     */
    @Query("SELECT DISTINCT c FROM Contact c LEFT JOIN FETCH c.tags WHERE c.owner = :owner")
    List<Contact> findAllByOwnerWithTags(@Param("owner") User owner);
    
    /**
     * Fetch a single contact by ID with tags eagerly loaded.
     */
    @Query("SELECT c FROM Contact c LEFT JOIN FETCH c.tags WHERE c.id = :id")
    Optional<Contact> findByIdWithTags(@Param("id") UUID id);

    /**
     * Fetch all contacts for the active tenant with tags eagerly loaded.
     * Filtered automatically by Hibernate tenantFilter.
     */
    @Query("SELECT DISTINCT c FROM Contact c LEFT JOIN FETCH c.tags")
    List<Contact> findAllWithTags();
}

