package com.chatcrmlite.backend.services.google;

import com.chatcrmlite.backend.config.GoogleConfig;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.google.*;
import com.chatcrmlite.backend.repositories.GoogleConnectionRepository;
import com.chatcrmlite.backend.repositories.GoogleIntegrationRepository;
import com.chatcrmlite.backend.repositories.GoogleSyncRepository;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.services.tasks.Tasks;
import com.google.api.services.tasks.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Service for Google Tasks integration (tasks scope).
 *
 * Allows users to sync CRM follow-up reminders, lead tasks, and action items
 * with their Google Tasks list.
 */
@Service
public class GoogleTasksService {

    private static final Logger log = LoggerFactory.getLogger(GoogleTasksService.class);
    private static final String APPLICATION_NAME = "CRMLite";
    private static final GsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();

    @Autowired private GoogleTokenService googleTokenService;
    @Autowired private GoogleConnectionRepository connectionRepository;
    @Autowired private GoogleIntegrationRepository integrationRepository;
    @Autowired private GoogleSyncRepository syncRepository;
    @Autowired(required = false) private GoogleAuditService auditService;

    /**
     * Checks if the user has an active Google Tasks integration.
     */
    public boolean isConnected(UUID userId) {
        if (userId == null) return false;

        Optional<GoogleConnection> connOpt = connectionRepository.findActiveByUserId(userId);
        if (connOpt.isEmpty()) return false;

        GoogleConnection conn = connOpt.get();
        Optional<GoogleIntegration> integrationOpt =
                integrationRepository.findByConnectionIdAndFeature(conn.getId(), GoogleIntegrationType.TASKS);

        if (integrationOpt.isPresent() && integrationOpt.get().getStatus() == GoogleIntegrationStatus.CONNECTED) {
            return true;
        }

        return conn.hasScope("tasks") || conn.hasScope("https://www.googleapis.com/auth/tasks");
    }

    /**
     * Creates a task in Google Tasks.
     *
     * @param user The authenticated user
     * @param title Title of the task
     * @param notes Description or notes for the task
     * @param dueDateTime Optional due date
     * @param crmResourceId Optional CRM entity UUID (reminder, lead, or appointment)
     * @return Map containing task details
     */
    @Transactional
    public Map<String, Object> createTask(
            User user,
            String title,
            String notes,
            LocalDateTime dueDateTime,
            UUID crmResourceId) throws GeneralSecurityException, IOException {

        if (user == null || user.getId() == null) {
            throw new IllegalArgumentException("User must not be null");
        }

        GoogleConnection conn = connectionRepository.findActiveByUserId(user.getId())
                .orElseThrow(() -> new IllegalStateException("No active Google connection found. Please connect Google Tasks in Settings."));

        if (!isConnected(user.getId())) {
            throw new IllegalStateException("Google Tasks is not connected. Please authorize Tasks in Settings.");
        }

        String accessToken = googleTokenService.getValidAccessToken(conn.getId());
        Tasks tasksClient = buildTasksClient(accessToken);

        Task task = new Task();
        task.setTitle(title);
        task.setNotes(notes != null ? notes : "Synced from GyanVaniAi Connect CRM");

        if (dueDateTime != null) {
            Date dueDate = Date.from(dueDateTime.atZone(ZoneId.systemDefault()).toInstant());
            task.setDue(new DateTime(dueDate).toStringRfc3339());
        }

        long startTime = System.currentTimeMillis();
        Task createdTask;
        try {
            createdTask = tasksClient.tasks().insert("@default", task).execute();
            if (auditService != null) {
                auditService.logSuccess(user.getId(), "TASKS", "CREATE_TASK", "tasks.googleapis.com", System.currentTimeMillis() - startTime);
            }
        } catch (Exception ex) {
            if (auditService != null) {
                auditService.logFailure(user.getId(), "TASKS", "CREATE_TASK", "tasks.googleapis.com", System.currentTimeMillis() - startTime, ex, 0);
            }
            throw ex;
        }
        log.info("[GoogleTasksService] Created task in Google Tasks for userId={} taskId={}", user.getId(), createdTask.getId());

        // Save GoogleSync mapping
        if (crmResourceId != null) {
            try {
                GoogleSync sync = syncRepository.findByConnectionIdAndResourceTypeAndCrmResourceId(
                        conn.getId(), "TASK", crmResourceId
                ).orElse(new GoogleSync());

                sync.setConnectionId(conn.getId());
                sync.setResourceType("TASK");
                sync.setCrmResourceId(crmResourceId);
                sync.setGoogleResourceId(createdTask.getId());
                sync.setSyncDirection("CRM_TO_GOOGLE");
                sync.setSyncStatus("OK");
                sync.setLastSyncedAt(LocalDateTime.now());
                syncRepository.save(sync);
            } catch (Exception e) {
                log.warn("[GoogleTasksService] Failed to record GoogleSync mapping: {}", e.getMessage());
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("taskId", createdTask.getId());
        result.put("title", createdTask.getTitle());
        result.put("status", createdTask.getStatus());
        result.put("updated", createdTask.getUpdated() != null ? createdTask.getUpdated().toString() : null);
        return result;
    }

    /**
     * Lists tasks from the user's primary task list.
     */
    public List<Map<String, Object>> listTasks(User user, int maxResults) throws GeneralSecurityException, IOException {
        if (!isConnected(user.getId())) {
            return Collections.emptyList();
        }

        GoogleConnection conn = connectionRepository.findActiveByUserId(user.getId()).orElse(null);
        if (conn == null) return Collections.emptyList();

        String accessToken = googleTokenService.getValidAccessToken(conn.getId());
        Tasks tasksClient = buildTasksClient(accessToken);

        com.google.api.services.tasks.model.Tasks tasksList = tasksClient.tasks().list("@default")
                .setMaxResults(Math.min(maxResults, 100))
                .setShowCompleted(false)
                .setShowHidden(false)
                .execute();

        List<Task> items = tasksList.getItems();
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Task t : items) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", t.getId());
            map.put("title", t.getTitle());
            map.put("notes", t.getNotes());
            map.put("status", t.getStatus());
            map.put("due", t.getDue() != null ? t.getDue().toString() : null);
            map.put("updated", t.getUpdated() != null ? t.getUpdated().toString() : null);
            result.add(map);
        }

        return result;
    }

    /**
     * Marks a task as completed in Google Tasks.
     */
    @Transactional
    public Map<String, Object> completeTask(User user, String taskId) throws GeneralSecurityException, IOException {
        if (!isConnected(user.getId()) || taskId == null || taskId.isBlank()) {
            throw new IllegalStateException("Google Tasks is not connected or taskId is missing.");
        }

        GoogleConnection conn = connectionRepository.findActiveByUserId(user.getId())
                .orElseThrow(() -> new IllegalStateException("No active Google connection found."));

        String accessToken = googleTokenService.getValidAccessToken(conn.getId());
        Tasks tasksClient = buildTasksClient(accessToken);

        Task task = tasksClient.tasks().get("@default", taskId).execute();
        task.setStatus("completed");
        task.setCompleted(new DateTime(new Date()).toStringRfc3339());

        Task updatedTask = tasksClient.tasks().update("@default", taskId, task).execute();
        log.info("[GoogleTasksService] Completed task taskId={} for userId={}", taskId, user.getId());

        return Map.of("success", true, "taskId", updatedTask.getId(), "status", updatedTask.getStatus());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private Tasks buildTasksClient(String accessToken) throws GeneralSecurityException, IOException {
        HttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();
        HttpRequestInitializer initializer = request ->
                request.getHeaders().setAuthorization("Bearer " + accessToken);

        return new Tasks.Builder(transport, JSON_FACTORY, initializer)
                .setApplicationName(APPLICATION_NAME)
                .build();
    }
}
