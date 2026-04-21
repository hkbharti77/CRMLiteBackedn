package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.BusinessSubCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface BusinessSubCategoryRepository extends JpaRepository<BusinessSubCategory, Long> {
    List<BusinessSubCategory> findByCategoryId(Long categoryId);
    boolean existsByNameAndCategoryId(String name, Long categoryId);
    java.util.Optional<com.chatcrmlite.backend.models.BusinessSubCategory> findByName(String name);
}
