package com.chatcrmlite.backend.services.ai.guardrail;

import com.chatcrmlite.backend.dto.ai.NicheConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NluEngineCacheIsolationTest {

    private NluEngine nluEngine;

    @BeforeEach
    void setUp() {
        // Initialize with a simple meter registry for testing
        nluEngine = new NluEngine(new SimpleMeterRegistry());
    }

    @Test
    void applyHinglishMapping_UsesNicheIdIfPresent() {
        // Given a config with a niche
        Map<String, String> hinglish = new HashMap<>();
        hinglish.put("hello", "hi");
        NicheConfig config = new NicheConfig();
        config.setNiche("test-niche");
        config.setHinglish(hinglish);

        // When
        String result = nluEngine.applyHinglishMapping("hello there", config);

        // Then
        assertEquals("hi there", result);
    }

    @Test
    void applyHinglishMapping_DifferentConfigsNeverCollide() {
        // Given two configurations that could theoretically have the same hashCode
        // if we were still using Map.hashCode() (e.g. hash collisions are possible),
        // we simulate this by ensuring deterministic representation handles differences correctly.
        
        Map<String, String> hinglish1 = new HashMap<>();
        hinglish1.put("aa", "bb"); // Config 1

        Map<String, String> hinglish2 = new HashMap<>();
        hinglish2.put("cc", "dd"); // Config 2

        NicheConfig config1 = new NicheConfig();
        config1.setHinglish(hinglish1);

        NicheConfig config2 = new NicheConfig();
        config2.setHinglish(hinglish2);

        // When applying both, they should not interfere
        String res1 = nluEngine.applyHinglishMapping("test aa test", config1);
        String res2 = nluEngine.applyHinglishMapping("test cc test", config2);

        // Then
        assertEquals("test bb test", res1);
        assertEquals("test dd test", res2);
        
        // Also verify cross-pollution doesn't happen
        String crossRes1 = nluEngine.applyHinglishMapping("test cc test", config1);
        assertEquals("test cc test", crossRes1); // config1 doesn't map 'cc'
        
        String crossRes2 = nluEngine.applyHinglishMapping("test aa test", config2);
        assertEquals("test aa test", crossRes2); // config2 doesn't map 'aa'
    }

    @Test
    void applyHinglishMapping_DifferentTenantsNeverShareCachedRegex() {
        // Given two identical maps, they will share the cached regex.
        // But if they differ slightly, they MUST use different regex patterns.
        
        Map<String, String> tenant1Hinglish = new HashMap<>();
        tenant1Hinglish.put("hello", "hi");
        tenant1Hinglish.put("world", "earth");

        Map<String, String> tenant2Hinglish = new HashMap<>();
        tenant2Hinglish.put("hello", "hi");
        tenant2Hinglish.put("world", "globe"); // Different mapping

        NicheConfig config1 = new NicheConfig();
        config1.setHinglish(tenant1Hinglish);

        NicheConfig config2 = new NicheConfig();
        config2.setHinglish(tenant2Hinglish);

        // First cache config1's pattern
        String res1 = nluEngine.applyHinglishMapping("hello world", config1);
        assertEquals("hi earth", res1);

        // If isolation failed, config2 might use config1's cached pattern and map to 'earth'
        String res2 = nluEngine.applyHinglishMapping("hello world", config2);
        assertEquals("hi globe", res2);
    }

    @Test
    void applyHinglishMapping_EmptyConfigHandledGracefully() {
        NicheConfig emptyConfig = new NicheConfig();
        
        String result = nluEngine.applyHinglishMapping("hello world", emptyConfig);
        
        assertEquals("hello world", result);
    }
}
