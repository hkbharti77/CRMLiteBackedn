package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.FaqItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FaqItemRepository extends JpaRepository<FaqItem, UUID> {

    List<FaqItem> findByTenantId(UUID tenantId);

    List<FaqItem> findByTenantIdAndIsActiveTrue(UUID tenantId);

    Optional<FaqItem> findByIdAndTenantId(UUID id, UUID tenantId);

    long countByTenantId(UUID tenantId);

    void deleteByIdAndTenantId(UUID id, UUID tenantId);

    @Modifying
    @Transactional
    @Query("DELETE FROM FaqItem f WHERE f.id = :id AND f.tenantId = :tenantId")
    int deleteByFaqIdAndTenantId(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    @Modifying
    @Transactional
    @Query("DELETE FROM FaqItem f WHERE f.tenantId = :tenantId")
    int deleteAllByTenantId(@Param("tenantId") UUID tenantId);

    @Modifying
    @Transactional
    @Query("DELETE FROM FaqItem f WHERE f.id IN :ids AND f.tenantId = :tenantId")
    int deleteByIdInAndTenantId(@Param("ids") List<UUID> ids, @Param("tenantId") UUID tenantId);

    @Modifying
    @Transactional
    @Query("UPDATE FaqItem f SET f.hitCount = f.hitCount + 1 WHERE f.id = :id")
    void incrementHitCount(@Param("id") UUID id);

    @Query(value = """
        SELECT * FROM faq_items
        WHERE tenant_id = :tenantId
          AND is_active = true
          AND lower(trim(question)) = lower(trim(:question))
        LIMIT 1
        """, nativeQuery = true)
    Optional<FaqItem> findFirstByTenantAndExactQuestion(
        @Param("tenantId") UUID tenantId, 
        @Param("question") String question
    );

    @Query(value = """
        SELECT f.id AS id, (f.embedding <=> CAST(:embedding AS vector)) AS distance
        FROM faq_items f
        WHERE f.tenant_id = :tenantId
          AND f.is_active = true
        ORDER BY distance ASC
        LIMIT 1
        """, nativeQuery = true)
    Optional<FaqVectorMatch> findNearestByEmbedding(
        @Param("tenantId") UUID tenantId,
        @Param("embedding") String embedding
    );
}
