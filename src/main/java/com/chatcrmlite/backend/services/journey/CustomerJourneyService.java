package com.chatcrmlite.backend.services.journey;

import com.chatcrmlite.backend.models.journey.CustomerJourney;
import com.chatcrmlite.backend.models.journey.CustomerJourneyVersion;
import com.chatcrmlite.backend.repositories.journey.CustomerJourneyRepository;
import com.chatcrmlite.backend.repositories.journey.CustomerJourneyVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class CustomerJourneyService {

    private final CustomerJourneyRepository journeyRepository;
    private final CustomerJourneyVersionRepository journeyVersionRepository;

    @Transactional
    public CustomerJourney createJourney(String businessId, String name, String description, String triggerEvent, String reentryMode) {
        CustomerJourney journey = CustomerJourney.builder()
                .businessId(businessId)
                .name(name)
                .description(description)
                .triggerEvent(triggerEvent)
                .status("DRAFT")
                .reentryMode(reentryMode != null ? reentryMode : "ONE_ACTIVE_PER_CONTACT")
                .build();

        CustomerJourney saved = journeyRepository.save(journey);
        log.info("[JourneyService] Created journey '{}' ({}) for biz={}", name, saved.getId(), businessId);
        return saved;
    }

    @Transactional
    public CustomerJourneyVersion createJourneyVersion(String businessId, UUID journeyId, String definitionJson) {
        CustomerJourney journey = journeyRepository.findByIdAndBusinessId(journeyId, businessId)
                .orElseThrow(() -> new IllegalArgumentException("Journey not found for ID: " + journeyId));

        List<CustomerJourneyVersion> existingVersions = journeyVersionRepository.findByJourneyId(journeyId);
        int nextVersionNumber = existingVersions.stream()
                .mapToInt(CustomerJourneyVersion::getVersionNumber)
                .max()
                .orElse(0) + 1;

        CustomerJourneyVersion version = CustomerJourneyVersion.builder()
                .journeyId(journeyId)
                .businessId(businessId)
                .versionNumber(nextVersionNumber)
                .definitionJson(definitionJson)
                .status("DRAFT")
                .build();

        CustomerJourneyVersion saved = journeyVersionRepository.save(version);
        log.info("[JourneyService] Created version v{} for journey {} (biz={})", nextVersionNumber, journeyId, businessId);
        return saved;
    }

    @Transactional
    public CustomerJourney publishJourneyVersion(String businessId, UUID journeyId, UUID versionId) {
        CustomerJourney journey = journeyRepository.findByIdAndBusinessId(journeyId, businessId)
                .orElseThrow(() -> new IllegalArgumentException("Journey not found: " + journeyId));

        CustomerJourneyVersion version = journeyVersionRepository.findByIdAndBusinessId(versionId, businessId)
                .orElseThrow(() -> new IllegalArgumentException("Journey version not found: " + versionId));

        if (!version.getJourneyId().equals(journeyId)) {
            throw new IllegalArgumentException("Version " + versionId + " does not belong to Journey " + journeyId);
        }

        // Update Version Status to PUBLISHED
        version.setStatus("PUBLISHED");
        version.setPublishedAt(ZonedDateTime.now());
        journeyVersionRepository.save(version);

        // Link as published version on parent journey
        journey.setPublishedVersionId(version.getId());
        journey.setStatus("PUBLISHED");
        CustomerJourney updatedJourney = journeyRepository.save(journey);

        log.info("[JourneyService] Published journey '{}' (v{}) for biz={}", journey.getName(), version.getVersionNumber(), businessId);
        return updatedJourney;
    }

    public List<CustomerJourney> getJourneys(String businessId) {
        return journeyRepository.findByBusinessId(businessId);
    }

    public Optional<CustomerJourney> getJourney(String businessId, UUID journeyId) {
        return journeyRepository.findByIdAndBusinessId(journeyId, businessId);
    }

    public List<CustomerJourneyVersion> getJourneyVersions(UUID journeyId) {
        return journeyVersionRepository.findByJourneyId(journeyId);
    }
}
