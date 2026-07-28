package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.NicheTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NicheTemplateRepository extends JpaRepository<NicheTemplate, String> {
    List<NicheTemplate> findByStatus(String status);
    List<NicheTemplate> findByNiche(String niche);
}
