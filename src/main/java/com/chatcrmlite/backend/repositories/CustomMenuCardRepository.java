package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.CustomMenuCard;
import com.chatcrmlite.backend.models.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CustomMenuCardRepository extends JpaRepository<CustomMenuCard, UUID> {

    /** Returns all cards for a tenant in display order (lowest number first). */
    List<CustomMenuCard> findByTenantOrderByDisplayOrderAsc(Tenant tenant);

    /** Returns only cards for a specific section (e.g. "SERVICES"). */
    List<CustomMenuCard> findByTenantAndSectionOrderByDisplayOrderAsc(Tenant tenant, String section);

    /** Check whether a tenant has ANY custom cards at all. */
    boolean existsByTenant(Tenant tenant);

    /** Delete all custom cards for a tenant before bulk-saving new ones. */
    void deleteByTenant(Tenant tenant);
}
