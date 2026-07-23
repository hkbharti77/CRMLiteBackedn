package com.chatcrmlite.backend.services.whatsapp.campaign;

import com.chatcrmlite.backend.clients.WhatsAppClient;
import com.chatcrmlite.backend.models.*;
import com.chatcrmlite.backend.repositories.*;
import com.chatcrmlite.backend.services.DistributedSchedulerService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class DripSequenceService {

    private final DripSequenceRepository dripSequenceRepository;
    private final DripSequenceStepRepository dripSequenceStepRepository;
    private final DripParticipantRepository dripParticipantRepository;
    private final WhatsAppConfigRepository whatsAppConfigRepository;
    private final WhatsAppClient whatsappClient;
    private final PersonalizationEngine personalizationEngine;
    private final ObjectMapper objectMapper;

    /**
     * Enrolls a new contact into matching active drip sequences.
     */
    @Transactional
    public void enrollContact(Contact contact, Lead lead, DripSequence.TriggerEvent event) {
        List<DripSequence> activeSequences = dripSequenceRepository.findByTriggerEventAndActiveTrue(event);
        for (DripSequence sequence : activeSequences) {
            Optional<DripParticipant> existing = dripParticipantRepository.findBySequenceAndContact(sequence, contact);
            if (existing.isPresent()) {
                continue; // Already enrolled
            }

            Optional<DripSequenceStep> firstStepOpt = dripSequenceStepRepository.findBySequenceAndStepOrder(sequence, 1);
            if (firstStepOpt.isEmpty()) {
                continue;
            }

            DripSequenceStep firstStep = firstStepOpt.get();
            LocalDateTime nextRun = LocalDateTime.now().plusHours(firstStep.getDelayHours() != null ? firstStep.getDelayHours() : 0);

            DripParticipant participant = DripParticipant.builder()
                    .sequence(sequence)
                    .contact(contact)
                    .lead(lead)
                    .currentStepOrder(1)
                    .nextRunAt(nextRun)
                    .status(DripParticipant.ParticipantStatus.ACTIVE)
                    .build();
            participant.setTenant(sequence.getTenant());
            dripParticipantRepository.save(participant);

            log.info("[DripSequence] Enrolled contactId={} in dripSequenceId={} nextRunAt={}", contact.getId(), sequence.getId(), nextRun);
        }
    }

    /**
     * Scheduled job evaluating active drip participants due for step execution.
     */
    @Scheduled(fixedDelay = 60000) // Runs every minute
    @Transactional
    public void processDueDripSteps() {
        List<DripParticipant> dueParticipants = dripParticipantRepository.findByStatusAndNextRunAtBefore(
                DripParticipant.ParticipantStatus.ACTIVE,
                LocalDateTime.now()
        );

        for (DripParticipant participant : dueParticipants) {
            processParticipantStep(participant);
        }
    }

    private void processParticipantStep(DripParticipant participant) {
        DripSequence sequence = participant.getSequence();
        Contact contact = participant.getContact();

        Optional<DripSequenceStep> stepOpt = dripSequenceStepRepository.findBySequenceAndStepOrder(sequence, participant.getCurrentStepOrder());
        if (stepOpt.isEmpty()) {
            participant.setStatus(DripParticipant.ParticipantStatus.COMPLETED);
            participant.setCompletedAt(LocalDateTime.now());
            dripParticipantRepository.save(participant);
            return;
        }

        DripSequenceStep step = stepOpt.get();

        // Check Smart Exit Conditions
        if (Boolean.TRUE.equals(step.getExitIfReplied())) {
            // Check if contact has replied since enrollment
            if (contact.getLastInteractiveAt() != null && contact.getLastInteractiveAt().isAfter(participant.getEnrolledAt())) {
                participant.setStatus(DripParticipant.ParticipantStatus.EXITED_REPLIED);
                dripParticipantRepository.save(participant);
                log.info("[DripSequence] ContactId={} exited sequenceId={} because they replied", contact.getId(), sequence.getId());
                return;
            }
        }

        // Send Drip Step Message via WhatsApp Client
        WhatsAppConfig config = whatsAppConfigRepository.findByUserId(sequence.getOwner().getId()).orElse(null);
        if (config != null && config.getAccessToken() != null) {
            WhatsAppTemplateSnapshot snapshot = step.getTemplateSnapshot();
            List<String> params = personalizationEngine.renderTemplateParameters(step.getVariableMappingJson(), contact, participant.getLead(), sequence.getOwner());

            String renderedBody = snapshot.getBodyText();
            for (int i = 0; i < params.size(); i++) {
                renderedBody = renderedBody.replace("{{" + (i + 1) + "}}", params.get(i));
            }

            try {
                whatsappClient.sendMessage(contact.getWaId(), renderedBody, config.getAccessToken(), config.getPhoneNumberId());
            } catch (Exception e) {
                log.warn("[DripSequence] Failed sending drip step to contactId={}: {}", contact.getId(), e.getMessage());
            }
        }

        // Advance to next step
        int nextStepOrder = participant.getCurrentStepOrder() + 1;
        Optional<DripSequenceStep> nextStepOpt = dripSequenceStepRepository.findBySequenceAndStepOrder(sequence, nextStepOrder);

        if (nextStepOpt.isPresent()) {
            DripSequenceStep nextStep = nextStepOpt.get();
            participant.setCurrentStepOrder(nextStepOrder);
            participant.setNextRunAt(LocalDateTime.now().plusHours(nextStep.getDelayHours()));
        } else {
            participant.setStatus(DripParticipant.ParticipantStatus.COMPLETED);
            participant.setCompletedAt(LocalDateTime.now());
        }

        dripParticipantRepository.save(participant);
    }
}
