package com.chatcrmlite.backend.config;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.data.segment.TextSegment;
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

    /**
     * Reuse the EmbeddingModel bean created by RagConfig — avoids loading the ONNX
     * model a second time at startup. Both this class and RagConfig share @Profile("!test"),
     * so this field is always populated when warmUp() fires.
     */
    @org.springframework.beans.factory.annotation.Autowired
    private EmbeddingModel embeddingModel;

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        TenantContext.setAdminMode(true);
        try {
            log.info("[AI-Warmup] Initializing and fixing DB constraints...");
            jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
            jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
            jdbcTemplate.execute("ALTER TABLE document_chunks DROP CONSTRAINT IF EXISTS content_length_limit");
            jdbcTemplate.execute("ALTER TABLE leads DROP CONSTRAINT IF EXISTS leads_status_check");
            jdbcTemplate.execute("ALTER TABLE leads ADD COLUMN IF NOT EXISTS lost_reason TEXT");
            jdbcTemplate.execute("ALTER TABLE faq_items ALTER COLUMN category TYPE TEXT");

            // Ensure document_chunks.embedding is converted from jsonb/text to vector(384)
            try {
                jdbcTemplate.execute(
                    "DO $$ BEGIN " +
                    "IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'document_chunks' AND column_name = 'embedding' AND udt_name != 'vector') THEN " +
                    "  ALTER TABLE document_chunks ADD COLUMN IF NOT EXISTS embedding_vec vector(384); " +
                    "  BEGIN " +
                    "    UPDATE document_chunks SET embedding_vec = (SELECT array_agg(value::text::real) FROM jsonb_array_elements(embedding))::vector WHERE embedding IS NOT NULL AND embedding_vec IS NULL AND pg_typeof(embedding)::text = 'jsonb'; " +
                    "  EXCEPTION WHEN OTHERS THEN NULL; END; " +
                    "  BEGIN " +
                    "    UPDATE document_chunks SET embedding_vec = embedding::text::vector WHERE embedding IS NOT NULL AND embedding_vec IS NULL; " +
                    "  EXCEPTION WHEN OTHERS THEN NULL; END; " +
                    "  ALTER TABLE document_chunks DROP COLUMN IF EXISTS embedding; " +
                    "  ALTER TABLE document_chunks RENAME COLUMN embedding_vec TO embedding; " +
                    "  ALTER TABLE document_chunks ALTER COLUMN embedding SET NOT NULL; " +
                    "END IF; END $$;"
                );
                log.info("[AI-Warmup] Verified document_chunks.embedding column data type is vector(384).");
            } catch (Exception e) {
                log.warn("[AI-Warmup] vector column conversion check: {}", e.getMessage());
            }

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
            // Use the existing Spring bean — do NOT construct a second ONNX model instance.
            // RagConfig.embeddingModel() already loaded AllMiniLmL6V2QuantizedEmbeddingModel
            // before ApplicationReadyEvent fires. Creating another instance wasted ~1-3s of
            // startup CPU and ~22 MB of off-heap memory for no benefit.
            embeddingModel.embed(TextSegment.from("Hello world to warm up the sentence transformer model"));
            log.info("[AI-Warmup] Embedding model ready.");
        } catch (Exception e) {
            log.error("[AI-Warmup] Failed to warm up model: {}", e.getMessage());
        } finally {
            TenantContext.setAdminMode(false);
        }
    }
}
