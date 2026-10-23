package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.CreditTopUpProduct;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CreditTopUpProductRepository extends JpaRepository<CreditTopUpProduct, String> {
    List<CreditTopUpProduct> findByIsActiveTrue();
}
