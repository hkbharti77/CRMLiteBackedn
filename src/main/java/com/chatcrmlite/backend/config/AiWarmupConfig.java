package com.chatcrmlite.backend.config;

import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import com.chatcrmlite.backend.security.TenantContext;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@org.springframework.context.annotation.Profile("!test")
public class AiWarmupConfig {

    /**
     * Warm up the AI models on startup to avoid cold-start latency 
     * for the first user message.
     */
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        TenantContext.setAdminMode(true);
        try {
            log.info("[AI-Warmup] Initializing and fixing DB constraints...");
            jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
            jdbcTemplate.execute("ALTER TABLE document_chunks DROP CONSTRAINT IF EXISTS content_length_limit");
            jdbcTemplate.execute("ALTER TABLE leads DROP CONSTRAINT IF EXISTS leads_status_check");
            jdbcTemplate.execute("ALTER TABLE leads ADD COLUMN IF NOT EXISTS lost_reason TEXT");
            jdbcTemplate.execute("ALTER TABLE faq_items ALTER COLUMN category TYPE TEXT");
            log.info("[AI-Warmup] Successfully dropped content_length_limit constraint and initialized pg_trgm.");
            
            // ENSURE business_services TABLE EXISTS (Critical Fallback)
            jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS business_services (" +
                "    id UUID PRIMARY KEY, " +
                "    user_id UUID NOT NULL, " +
                "    name VARCHAR(100) NOT NULL, " +
                "    description TEXT, " +
                "    image_data BYTEA, " +
                "    image_content_type VARCHAR(255), " +
                "    image_url VARCHAR(255), " +
                "    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP, " +
                "    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP, " +
                "    CONSTRAINT fk_business_services_user FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE" +
                ")"
            );
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_business_services_user_id ON business_services(user_id)");
            log.info("[AI-Warmup] business_services table is ready.");
            
        } catch (Exception e) {
            log.warn("[AI-Warmup] DB initialization failed: {}", e.getMessage());
        }

        try {
            AllMiniLmL6V2QuantizedEmbeddingModel model = new AllMiniLmL6V2QuantizedEmbeddingModel();
            // Run a dummy embedding to force model loading into memory
            model.embed("Hello world to warm up the sentence transformer model");
            log.info("[AI-Warmup] Embedding model ready.");
        } catch (Exception e) {
            log.error("[AI-Warmup] Failed to warm up model: {}", e.getMessage());
        } finally {
            TenantContext.setAdminMode(false);
        }
    }
}
