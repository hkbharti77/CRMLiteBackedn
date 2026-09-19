package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.dto.CustomEmailDTO;
import com.chatcrmlite.backend.dto.CustomEmailRequest;
import com.chatcrmlite.backend.dto.AiContentResponse;
import com.chatcrmlite.backend.dto.AiTemplateResponse;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.CustomEmailService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/custom-emails")
public class CustomEmailController {

    private final CustomEmailService customEmailService;
    private final UserRepository     userRepository;
    private final com.chatcrmlite.backend.services.email.EmailInboundReplyService inboundReplyService;

    @Autowired
    public CustomEmailController(CustomEmailService customEmailService, UserRepository userRepository, com.chatcrmlite.backend.services.email.EmailInboundReplyService inboundReplyService) {
        this.customEmailService = customEmailService;
        this.userRepository = userRepository;
        this.inboundReplyService = inboundReplyService;
    }

    private User me() {
        String email = (String) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    @PostMapping("/send")
    public ResponseEntity<CustomEmailDTO> send(@Valid @RequestBody CustomEmailRequest req) {
        return ResponseEntity.ok(customEmailService.scheduleOrExecuteCampaign(null, req, me()));
    }

    @PostMapping("/{id}/send")
    public ResponseEntity<CustomEmailDTO> sendExisting(@PathVariable UUID id, @Valid @RequestBody CustomEmailRequest req) {
        return ResponseEntity.ok(customEmailService.scheduleOrExecuteCampaign(id, req, me()));
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<CustomEmailDTO> pause(@PathVariable UUID id) {
        return ResponseEntity.ok(customEmailService.pauseCampaign(id, me()));
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<CustomEmailDTO> resume(@PathVariable UUID id) {
        return ResponseEntity.ok(customEmailService.resumeCampaign(id, me()));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<CustomEmailDTO> cancel(@PathVariable UUID id) {
        return ResponseEntity.ok(customEmailService.cancelCampaign(id, me()));
    }

    @PostMapping("/{id}/test-send")
    public ResponseEntity<Map<String, String>> testSend(@PathVariable UUID id, @RequestBody Map<String, String> payload) {
        String testEmail = payload.get("testEmail");
        if (testEmail == null || testEmail.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "testEmail is required"));
        }
        String msg = customEmailService.sendTestEmail(id, testEmail, me());
        return ResponseEntity.ok(Map.of("message", msg));
    }

    @PostMapping("/ai/generate-content")
    public ResponseEntity<AiContentResponse> generateAiContent(@RequestBody Map<String, String> request) {
        String prompt = request.get("prompt");
        if (prompt == null || prompt.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(customEmailService.generateAiContent(me(), prompt));
    }

    @PostMapping("/ai/generate-template")
    public ResponseEntity<AiTemplateResponse> generateAiTemplate(@RequestBody Map<String, String> request) {
        String prompt = request.get("prompt");
        if (prompt == null || prompt.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(customEmailService.generateAiTemplate(me(), prompt));
    }

    @PostMapping("/audience/preview")
    public ResponseEntity<com.chatcrmlite.backend.dto.AudiencePreviewResponse> previewAudience(@RequestBody com.chatcrmlite.backend.dto.AudiencePreviewRequest req) {
        return ResponseEntity.ok(customEmailService.previewAudience(me(), req));
    }

    @PostMapping("/draft")
    public ResponseEntity<CustomEmailDTO> saveDraft(@Valid @RequestBody CustomEmailRequest req) {
        return ResponseEntity.ok(customEmailService.saveDraft(me(), req));
    }

    @GetMapping
    public ResponseEntity<Page<CustomEmailDTO>> history(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(
                customEmailService.getHistory(
                        me(),
                        PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))
                )
        );
    }

    @GetMapping("/{id}")
    public ResponseEntity<CustomEmailDTO> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(customEmailService.getById(id, me()));
    }

    @PostMapping("/{id}/resend")
    public ResponseEntity<CustomEmailDTO> resend(@PathVariable UUID id) {
        CustomEmailDTO existing = customEmailService.getById(id, me());
        CustomEmailRequest req = new CustomEmailRequest();
        req.setSubject(existing.getSubject());
        req.setBody(existing.getBody());
        req.setCtaLabel(existing.getCtaLabel());
        req.setCtaUrl(existing.getCtaUrl());
        req.setRecipientMode(existing.getRecipientMode());
        req.setTagsFilter(existing.getTagsFilter());
        return ResponseEntity.ok(customEmailService.scheduleOrExecuteCampaign(null, req, me()));
    }

    @GetMapping("/{id}/replies")
    public ResponseEntity<java.util.List<com.chatcrmlite.backend.dto.email.EmailInboundMessageDTO>> getCampaignReplies(@PathVariable UUID id) {
        return ResponseEntity.ok(customEmailService.getCampaignInboundReplies(id, me()));
    }

    @PostMapping("/{id}/simulate-reply")
    public ResponseEntity<com.chatcrmlite.backend.dto.email.EmailInboundMessageDTO> simulateReply(
            @PathVariable UUID id,
            @RequestBody Map<String, String> payload) {
        com.chatcrmlite.backend.models.CustomEmail campaign = customEmailService.findOwnedCampaign(id, me());
        String fromEmail = payload.get("fromEmail");
        String textBody = payload.get("textBody");
        String subject = payload.getOrDefault("subject", "Re: " + campaign.getSubject());
        if (fromEmail == null || fromEmail.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        com.chatcrmlite.backend.dto.email.InboundEmailDTO dto = com.chatcrmlite.backend.dto.email.InboundEmailDTO.builder()
                .provider("simulated")
                .providerMessageId("sim-" + UUID.randomUUID())
                .fromEmail(fromEmail.trim())
                .toEmail("campaign-reply@yourcrm.com")
                .subject(subject)
                .textBody(textBody != null ? textBody : "Simulated test reply from email dashboard")
                .receivedAt(java.time.Instant.now())
                .build();

        com.chatcrmlite.backend.models.email.EmailInboundMessage message = inboundReplyService.processInboundReply(dto);

        com.chatcrmlite.backend.dto.email.EmailInboundMessageDTO resultDto = new com.chatcrmlite.backend.dto.email.EmailInboundMessageDTO();
        resultDto.setId(message.getId());
        resultDto.setCampaignId(message.getCampaignId());
        resultDto.setFromEmail(message.getFromEmail());
        resultDto.setSubject(message.getSubject());
        resultDto.setTextBody(message.getTextBody());
        resultDto.setReplySnippet(message.getReplySnippet());
        resultDto.setSentiment(message.getSentiment());
        resultDto.setAttributionStatus(message.getAttributionStatus() != null ? message.getAttributionStatus().name() : null);
        resultDto.setReceivedAt(message.getReceivedAt());
        return ResponseEntity.ok(resultDto);
    }
}
