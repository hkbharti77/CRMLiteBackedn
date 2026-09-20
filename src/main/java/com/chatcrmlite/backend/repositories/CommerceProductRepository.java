package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.CommerceCatalog;
import com.chatcrmlite.backend.models.CommerceProduct;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CommerceProductRepository extends JpaRepository<CommerceProduct, UUID> {
    List<CommerceProduct> findAllByCatalog(CommerceCatalog catalog);
    Optional<CommerceProduct> findByCatalogAndProductRetailerId(CommerceCatalog catalog, String productRetailerId);
    long countByCatalog(CommerceCatalog catalog);
}
