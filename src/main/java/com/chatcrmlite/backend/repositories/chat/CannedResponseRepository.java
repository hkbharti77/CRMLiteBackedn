package com.chatcrmlite.backend.repositories.chat;

import com.chatcrmlite.backend.models.chat.CannedResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CannedResponseRepository extends JpaRepository<CannedResponse, UUID> {

    @Query("SELECT c FROM CannedResponse c WHERE c.tenant.id = :tenantId ORDER BY c.shortcut ASC")
    List<CannedResponse> findByTenantIdOrderByShortcutAsc(@Param("tenantId") UUID tenantId);

    @Query("SELECT c FROM CannedResponse c WHERE c.tenant.id = :tenantId AND LOWER(c.category) = LOWER(:category) ORDER BY c.shortcut ASC")
    List<CannedResponse> findByTenantIdAndCategoryIgnoreCaseOrderByShortcutAsc(@Param("tenantId") UUID tenantId, @Param("category") String category);

    @Query("SELECT c FROM CannedResponse c WHERE c.tenant.id = :tenantId AND LOWER(c.shortcut) = LOWER(:shortcut)")
    Optional<CannedResponse> findByTenantIdAndShortcutIgnoreCase(@Param("tenantId") UUID tenantId, @Param("shortcut") String shortcut);

    @Query("SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END FROM CannedResponse c WHERE c.tenant.id = :tenantId AND LOWER(c.shortcut) = LOWER(:shortcut)")
    boolean existsByTenantIdAndShortcutIgnoreCase(@Param("tenantId") UUID tenantId, @Param("shortcut") String shortcut);

    @Query("SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END FROM CannedResponse c WHERE c.tenant.id = :tenantId AND LOWER(c.shortcut) = LOWER(:shortcut) AND c.id != :id")
    boolean existsByTenantIdAndShortcutIgnoreCaseAndIdNot(@Param("tenantId") UUID tenantId, @Param("shortcut") String shortcut, @Param("id") UUID id);

    @Query("SELECT c FROM CannedResponse c WHERE c.tenant.id = :tenantId AND (" +
           "LOWER(c.shortcut) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(c.title) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(c.content) LIKE LOWER(CONCAT('%', :query, '%'))) " +
           "ORDER BY c.usageCount DESC, c.shortcut ASC")
    List<CannedResponse> searchByKeyword(@Param("tenantId") UUID tenantId, @Param("query") String query);
}
