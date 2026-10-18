package com.chatcrmlite.backend.utils;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Robust loader for environment variables that checks:
 * 1. OS System Environment (System.getenv)
 * 2. JVM System Properties (System.getProperty)
 * 3. Root and backend .env files (with live hot-reload on file modification)
 */
@Slf4j
public class DotenvLoader {

    private static final Map<String, String> CACHED_ENV = new ConcurrentHashMap<>();
    private static volatile long lastModifiedTime = 0L;
    private static volatile File activeEnvFile = null;

    private static synchronized void checkAndReload() {
        File candidate = activeEnvFile;
        if (candidate == null || !candidate.exists()) {
            File[] candidateFiles = new File[] {
                new File(".env"),
                new File("CRMLiteBackedn/.env"),
                new File("../CRMLiteBackedn/.env"),
                new File(System.getProperty("user.dir"), ".env")
            };
            for (File f : candidateFiles) {
                if (f.exists() && f.isFile() && f.canRead()) {
                    candidate = f;
                    activeEnvFile = f;
                    break;
                }
            }
        }

        if (candidate == null || !candidate.exists()) {
            return;
        }

        long fileMod = candidate.lastModified();
        if (fileMod != lastModifiedTime) {
            log.info("📄 [DotenvLoader] Loading/Reloading environment variables from {}", candidate.getAbsolutePath());
            try (BufferedReader reader = new BufferedReader(new FileReader(candidate, StandardCharsets.UTF_8))) {
                Map<String, String> newMap = new ConcurrentHashMap<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int eqIdx = line.indexOf('=');
                    if (eqIdx > 0) {
                        String key = line.substring(0, eqIdx).trim();
                        String val = line.substring(eqIdx + 1).trim();
                        if ((val.startsWith("\"") && val.endsWith("\"")) || (val.startsWith("'") && val.endsWith("'"))) {
                            if (val.length() >= 2) {
                                val = val.substring(1, val.length() - 1);
                            }
                        }
                        newMap.put(key, val);
                    }
                }
                CACHED_ENV.clear();
                CACHED_ENV.putAll(newMap);
                lastModifiedTime = fileMod;
                log.info("✅ [DotenvLoader] Successfully loaded {} properties from .env", CACHED_ENV.size());
            } catch (Exception e) {
                log.warn("⚠️ [DotenvLoader] Failed to read .env file: {}", e.getMessage());
            }
        }
    }

    public static String get(String key, String defaultValue) {
        String val = get(key);
        return (val != null && !val.isBlank()) ? val : defaultValue;
    }

    public static String get(String key) {
        if (key == null || key.isBlank()) return null;

        // 1. System environment variable (e.g. from Docker or shell)
        String val = System.getenv(key);
        if (val != null && !val.isBlank()) {
            return val.trim();
        }

        // 2. JVM System property (-D)
        val = System.getProperty(key);
        if (val != null && !val.isBlank()) {
            return val.trim();
        }

        // 3. .env file
        checkAndReload();
        val = CACHED_ENV.get(key);
        if (val != null && !val.isBlank()) {
            return val.trim();
        }

        return null;
    }
}
