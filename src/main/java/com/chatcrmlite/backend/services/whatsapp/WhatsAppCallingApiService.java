package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.dto.whatsapp.calling.*;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.models.voice.VoiceTurn;
import com.chatcrmlite.backend.models.whatsapp.WhatsAppCallPermission;
import com.chatcrmlite.backend.models.whatsapp.WhatsAppCallSession;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.WhatsAppCallPermissionRepository;
import com.chatcrmlite.backend.repositories.WhatsAppCallSessionRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.repositories.voice.VoiceSessionRepository;
import com.chatcrmlite.backend.repositories.voice.VoiceTurnRepository;
import com.chatcrmlite.backend.services.storage.CloudinaryStorageService;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppCallingApiService {

    private final WhatsAppCallingAgentService whatsAppCallingAgentService;
    private final OutboundCallPermissionService outboundCallPermissionService;
    private final OutboundSendIdempotencyService idempotencyService;
    private final WhatsAppCallSessionRepository callSessionRepository;
    private final WhatsAppCallPermissionRepository permissionRepository;
    private final WhatsAppConfigRepository whatsappConfigRepository;
    private final ContactRepository contactRepository;
    private final VoiceTurnRepository voiceTurnRepository;
    private final CloudinaryStorageService cloudinaryStorageService;
    private final VoiceSessionRepository voiceSessionRepository;

    @Value("${jwt.secret:crmlite_secure_production_secret_key_whatsapp_calling_2026_super_hardened}")
    private String tokenSigningSecret;

    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "createdAt", "startedAt", "endedAt", "durationSeconds", "state", "direction"
    );

    private static final Pattern PHONE_NUMBER_PATTERN = Pattern.compile("^\\+?[0-9]{7,15}$");

    /**
     * Initiates an outbound WhatsApp voice call under strict tenant isolation.
     */
    @Transactional
    public WhatsAppCallResponse initiateOutboundCall(
            UUID tenantId,
            InitiateWhatsAppCallRequest request,
            String headerIdempotencyKey
    ) {
        if (tenantId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant authentication required");
        }
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Call initiation request body is required");
        }

        // 1. Resolve & validate tenant phone number
        String phoneNumberId = resolveTenantPhoneNumberId(tenantId, request.getPhoneNumberId());

        // 2. Resolve & validate recipient WhatsApp ID
        String toWaId = resolveRecipientWaId(tenantId, request);

        // 3. Handle Idempotency
        String idempotencyKey = (headerIdempotencyKey != null && !headerIdempotencyKey.isBlank())
                ? headerIdempotencyKey.trim()
                : (request.getIdempotencyKey() != null && !request.getIdempotencyKey().isBlank()
                ? request.getIdempotencyKey().trim()
                : null);

        String redisIdempotencyKey = null;
        String executionId = null;

        if (idempotencyKey != null) {
            redisIdempotencyKey = "outbound:call:" + tenantId + ":" + idempotencyKey;
            String payloadHash = idempotencyService.computePayloadHash(phoneNumberId + ":" + toWaId);
            executionId = UUID.randomUUID().toString();

            OutboundSendIdempotencyService.ClaimResult claim =
                    idempotencyService.claimOrCheck(redisIdempotencyKey, payloadHash, executionId);

            if (claim.status() == OutboundSendIdempotencyService.ClaimStatus.CONFLICT) {
                log.warn("⚠️ [WhatsAppCallingApi] Idempotency conflict for key={}", redisIdempotencyKey);
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key reused with different payload parameters");
            }

            if (claim.status() == OutboundSendIdempotencyService.ClaimStatus.IN_PROGRESS) {
                log.info("⏳ [WhatsAppCallingApi] Call initiation in progress for key={}", redisIdempotencyKey);
                throw new ResponseStatusException(HttpStatus.ACCEPTED, "Call initiation request is already in progress");
            }

            if (claim.status() == OutboundSendIdempotencyService.ClaimStatus.SUCCEEDED) {
                String existingCallId = claim.waMessageId() != null ? claim.waMessageId() : claim.crmMessageId();
                log.info("ℹ️ [WhatsAppCallingApi] Returning existing call for idempotency key={} callId={}",
                        redisIdempotencyKey, existingCallId);

                WhatsAppCallSession existing = callSessionRepository.findByTenantIdAndCallId(tenantId, existingCallId)
                        .orElse(null);

                if (existing != null) {
                    return toCallResponse(existing);
                }
            }
        }

        // 4. Delegate to WhatsAppCallingAgentService
        String callId;
        try {
            callId = whatsAppCallingAgentService.initiateOutboundCall(
                    tenantId,
                    phoneNumberId,
                    toWaId,
                    request.getBizOpaqueCallbackData()
            );

            if (redisIdempotencyKey != null) {
                idempotencyService.markSucceeded(redisIdempotencyKey, callId);
            }
        } catch (IllegalStateException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Call initiation failed";
            log.warn("⛔ [WhatsAppCallingApi] Business policy rejection for tenantId={}: {}", tenantId, msg);

            if (redisIdempotencyKey != null && executionId != null) {
                idempotencyService.markFailed(redisIdempotencyKey, executionId, msg);
            }

            if (msg.contains("Outbound call not permitted")) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, msg);
            } else if (msg.contains("Concurrency limit reached")) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, msg);
            } else if (msg.contains("WhatsAppConfig missing")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
            } else if (msg.contains("Failed to initiate outbound call with Meta")) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, msg);
            } else {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
            }
        } catch (Exception e) {
            log.error("❌ [WhatsAppCallingApi] Unexpected error during call initiation: {}", e.getMessage(), e);
            if (redisIdempotencyKey != null && executionId != null) {
                idempotencyService.markFailed(redisIdempotencyKey, executionId, e.getMessage());
            }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to initiate outbound call");
        }

        // 5. Fetch and return persisted session
        WhatsAppCallSession createdSession = callSessionRepository.findByTenantIdAndCallId(tenantId, callId)
                .orElse(null);

        if (createdSession != null) {
            return toCallResponse(createdSession);
        }

        return WhatsAppCallResponse.builder()
                .callId(callId)
                .status("OFFER_SENT")
                .direction("BUSINESS_INITIATED")
                .phoneNumberId(phoneNumberId)
                .toWaId(toWaId)
                .createdAt(Instant.now())
                .build();
    }

    /**
     * Lists calls for the authenticated tenant with pagination, sorting, and filtering.
     */
    @Transactional(readOnly = true)
    public Page<WhatsAppCallSummaryDTO> listCalls(
            UUID tenantId,
            int page,
            int size,
            String sortProperty,
            String sortDirection,
            String status,
            String direction,
            String phone,
            UUID contactId,
            Instant from,
            Instant to
    ) {
        if (tenantId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant authentication required");
        }

        int sanitizedPage = Math.max(0, page);
        int sanitizedSize = Math.min(100, Math.max(1, size));

        String validSortProp = (sortProperty != null && ALLOWED_SORT_FIELDS.contains(sortProperty))
                ? sortProperty
                : "createdAt";

        Sort.Direction validDir = "ASC".equalsIgnoreCase(sortDirection)
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;

        Pageable pageable = PageRequest.of(sanitizedPage, sanitizedSize, Sort.by(validDir, validSortProp));

        // If contactId is provided, resolve contact phone within tenant
        String resolvedPhone = phone;
        if (contactId != null) {
            Contact contact = contactRepository.findById(contactId).orElse(null);
            if (contact != null && contact.getTenant() != null && tenantId.equals(contact.getTenant().getId())) {
                resolvedPhone = contact.getWaId();
            } else {
                // If contact does not belong to tenant, return empty page without leaking info
                return new PageImpl<>(Collections.emptyList(), pageable, 0);
            }
        }

        final String filterPhone = resolvedPhone != null ? resolvedPhone.replaceAll("[^0-9]", "") : null;

        Specification<WhatsAppCallSession> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenant").get("id"), tenantId));

            if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
                predicates.add(cb.equal(root.get("state"), status.trim()));
            }

            if (direction != null && !direction.isBlank() && !"ALL".equalsIgnoreCase(direction)) {
                predicates.add(cb.equal(root.get("direction"), direction.trim()));
            }

            if (filterPhone != null && !filterPhone.isBlank()) {
                predicates.add(cb.or(
                        cb.equal(root.get("toWaId"), filterPhone),
                        cb.equal(root.get("fromWaId"), filterPhone)
                ));
            }

            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }

            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<WhatsAppCallSession> pageResult = callSessionRepository.findAll(spec, pageable);
        return pageResult.map(this::toSummaryDTO);
    }

    /**
     * Retrieves detailed call session metadata for the authenticated tenant.
     */
    @Transactional(readOnly = true)
    public WhatsAppCallDetailDTO getCallDetails(UUID tenantId, String callId) {
        if (tenantId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant authentication required");
        }
        if (callId == null || callId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Call ID is required");
        }

        WhatsAppCallSession session = callSessionRepository.findByTenantIdAndCallId(tenantId, callId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Call not found"));

        WhatsAppCallDetailDTO dto = toDetailDTO(session);

        // Enrich with contact name if known within tenant
        String targetPhone = "BUSINESS_INITIATED".equalsIgnoreCase(session.getDirection())
                ? session.getToWaId()
                : session.getFromWaId();

        if (targetPhone != null && !targetPhone.isBlank()) {
            contactRepository.findByTenantIdAndWaId(tenantId, targetPhone)
                    .ifPresent(contact -> {
                        dto.setContactId(contact.getId());
                        dto.setContactName(contact.getName());
                    });
        }

        return dto;
    }

    /**
     * Retrieves conversation turns associated with a call session.
     */
    @Transactional(readOnly = true)
    public Page<WhatsAppCallTurnDTO> getCallTurns(UUID tenantId, String callId, int page, int size) {
        if (tenantId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant authentication required");
        }
        if (callId == null || callId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Call ID is required");
        }

        // Verify call session belongs to the tenant
        WhatsAppCallSession session = callSessionRepository.findByTenantIdAndCallId(tenantId, callId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Call not found"));

        int sanitizedPage = Math.max(0, page);
        int sanitizedSize = Math.min(100, Math.max(1, size));
        Pageable pageable = PageRequest.of(sanitizedPage, sanitizedSize);

        if (session.getAiSessionId() == null) {
            // Transcripts are maintained in-memory during streaming in Phase 1;
            // DB persistence is linked via aiSessionId when configured.
            return new PageImpl<>(Collections.emptyList(), pageable, 0);
        }

        Page<VoiceTurn> turnsPage = voiceTurnRepository.findBySessionIdOrderByTurnNumberAsc(
                session.getAiSessionId(), pageable
        );

        return turnsPage.map(this::toTurnDTO);
    }

    /**
     * Terminates an active call session on demand under strict tenant isolation.
     */
    @Transactional
    public void terminateCall(UUID tenantId, String callId) {
        if (tenantId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant authentication required");
        }
        if (callId == null || callId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Call ID is required");
        }
        callSessionRepository.findByTenantIdAndCallId(tenantId, callId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Call not found: " + callId));

        whatsAppCallingAgentService.terminateActiveCall(tenantId, callId.trim());
    }

    /**
     * Retrieves recording metadata for a specific WhatsApp call session with strict tenant isolation.
     */
    @Transactional
    public WhatsAppCallRecordingDTO getCallRecording(UUID tenantId, String callId) {
        if (tenantId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant authentication required");
        }
        if (callId == null || callId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Call ID is required");
        }

        WhatsAppCallSession session = callSessionRepository.findByTenantIdAndCallId(tenantId, callId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Call not found: " + callId));

        // Auto-resolve: if recording metadata is missing, check linked voice session or existing asset
        if ((session.getRecordingPublicId() == null || session.getRecordingPublicId().isBlank())
                && (session.getRecordingUrl() == null || session.getRecordingUrl().isBlank())) {
            resolveAndLinkRecordingIfAvailable(session);
        }

        boolean hasRecording = (session.getRecordingPublicId() != null && !session.getRecordingPublicId().isBlank())
                || (session.getRecordingUrl() != null && !session.getRecordingUrl().isBlank());

        String status = session.getRecordingStatus();
        if (hasRecording) {
            status = "AVAILABLE";
            if (!"AVAILABLE".equalsIgnoreCase(session.getRecordingStatus())) {
                session.setRecordingStatus("AVAILABLE");
                callSessionRepository.save(session);
            }
        } else if ("ACTIVE".equalsIgnoreCase(session.getState())
                || "MEDIA_CONNECTED".equalsIgnoreCase(session.getState())
                || "MEDIA_NEGOTIATING".equalsIgnoreCase(session.getState())) {
            status = "PROCESSING";
        } else if (status == null || status.isBlank()) {
            status = "NOT_AVAILABLE";
        }

        String playbackToken = hasRecording ? generatePlaybackToken(tenantId, session.getCallId(), 7200) : null;
        String playbackUrl = hasRecording
                ? "/api/v1/voice/whatsapp/calls/" + urlEncode(session.getCallId()) + "/recording/stream?token=" + playbackToken
                : null;
        String downloadUrl = hasRecording
                ? "/api/v1/voice/whatsapp/calls/" + urlEncode(session.getCallId()) + "/recording/download?token=" + playbackToken
                : null;

        String signedDirectUrl = null;
        if (hasRecording && cloudinaryStorageService != null && cloudinaryStorageService.isConfigured()) {
            if (session.getRecordingPublicId() != null && !session.getRecordingPublicId().isBlank()) {
                signedDirectUrl = cloudinaryStorageService.getSignedAudioPlaybackUrl(session.getRecordingPublicId());
            } else if (session.getRecordingUrl() != null && session.getRecordingUrl().contains("cloudinary.com")) {
                signedDirectUrl = session.getRecordingUrl();
            }
        }

        return WhatsAppCallRecordingDTO.builder()
                .callId(session.getCallId())
                .available(hasRecording)
                .status(status)
                .publicId(session.getRecordingPublicId())
                .format(session.getRecordingFormat() != null ? session.getRecordingFormat() : "mp3")
                .durationSeconds(session.getRecordingDurationSeconds() != null ? session.getRecordingDurationSeconds() : session.getDurationSeconds())
                .sizeBytes(session.getRecordingSizeBytes())
                .playbackUrl(playbackUrl)
                .signedDirectUrl(signedDirectUrl)
                .downloadUrl(downloadUrl)
                .errorMessage("NOT_AVAILABLE".equalsIgnoreCase(status)
                        ? (session.getMetaErrorMessage() != null ? session.getMetaErrorMessage() : "No recording available for this call")
                        : null)
                .createdAt(session.getCreatedAt())
                .build();
    }

    /**
     * Uploads a call recording MP3 for a specific call session.
     */
    @Transactional
    public WhatsAppCallRecordingDTO uploadCallRecording(UUID tenantId, String callId, MultipartFile file) {
        if (tenantId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant authentication required");
        }
        if (callId == null || callId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Call ID is required");
        }
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Recording audio file is required");
        }

        WhatsAppCallSession session = callSessionRepository.findByTenantIdAndCallId(tenantId, callId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Call not found: " + callId));

        try {
            byte[] bytes = file.getBytes();
            CloudinaryStorageService.AudioUploadResult uploadResult =
                    cloudinaryStorageService.uploadCallRecording(tenantId, session.getCallId(), bytes);

            session.setRecordingUrl(uploadResult.secureUrl());
            session.setRecordingPublicId(uploadResult.publicId());
            session.setRecordingFormat(uploadResult.format());
            session.setRecordingSizeBytes(uploadResult.sizeBytes());
            if (uploadResult.durationSeconds() != null && uploadResult.durationSeconds() > 0) {
                session.setRecordingDurationSeconds(uploadResult.durationSeconds());
            } else if (session.getDurationSeconds() != null && session.getDurationSeconds() > 0) {
                session.setRecordingDurationSeconds(session.getDurationSeconds());
            }
            session.setRecordingStatus("AVAILABLE");
            callSessionRepository.save(session);

            if (session.getAiSessionId() != null && voiceSessionRepository != null) {
                voiceSessionRepository.findById(session.getAiSessionId()).ifPresent(vs -> {
                    vs.setRecordingUrl(uploadResult.secureUrl());
                    voiceSessionRepository.save(vs);
                });
            }

            return getCallRecording(tenantId, session.getCallId());
        } catch (Exception e) {
            log.error("Failed to upload call recording to Cloudinary for callId={}: {}", callId, e.getMessage(), e);
            session.setRecordingStatus("FAILED");
            callSessionRepository.save(session);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Recording upload failed: " + e.getMessage());
        }
    }

    /**
     * Uploads call recording raw audio bytes directly to Cloudinary and persists metadata.
     */
    @Transactional
    public WhatsAppCallRecordingDTO uploadCallRecordingBytes(UUID tenantId, String callId, byte[] audioBytes, String format) {
        if (tenantId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant authentication required");
        }
        WhatsAppCallSession session = callSessionRepository.findByTenantIdAndCallId(tenantId, callId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Call not found: " + callId));

        try {
            CloudinaryStorageService.AudioUploadResult uploadResult =
                    cloudinaryStorageService.uploadCallRecording(tenantId, session.getCallId(), audioBytes);

            session.setRecordingUrl(uploadResult.secureUrl());
            session.setRecordingPublicId(uploadResult.publicId());
            session.setRecordingFormat(uploadResult.format() != null ? uploadResult.format() : (format != null ? format : "mp3"));
            session.setRecordingSizeBytes(uploadResult.sizeBytes());
            if (uploadResult.durationSeconds() != null && uploadResult.durationSeconds() > 0) {
                session.setRecordingDurationSeconds(uploadResult.durationSeconds());
            } else if (session.getDurationSeconds() != null && session.getDurationSeconds() > 0) {
                session.setRecordingDurationSeconds(session.getDurationSeconds());
            }
            session.setRecordingStatus("AVAILABLE");
            callSessionRepository.save(session);

            if (session.getAiSessionId() != null && voiceSessionRepository != null) {
                voiceSessionRepository.findById(session.getAiSessionId()).ifPresent(vs -> {
                    vs.setRecordingUrl(uploadResult.secureUrl());
                    voiceSessionRepository.save(vs);
                });
            }

            return getCallRecording(tenantId, session.getCallId());
        } catch (Exception e) {
            log.error("Failed to upload call recording bytes to Cloudinary for callId={}: {}", callId, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Recording upload failed: " + e.getMessage());
        }
    }

    /**
     * Retries a failed call recording upload using cached audio if available.
     */
    @Transactional
    public WhatsAppCallRecordingDTO retryCallRecording(UUID tenantId, String callId) {
        if (tenantId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant authentication required");
        }
        WhatsAppCallSession session = callSessionRepository.findByTenantIdAndCallId(tenantId, callId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Call not found: " + callId));

        if ("AVAILABLE".equalsIgnoreCase(session.getRecordingStatus()) && session.getRecordingUrl() != null) {
            return getCallRecording(tenantId, callId);
        }

        byte[] cachedAudio = whatsAppCallingAgentService.getFailedRecordingAudio(tenantId, callId.trim());
        if (cachedAudio == null || cachedAudio.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No cached recording audio available for automatic retry. Please use the /upload endpoint to upload the audio file directly.");
        }

        session.setRecordingStatus("PROCESSING");
        callSessionRepository.save(session);

        whatsAppCallingAgentService.retryUploadAsync(session, cachedAudio);
        return getCallRecording(tenantId, callId);
    }

    /**
     * Saves or updates call recording metadata idempotently without creating duplicate records.
     */
    @Transactional
    public WhatsAppCallRecordingDTO saveOrUpdateCallRecording(
            UUID tenantId,
            String callId,
            String publicId,
            String url,
            String format,
            Integer durationSeconds,
            Long sizeBytes,
            String status
    ) {
        if (tenantId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant authentication required");
        }
        if (callId == null || callId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Call ID is required");
        }

        WhatsAppCallSession session = callSessionRepository.findByTenantIdAndCallId(tenantId, callId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Call not found: " + callId));

        if (publicId != null && !publicId.isBlank()) {
            session.setRecordingPublicId(publicId.trim());
        }
        if (url != null && !url.isBlank()) {
            session.setRecordingUrl(url.trim());
        }
        if (format != null && !format.isBlank()) {
            session.setRecordingFormat(format.trim());
        }
        if (durationSeconds != null && durationSeconds > 0) {
            session.setRecordingDurationSeconds(durationSeconds);
        }
        if (sizeBytes != null && sizeBytes > 0) {
            session.setRecordingSizeBytes(sizeBytes);
        }
        session.setRecordingStatus(status != null && !status.isBlank() ? status.trim() : "AVAILABLE");
        callSessionRepository.save(session);

        if (session.getAiSessionId() != null && voiceSessionRepository != null) {
            voiceSessionRepository.findById(session.getAiSessionId()).ifPresent(vs -> {
                if (session.getRecordingUrl() != null) {
                    vs.setRecordingUrl(session.getRecordingUrl());
                    voiceSessionRepository.save(vs);
                }
            });
        }

        return getCallRecording(tenantId, callId);
    }

    /**
     * Streams call recording audio with HTTP 206 Partial Content (Range requests) for browser audio seeking.
     */
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> streamCallRecording(
            UUID authenticatedTenantId,
            String callId,
            String token,
            String rangeHeader
    ) {
        UUID effectiveTenantId = resolveTenantFromAuthOrToken(authenticatedTenantId, callId, token);
        WhatsAppCallSession session = callSessionRepository.findByTenantIdAndCallId(effectiveTenantId, callId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Call not found: " + callId));

        byte[] audioBytes = fetchAudioBytesForSession(session);
        if (audioBytes == null || audioBytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Audio recording file not found or empty");
        }

        int totalLength = audioBytes.length;

        // HTTP 206 Partial Content parsing for browser scrubber seeking
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            String[] parts = rangeHeader.substring(6).split("-");
            try {
                int start = Integer.parseInt(parts[0].trim());
                int end = parts.length > 1 && !parts[1].trim().isEmpty()
                        ? Integer.parseInt(parts[1].trim())
                        : totalLength - 1;

                if (start >= totalLength) {
                    return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                            .header(HttpHeaders.CONTENT_RANGE, "bytes */" + totalLength)
                            .build();
                }
                end = Math.min(end, totalLength - 1);
                int contentLength = end - start + 1;

                byte[] chunk = Arrays.copyOfRange(audioBytes, start, end + 1);

                return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                        .header(HttpHeaders.CONTENT_TYPE, "audio/mpeg")
                        .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                        .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + totalLength)
                        .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(contentLength))
                        .body(chunk);
            } catch (NumberFormatException e) {
                log.warn("Invalid Range header: {}", rangeHeader);
            }
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "audio/mpeg")
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(totalLength))
                .body(audioBytes);
    }

    /**
     * Downloads call recording audio with Content-Disposition attachment.
     */
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> downloadCallRecording(
            UUID authenticatedTenantId,
            String callId,
            String token
    ) {
        UUID effectiveTenantId = resolveTenantFromAuthOrToken(authenticatedTenantId, callId, token);
        WhatsAppCallSession session = callSessionRepository.findByTenantIdAndCallId(effectiveTenantId, callId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Call not found: " + callId));

        byte[] audioBytes = fetchAudioBytesForSession(session);
        if (audioBytes == null || audioBytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Audio recording file not found or empty");
        }

        String safeName = "whatsapp_call_" + session.getCallId().replaceAll("[^a-zA-Z0-9._-]", "_") + ".mp3";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "audio/mpeg")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safeName + "\"")
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(audioBytes.length))
                .body(audioBytes);
    }

    /**
     * Calculates aggregated calling analytics for the authenticated tenant.
     */
    @Transactional(readOnly = true)
    public WhatsAppCallingAnalyticsDTO getAnalytics(
            UUID tenantId,
            String phoneNumberId,
            Instant from,
            Instant to
    ) {
        if (tenantId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant authentication required");
        }

        String filterPhoneId = null;
        if (phoneNumberId != null && !phoneNumberId.isBlank()) {
            filterPhoneId = phoneNumberId.trim();
            // Verify this phone number belongs to the tenant
            UUID phoneTenant = whatsappConfigRepository.findTenantIdByPhoneNumberId(filterPhoneId).orElse(null);
            if (!tenantId.equals(phoneTenant)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Phone number configuration not found");
            }
        }

        // 1. Total Calls
        long totalCalls = callSessionRepository.countCallsByTenant(tenantId, filterPhoneId, from, to);

        // 2. Direction breakdown
        long inboundCalls = 0;
        long outboundCalls = 0;
        List<Object[]> directionCounts = callSessionRepository.countCallsByDirection(tenantId, filterPhoneId, from, to);
        for (Object[] row : directionCounts) {
            String dir = (String) row[0];
            Long cnt = (Long) row[1];
            if ("USER_INITIATED".equalsIgnoreCase(dir)) {
                inboundCalls += cnt;
            } else if ("BUSINESS_INITIATED".equalsIgnoreCase(dir)) {
                outboundCalls += cnt;
            }
        }

        // 3. State breakdown
        Map<String, Long> statusBreakdown = new LinkedHashMap<>();
        long failedCalls = 0;
        long rejectedCalls = 0;
        long completedCalls = 0;

        List<Object[]> stateCounts = callSessionRepository.countCallsByState(tenantId, filterPhoneId, from, to);
        for (Object[] row : stateCounts) {
            String state = (String) row[0];
            Long cnt = (Long) row[1];
            if (state != null) {
                statusBreakdown.put(state, cnt);
                if ("FAILED".equalsIgnoreCase(state)) {
                    failedCalls += cnt;
                } else if ("REJECTED".equalsIgnoreCase(state)) {
                    rejectedCalls += cnt;
                } else if ("ENDED".equalsIgnoreCase(state) || "TERMINATED".equalsIgnoreCase(state)) {
                    completedCalls += cnt;
                }
            }
        }

        // 4. Answered calls
        long answeredCalls = callSessionRepository.countAnsweredCalls(tenantId, filterPhoneId, from, to);

        // 5. Termination reasons
        Map<String, Long> terminationReasons = new LinkedHashMap<>();
        List<Object[]> reasonCounts = callSessionRepository.countCallsByTerminationReason(tenantId, filterPhoneId, from, to);
        for (Object[] row : reasonCounts) {
            String reason = (String) row[0];
            Long cnt = (Long) row[1];
            if (reason != null) {
                terminationReasons.put(reason, cnt);
            }
        }

        // 6. Duration stats
        long totalDurationSeconds = 0;
        double averageDurationSeconds = 0.0;
        List<Object[]> durationStats = callSessionRepository.getDurationStats(tenantId, filterPhoneId, from, to);
        if (!durationStats.isEmpty() && durationStats.get(0) != null) {
            Object[] stats = durationStats.get(0);
            Number sum = (Number) stats[0];
            Number avg = (Number) stats[1];
            if (sum != null) totalDurationSeconds = sum.longValue();
            if (avg != null) averageDurationSeconds = Math.round(avg.doubleValue() * 100.0) / 100.0;
        }

        double answerRate = totalCalls > 0
                ? Math.round(((double) answeredCalls / totalCalls) * 10000.0) / 100.0
                : 0.0;

        return WhatsAppCallingAnalyticsDTO.builder()
                .totalCalls(totalCalls)
                .inboundCalls(inboundCalls)
                .outboundCalls(outboundCalls)
                .answeredCalls(answeredCalls)
                .rejectedCalls(rejectedCalls)
                .failedCalls(failedCalls)
                .completedCalls(completedCalls)
                .averageDurationSeconds(averageDurationSeconds)
                .totalDurationSeconds(totalDurationSeconds)
                .answerRate(answerRate)
                .failureCount(failedCalls)
                .terminationReasons(terminationReasons)
                .statusBreakdown(statusBreakdown)
                .from(from)
                .to(to)
                .build();
    }

    /**
     * Sends an interactive Call Permission Request message.
     */
    @Transactional
    public CallPermissionResponseDTO requestPermission(UUID tenantId, RequestCallPermissionRequest request) {
        if (tenantId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant authentication required");
        }
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Permission request body is required");
        }

        String phoneNumberId = resolveTenantPhoneNumberId(tenantId, request.getPhoneNumberId());

        String userWaId;
        if (request.getContactId() != null) {
            Contact contact = contactRepository.findById(request.getContactId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contact not found"));
            if (contact.getTenant() == null || !tenantId.equals(contact.getTenant().getId())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Contact not found");
            }
            userWaId = contact.getWaId();
        } else if (request.getUserWaId() != null && !request.getUserWaId().isBlank()) {
            userWaId = request.getUserWaId().replaceAll("[^0-9]", "");
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Either contactId or userWaId must be provided");
        }

        if (userWaId == null || userWaId.isBlank() || userWaId.length() < 7) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid WhatsApp phone number");
        }

        String messageText = (request.getMessageText() != null && !request.getMessageText().isBlank())
                ? request.getMessageText().trim()
                : "May we call you on WhatsApp to discuss your inquiry?";

        boolean sent = outboundCallPermissionService.requestCallPermission(
                tenantId, phoneNumberId, userWaId, messageText
        );

        if (!sent) {
            log.warn("🛑 [WhatsAppCallingApi] Permission request rejected by rate limits: userWaId={}", userWaId);
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Permission request limit reached (1 request per 24 hours, 2 per 7 days per recipient) or Meta API unavailable"
            );
        }

        WhatsAppCallPermission perm = permissionRepository
                .findByTenantIdAndPhoneNumberIdAndUserWaId(tenantId, phoneNumberId, userWaId)
                .orElse(null);

        boolean isActive = outboundCallPermissionService.isPermissionActive(tenantId, phoneNumberId, userWaId);
        boolean canCall = outboundCallPermissionService.canInitiateOutboundCall(tenantId, phoneNumberId, userWaId);

        if (perm != null) {
            return toPermissionDTO(perm, isActive, canCall);
        }

        return CallPermissionResponseDTO.builder()
                .phoneNumberId(phoneNumberId)
                .userWaId(userWaId)
                .status("PENDING")
                .permissionType("TEMPORARY")
                .isPermanent(false)
                .lastPermissionRequestAt(Instant.now())
                .active(false)
                .canInitiateCall(false)
                .build();
    }

    /**
     * Retrieves outbound call permission status for a contact or phone number.
     */
    @Transactional(readOnly = true)
    public CallPermissionResponseDTO getPermission(UUID tenantId, String contactOrPhone, String phoneNumberId) {
        if (tenantId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant authentication required");
        }
        if (contactOrPhone == null || contactOrPhone.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Contact ID or phone number is required");
        }

        String userWaId;
        try {
            UUID contactId = UUID.fromString(contactOrPhone.trim());
            Contact contact = contactRepository.findById(contactId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contact not found"));
            if (contact.getTenant() == null || !tenantId.equals(contact.getTenant().getId())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Contact not found");
            }
            userWaId = contact.getWaId();
        } catch (IllegalArgumentException e) {
            userWaId = contactOrPhone.replaceAll("[^0-9]", "");
        }

        if (userWaId == null || userWaId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid contact identifier");
        }

        String phoneId = resolveTenantPhoneNumberId(tenantId, phoneNumberId);

        WhatsAppCallPermission perm = permissionRepository
                .findByTenantIdAndPhoneNumberIdAndUserWaId(tenantId, phoneId, userWaId)
                .orElse(null);

        if (perm == null) {
            // Check if permission exists under any phone number for this tenant
            List<WhatsAppCallPermission> list = permissionRepository.findByTenantIdAndUserWaId(tenantId, userWaId);
            if (!list.isEmpty()) {
                perm = list.get(0);
                phoneId = perm.getPhoneNumberId();
            }
        }

        if (perm == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "No call permission record found for " + contactOrPhone
            );
        }

        boolean isActive = outboundCallPermissionService.isPermissionActive(tenantId, phoneId, userWaId);
        boolean canCall = outboundCallPermissionService.canInitiateOutboundCall(tenantId, phoneId, userWaId);

        return toPermissionDTO(perm, isActive, canCall);
    }

    // ──────────────── Private Helpers ────────────────

    private String resolveTenantPhoneNumberId(UUID tenantId, String requestedPhoneId) {
        if (requestedPhoneId != null && !requestedPhoneId.isBlank()) {
            String candidate = requestedPhoneId.trim();
            UUID ownerTenant = whatsappConfigRepository.findTenantIdByPhoneNumberId(candidate).orElse(null);
            if (!tenantId.equals(ownerTenant)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Phone number ID does not belong to the authenticated organization");
            }
            return candidate;
        }

        WhatsAppConfig config = whatsappConfigRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenant has no WhatsApp Business Account configured"));

        String phoneId = config.getPhoneNumberId();
        if (phoneId == null || phoneId.isBlank()) {
            phoneId = config.getEmbeddedPhoneId();
        }

        if (phoneId == null || phoneId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenant has no active WhatsApp Phone Number ID");
        }

        return phoneId.trim();
    }

    private String resolveRecipientWaId(UUID tenantId, InitiateWhatsAppCallRequest request) {
        if (request.getContactId() != null) {
            Contact contact = contactRepository.findById(request.getContactId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contact not found"));

            if (contact.getTenant() == null || !tenantId.equals(contact.getTenant().getId())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Contact not found");
            }

            String waId = contact.getWaId();
            if (waId == null || waId.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Contact does not have a registered WhatsApp number");
            }
            return waId.replaceAll("[^0-9]", "");
        }

        if (request.getToWaId() != null && !request.getToWaId().isBlank()) {
            String raw = request.getToWaId().trim();
            if (!PHONE_NUMBER_PATTERN.matcher(raw).matches()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid phone number format: " + raw);
            }
            return raw.replaceAll("[^0-9]", "");
        }

        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Either contactId or toWaId must be provided");
    }

    private WhatsAppCallResponse toCallResponse(WhatsAppCallSession session) {
        return WhatsAppCallResponse.builder()
                .callId(session.getCallId())
                .status(session.getState())
                .direction(session.getDirection())
                .phoneNumberId(session.getPhoneNumberId())
                .toWaId(session.getToWaId())
                .fromWaId(session.getFromWaId())
                .createdAt(session.getCreatedAt())
                .build();
    }

    private WhatsAppCallSummaryDTO toSummaryDTO(WhatsAppCallSession session) {
        return WhatsAppCallSummaryDTO.builder()
                .id(session.getId())
                .callId(session.getCallId())
                .direction(session.getDirection())
                .fromWaId(session.getFromWaId())
                .toWaId(session.getToWaId())
                .state(session.getState())
                .phoneNumberId(session.getPhoneNumberId())
                .startedAt(session.getStartedAt())
                .connectedAt(session.getConnectedAt())
                .endedAt(session.getEndedAt())
                .durationSeconds(session.getDurationSeconds())
                .terminationReason(session.getTerminationReason())
                .createdAt(session.getCreatedAt())
                .build();
    }

    private WhatsAppCallDetailDTO toDetailDTO(WhatsAppCallSession session) {
        boolean hasRecording = (session.getRecordingPublicId() != null && !session.getRecordingPublicId().isBlank())
                || (session.getRecordingUrl() != null && !session.getRecordingUrl().isBlank());
        String status = session.getRecordingStatus();
        if (hasRecording) {
            status = "AVAILABLE";
        } else if ("ACTIVE".equalsIgnoreCase(session.getState())
                || "MEDIA_CONNECTED".equalsIgnoreCase(session.getState())
                || "MEDIA_NEGOTIATING".equalsIgnoreCase(session.getState())) {
            status = "PROCESSING";
        } else if (status == null || status.isBlank()) {
            status = "NOT_AVAILABLE";
        }

        String playbackToken = hasRecording && session.getTenantId() != null
                ? generatePlaybackToken(session.getTenantId(), session.getCallId(), 7200) : null;
        String playbackUrl = hasRecording
                ? "/api/v1/voice/whatsapp/calls/" + urlEncode(session.getCallId()) + "/recording/stream?token=" + playbackToken
                : null;
        String downloadUrl = hasRecording
                ? "/api/v1/voice/whatsapp/calls/" + urlEncode(session.getCallId()) + "/recording/download?token=" + playbackToken
                : null;

        return WhatsAppCallDetailDTO.builder()
                .id(session.getId())
                .callId(session.getCallId())
                .direction(session.getDirection())
                .fromWaId(session.getFromWaId())
                .toWaId(session.getToWaId())
                .state(session.getState())
                .phoneNumberId(session.getPhoneNumberId())
                .wabaId(session.getWabaId())
                .signalingMode(session.getSignalingMode())
                .mediaMode(session.getMediaMode())
                .codec(session.getCodec())
                .startedAt(session.getStartedAt())
                .acceptedAt(session.getAcceptedAt())
                .connectedAt(session.getConnectedAt())
                .endedAt(session.getEndedAt())
                .durationSeconds(session.getDurationSeconds())
                .terminationSource(session.getTerminationSource())
                .terminationReason(session.getTerminationReason())
                .failureStage(session.getFailureStage())
                .metaErrorCode(session.getMetaErrorCode())
                .metaErrorTitle(session.getMetaErrorTitle())
                .metaErrorMessage(session.getMetaErrorMessage())
                .aiSessionId(session.getAiSessionId())
                .recordingAvailable(hasRecording)
                .recordingStatus(status)
                .recordingPublicId(session.getRecordingPublicId())
                .recordingFormat(session.getRecordingFormat() != null ? session.getRecordingFormat() : "mp3")
                .recordingDurationSeconds(session.getRecordingDurationSeconds() != null ? session.getRecordingDurationSeconds() : session.getDurationSeconds())
                .recordingSizeBytes(session.getRecordingSizeBytes())
                .recordingPlaybackUrl(playbackUrl)
                .recordingDownloadUrl(downloadUrl)
                .createdAt(session.getCreatedAt())
                .updatedAt(session.getUpdatedAt())
                .build();
    }

    private WhatsAppCallTurnDTO toTurnDTO(VoiceTurn turn) {
        return WhatsAppCallTurnDTO.builder()
                .turnId(turn.getId())
                .turnNumber(turn.getTurnNumber())
                .speaker(turn.getUserTranscript() != null && !turn.getUserTranscript().isBlank() ? "USER" : "ASSISTANT")
                .transcript(turn.getUserTranscript() != null ? turn.getUserTranscript() : turn.getBotResponseText())
                .timestamp(turn.getCreatedAt())
                .audioDurationSeconds(turn.getAudioDurationSeconds())
                .sttLatencyMs(turn.getSttLatencyMs())
                .llmLatencyMs(turn.getLlmLatencyMs())
                .ttsLatencyMs(turn.getTtsLatencyMs())
                .ttfaMs(turn.getTtfaMs())
                .wasInterrupted(turn.getWasInterrupted())
                .detectedLanguage(turn.getDetectedLanguage())
                .build();
    }

    private CallPermissionResponseDTO toPermissionDTO(
            WhatsAppCallPermission perm,
            boolean isActive,
            boolean canCall
    ) {
        return CallPermissionResponseDTO.builder()
                .id(perm.getId())
                .phoneNumberId(perm.getPhoneNumberId())
                .userWaId(perm.getUserWaId())
                .status(perm.getStatus())
                .permissionType(perm.getPermissionType())
                .isPermanent(perm.getIsPermanent())
                .grantedAt(perm.getGrantedAt())
                .expiresAt(perm.getExpiresAt())
                .revokedAt(perm.getRevokedAt())
                .lastPermissionRequestAt(perm.getLastPermissionRequestAt())
                .active(isActive)
                .canInitiateCall(canCall)
                .build();
    }

    private byte[] fetchAudioBytesForSession(WhatsAppCallSession session) {
        if (cloudinaryStorageService == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Storage service unconfigured");
        }

        // 1. Try via signed URL from public ID
        if (session.getRecordingPublicId() != null && !session.getRecordingPublicId().isBlank()) {
            try {
                String signedUrl = cloudinaryStorageService.getSignedAudioPlaybackUrl(session.getRecordingPublicId());
                if (signedUrl != null && !signedUrl.isBlank()) {
                    return cloudinaryStorageService.downloadFile(signedUrl);
                }
            } catch (Exception e) {
                log.warn("Could not download via signed URL for publicId={}: {}", session.getRecordingPublicId(), e.getMessage());
            }
        }

        // 2. Try direct download via recording URL
        if (session.getRecordingUrl() != null && !session.getRecordingUrl().isBlank()) {
            try {
                return cloudinaryStorageService.downloadFile(session.getRecordingUrl());
            } catch (Exception e) {
                log.warn("Could not download via recordingUrl for callId={}: {}", session.getCallId(), e.getMessage());
            }
        }

        return null;
    }

    private void resolveAndLinkRecordingIfAvailable(WhatsAppCallSession session) {
        if (session.getAiSessionId() != null && voiceSessionRepository != null) {
            voiceSessionRepository.findById(session.getAiSessionId()).ifPresent(vs -> {
                if (vs.getRecordingUrl() != null && !vs.getRecordingUrl().isBlank()) {
                    session.setRecordingUrl(vs.getRecordingUrl());
                    String publicId = extractCloudinaryPublicId(vs.getRecordingUrl());
                    if (publicId != null) {
                        session.setRecordingPublicId(publicId);
                    }
                    session.setRecordingStatus("AVAILABLE");
                    if (session.getRecordingDurationSeconds() == null) {
                        session.setRecordingDurationSeconds(session.getDurationSeconds());
                    }
                    callSessionRepository.save(session);
                }
            });
        }
    }

    private String extractCloudinaryPublicId(String url) {
        if (url == null || !url.contains("/upload/")) return null;
        try {
            int uploadIdx = url.indexOf("/upload/");
            String sub = url.substring(uploadIdx + 8);
            if (sub.startsWith("s--")) {
                int nextSlash = sub.indexOf('/');
                if (nextSlash != -1) sub = sub.substring(nextSlash + 1);
            }
            if (sub.matches("^v\\d+/.*")) {
                sub = sub.substring(sub.indexOf('/') + 1);
            }
            int dotIdx = sub.lastIndexOf('.');
            return dotIdx != -1 ? sub.substring(0, dotIdx) : sub;
        } catch (Exception e) {
            return null;
        }
    }

    public String generatePlaybackToken(UUID tenantId, String callId, int validitySeconds) {
        long expiry = System.currentTimeMillis() + (validitySeconds * 1000L);
        String payload = tenantId.toString() + "|" + callId + "|" + expiry;
        String hmac = computeHmacSha256(payload, tokenSigningSecret != null ? tokenSigningSecret : "default_secret");
        String raw = payload + "|" + hmac;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public UUID resolveTenantFromAuthOrToken(UUID authenticatedTenantId, String callId, String token) {
        if (authenticatedTenantId != null) {
            return authenticatedTenantId;
        }
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication token or query token required");
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(token.trim());
            String raw = new String(decoded, StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|");
            if (parts.length != 4) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid token structure");
            }
            String tenantStr = parts[0];
            String tokenCallId = parts[1];
            long expiry = Long.parseLong(parts[2]);
            String hmac = parts[3];

            if (System.currentTimeMillis() > expiry) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Recording playback token has expired");
            }
            if (!tokenCallId.equals(callId != null ? callId.trim() : "")) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Token call mismatch");
            }

            String expectedPayload = tenantStr + "|" + tokenCallId + "|" + expiry;
            String expectedHmac = computeHmacSha256(expectedPayload, tokenSigningSecret != null ? tokenSigningSecret : "default_secret");

            if (!MessageDigest.isEqual(hmac.getBytes(StandardCharsets.UTF_8), expectedHmac.getBytes(StandardCharsets.UTF_8))) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid recording playback signature");
            }

            return UUID.fromString(tenantStr);
        } catch (ResponseStatusException rse) {
            throw rse;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Playback token validation failed: " + e.getMessage());
        }
    }

    private String computeHmacSha256(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKey);
            byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hmacBytes);
        } catch (Exception e) {
            throw new RuntimeException("HMAC computation error", e);
        }
    }

    private String urlEncode(String val) {
        if (val == null) return "";
        return URLEncoder.encode(val, StandardCharsets.UTF_8);
    }
}
