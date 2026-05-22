package com.chatcrmlite.backend.chaos;

import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class ChaosMonkeyService {

    private final Map<String, Boolean> activeExperiments = new ConcurrentHashMap<>();
    private final Map<String, Integer> experimentLatencies = new ConcurrentHashMap<>();

    public void startExperiment(String name) {
        log.warn("⚠️ [Chaos-Monkey] Starting experiment: {}", name);
        activeExperiments.put(name, true);
    }

    public void stopExperiment(String name) {
        log.info("✅ [Chaos-Monkey] Stopping experiment: {}", name);
        activeExperiments.remove(name);
    }

    public boolean isExperimentActive(String name) {
        return activeExperiments.getOrDefault(name, false);
    }

    public void injectLatency(String name, int ms) {
        experimentLatencies.put(name, ms);
    }

    public void simulateLatency(String name) {
        Integer ms = experimentLatencies.get(name);
        if (ms != null && ms > 0) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}

@RestController
@RequestMapping("/api/admin/chaos")
@RequiredArgsConstructor
class ChaosAdminController {

    private final ChaosMonkeyService chaosMonkey;

    @PostMapping("/start/{experiment}")
    public String start(@PathVariable String experiment) {
        chaosMonkey.startExperiment(experiment);
        return "Experiment " + experiment + " started.";
    }

    @PostMapping("/stop/{experiment}")
    public String stop(@PathVariable String experiment) {
        chaosMonkey.stopExperiment(experiment);
        return "Experiment " + experiment + " stopped.";
    }

    @PostMapping("/latency/{experiment}/{ms}")
    public String setLatency(@PathVariable String experiment, @PathVariable int ms) {
        chaosMonkey.injectLatency(experiment, ms);
        return "Latency of " + ms + "ms injected into " + experiment;
    }
}
