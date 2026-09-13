package com.chatcrmlite.backend.tools;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class ConstraintFixer {

    private static final Logger log = LoggerFactory.getLogger(ConstraintFixer.class);
    private final JdbcTemplate jdbcTemplate;

    public ConstraintFixer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void fix() {
        try {
            log.info("Attempting to drop bad foreign key constraint 'fkapybf0vjkpb5kf30iu0n0jk1c' on document_chunks...");
            jdbcTemplate.execute("ALTER TABLE document_chunks DROP CONSTRAINT IF EXISTS fkapybf0vjkpb5kf30iu0n0jk1c");
            log.info("✅ Successfully dropped bad foreign key constraint!");
        } catch (Exception e) {
            log.warn("⚠️ Could not drop constraint (it may have already been dropped): {}", e.getMessage());
        }
    }
}
