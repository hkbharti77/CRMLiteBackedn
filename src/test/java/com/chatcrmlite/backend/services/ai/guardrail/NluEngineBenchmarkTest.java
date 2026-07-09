package com.chatcrmlite.backend.services.ai.guardrail;

import com.chatcrmlite.backend.dto.ai.NicheConfig;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import static org.junit.jupiter.api.Assertions.*;

class NluEngineBenchmarkTest {

    private static final Logger log = LoggerFactory.getLogger(NluEngineBenchmarkTest.class);
    private final NluEngine engine = new NluEngine(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

    @Test
    void testIsTrash() {
        // Repeated chars should be flagged as trash
        assertTrue(engine.isTrash("aaaaa"), "5 repeated chars should be trash");
        assertTrue(engine.isTrash("helloooooworld"), "5 repeated 'o' should be trash");
        assertTrue(engine.isTrash("bbbbbbb"), "Low entropy (<0.20) should be trash");
        assertFalse(engine.isTrash("hello"), "Normal text is not trash");
    }

    @Test
    void testExtractEntitiesCleanWord() {
        NicheConfig config = new NicheConfig();
        config.setEntities(Map.of("gym", "fitness", "yoga", "wellness"));
        config.setPriorities(Map.of());

        // Should clean the commas and punctuation
        var entities = engine.extractEntities("I want to do yoga, and gym!!", config);
        assertEquals(2, entities.size());
        assertTrue(entities.contains("fitness"));
        assertTrue(entities.contains("wellness"));
    }

    @Test
    void testApplyHinglishMapping() {
        NicheConfig config = new NicheConfig();
        config.setHinglish(Map.of("kyun", "why", "kab", "when", "daam", "cost"));

        String mapped = engine.applyHinglishMapping("kyun aur kab daam", config);
        assertEquals("why aur when cost", mapped);
        
        // Edge case: kitna time
        assertEquals("duration lagta hai", engine.applyHinglishMapping("kitna time lagta hai", config));
    }

    @Test
    void testFuzzyCacheIsolation() {
        NicheConfig configA = new NicheConfig();
        configA.setNiche("tenantA");
        configA.setIntents(java.util.Set.of("pricing"));

        NicheConfig configB = new NicheConfig();
        configB.setNiche("tenantB");
        configB.setIntents(java.util.Set.of("privacy"));

        // Both tenants get the misspelled word 'prcing'. 
        // For A it should resolve to pricing. 
        // For B it should NOT resolve to pricing since they don't have it in their intents.
        
        var intentsA = engine.detectIntents("tell me about prcing", configA);
        assertTrue(intentsA.contains("pricing"));

        var intentsB = engine.detectIntents("tell me about prcing", configB);
        assertFalse(intentsB.contains("pricing"));
    }

    @Test
    void benchmarkRegexOptimizations() {
        // Setup
        String testTrash = "some random text that is long enough to not be low entropy but might trigger regex evaluating";
        
        NicheConfig config = new NicheConfig();
        Map<String, String> hinglishDict = new HashMap<>();
        for (int i = 0; i < 50; i++) hinglishDict.put("word" + i, "trans" + i);
        config.setHinglish(hinglishDict);
        String textToMap = "This is a word10 test with word20 and word49 in it.";

        int iterations = 100_000;

        // Warmup (to stabilize JIT compiler for the benchmark)
        for (int i = 0; i < 10000; i++) {
            oldIsTrashRegex(testTrash);
            engine.isTrash(testTrash);
            oldApplyHinglish(textToMap, config);
            engine.applyHinglishMapping(textToMap, config);
        }

        // 1. Benchmark isTrash
        long startOldTrash = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            oldIsTrashRegex(testTrash);
        }
        long timeOldTrash = System.nanoTime() - startOldTrash;

        long startNewTrash = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            engine.isTrash(testTrash);
        }
        long timeNewTrash = System.nanoTime() - startNewTrash;

        // 2. Benchmark applyHinglishMapping
        long startOldMap = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            oldApplyHinglish(textToMap, config);
        }
        long timeOldMap = System.nanoTime() - startOldMap;

        long startNewMap = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            engine.applyHinglishMapping(textToMap, config);
        }
        long timeNewMap = System.nanoTime() - startNewMap;

        log.info("Benchmark Result (100k iterations) - isTrash: Old={}ms, New={}ms", timeOldTrash / 1_000_000, timeNewTrash / 1_000_000);
        log.info("Benchmark Result (100k iterations) - hinglish: Old={}ms, New={}ms", timeOldMap / 1_000_000, timeNewMap / 1_000_000);
    }

    @Test
    void benchmarkLevenshteinAllocations() throws Exception {
        // Reflection to test the private levenshteinDistance
        java.lang.reflect.Method oldLev = NluEngineBenchmarkTest.class.getDeclaredMethod("oldLevenshteinDistance", String.class, String.class);
        oldLev.setAccessible(true);
        
        java.lang.reflect.Method newLev = NluEngine.class.getDeclaredMethod("levenshteinDistance", String.class, String.class);
        newLev.setAccessible(true);

        String s1 = "pricing";
        String s2 = "prcing";
        int iterations = 1_000_000;

        // Old Allocating Method
        long startOld = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            oldLev.invoke(this, s1, s2);
        }
        long timeOld = System.nanoTime() - startOld;

        // New Zero-Allocation Method
        long startNew = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            newLev.invoke(engine, s1, s2);
        }
        long timeNew = System.nanoTime() - startNew;

        log.info("Levenshtein Benchmark (1M iterations) - Old (Allocating): {}ms, New (ThreadLocal): {}ms", timeOld / 1_000_000, timeNew / 1_000_000);
        
        // Assert they produce identical results
        assertEquals(oldLev.invoke(this, s1, s2), newLev.invoke(engine, s1, s2));
    }

    private boolean oldIsTrashRegex(String text) {
        return text.matches(".*(.)\\1{4,}.*"); // Just testing the regex portion
    }

    private String oldApplyHinglish(String text, NicheConfig config) {
        String result = text;
        if (text.contains("kitna time")) return text.replaceAll("\\bkitna\\b", "duration");
        for (Map.Entry<String, String> entry : config.getHinglish().entrySet()) {
            result = result.replaceAll("\\b" + entry.getKey() + "\\b", entry.getValue());
        }
        return result;
    }

    private int oldLevenshteinDistance(String s1, String s2) {
        if (s1.length() < s2.length()) return oldLevenshteinDistance(s2, s1);
        int[] dp = new int[s2.length() + 1];
        for (int i = 0; i <= s2.length(); i++) dp[i] = i;
        for (int i = 1; i <= s1.length(); i++) {
            int prev = i;
            for (int j = 1; j <= s2.length(); j++) {
                int next = (s1.charAt(i - 1) == s2.charAt(j - 1)) ? dp[j - 1] : 1 + Math.min(Math.min(dp[j], dp[j - 1]), prev);
                dp[j - 1] = prev;
                prev = next;
            }
            dp[s2.length()] = prev;
        }
        return dp[s2.length()];
    }
}
