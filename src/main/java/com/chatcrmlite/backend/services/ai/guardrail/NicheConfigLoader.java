package com.chatcrmlite.backend.services.ai.guardrail;

import com.chatcrmlite.backend.dto.ai.NicheConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

@Slf4j
@Component
public class NicheConfigLoader {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, NicheConfig> nicheConfigs = new HashMap<>();
    
    @Getter
    private NicheConfig fallbackConfig;

    @PostConstruct
    public void loadConfigs() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:guardrails/*.json");
            for (Resource resource : resources) {
                try (InputStream is = resource.getInputStream()) {
                    NicheConfig config = objectMapper.readValue(is, NicheConfig.class);
                    nicheConfigs.put(config.getNiche(), config);
                    log.info("Loaded Guardrail Config for Niche: {}", config.getNiche());
                    if (config.getNiche().equalsIgnoreCase("Other")) {
                        fallbackConfig = config;
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to load niche guardrail configs", e);
        }
    }

    public NicheConfig getConfig(String niche) {
        NicheConfig config = nicheConfigs.getOrDefault(niche, fallbackConfig);
        if (config == null) {
            config = new NicheConfig();
            config.setIntents(new HashSet<>());
            config.setEntities(new HashMap<>());
            config.setHinglish(new HashMap<>());
            config.setPriorities(new HashMap<>());
        }
        return config;
    }
}
