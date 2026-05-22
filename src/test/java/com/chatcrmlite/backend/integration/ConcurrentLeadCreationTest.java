package com.chatcrmlite.backend.integration;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.LeadRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.lead.LeadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for concurrent lead creation scenarios to ensure thread safety
 * and data consistency when multiple leads are created simultaneously
 * for the same contact.
 */
@SpringBootTest
@ActiveProfiles("test")
public class ConcurrentLeadCreationTest {

    @Autowired
    private LeadService leadService;

    @Autowired
    private LeadRepository leadRepository;

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private com.chatcrmlite.backend.repositories.TenantRepository tenantRepository;

    private User testUser;
    private Contact testContact;

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        leadRepository.deleteAll();
        contactRepository.deleteAll();
        userRepository.deleteAll();
        tenantRepository.deleteAll();
    }

    @BeforeEach
    void setUp() {
        // Create test user
        testUser = User.builder()
                .email("concurrent@example.com")
                .password("test123")
                .businessName("Concurrent Test Business")
                .businessSubType("GENERAL")
                .build();
        testUser = userRepository.save(testUser);

        // Create test contact
        testContact = Contact.builder()
                .waId("concurrent_test_wa")
                .name("Concurrent Test Contact")
                .source("WhatsApp")
                .owner(testUser)
                .build();
        testContact = contactRepository.save(testContact);
    }

    @Test
    void testConcurrentLeadCreation() throws InterruptedException, ExecutionException {
        // Arrange
        int numberOfThreads = 10;
        int leadsPerThread = 5;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(numberOfThreads);
        
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<Future<List<Lead>>> futures = new ArrayList<>();

        // Act: Create leads concurrently
        for (int i = 0; i < numberOfThreads; i++) {
            final int threadId = i;
            Future<List<Lead>> future = executor.submit(() -> {
                try {
                    // Wait for all threads to be ready
                    startLatch.await();
                    
                    List<Lead> createdLeads = new ArrayList<>();
                    for (int j = 0; j < leadsPerThread; j++) {
                        try {
                            Lead lead = Lead.builder()
                                    .contact(testContact)
                                    .owner(testUser)
                                    .status(Lead.LeadStatus.NEW)
                                    .enquiries("[]")
                                    .build();
                            
                            Lead savedLead = leadRepository.save(lead);
                            createdLeads.add(savedLead);
                            successCount.incrementAndGet();
                            
                            // Small delay to increase chance of concurrency issues
                            Thread.sleep(1);
                            
                        } catch (Exception e) {
                            failureCount.incrementAndGet();
                            System.err.println("Thread " + threadId + " failed to create lead: " + e.getMessage());
                        }
                    }
                    return createdLeads;
                } finally {
                    completionLatch.countDown();
                }
            });
            futures.add(future);
        }

        // Start all threads simultaneously
        startLatch.countDown();
        
        // Wait for all threads to complete
        boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        // Collect all created leads
        List<Lead> allCreatedLeads = new ArrayList<>();
        for (Future<List<Lead>> future : futures) {
            allCreatedLeads.addAll(future.get());
        }

        executor.shutdown();

        // Assert: Verify data consistency
        List<Lead> leadsFromDb = leadService.getLeadsByContactId(testContact.getId(), testUser);
        
        assertThat(successCount.get()).isEqualTo(numberOfThreads * leadsPerThread);
        assertThat(failureCount.get()).isEqualTo(0);
        assertThat(leadsFromDb).hasSize(numberOfThreads * leadsPerThread);
        assertThat(allCreatedLeads).hasSize(numberOfThreads * leadsPerThread);
        
        // Verify all leads belong to the correct contact and owner
        assertThat(leadsFromDb).allMatch(lead -> 
            lead.getContact().getId().equals(testContact.getId()) &&
            lead.getOwner().getId().equals(testUser.getId()) &&
            lead.getStatus() == Lead.LeadStatus.NEW
        );
        
        // Verify no duplicate IDs
        long uniqueIds = leadsFromDb.stream()
                .map(Lead::getId)
                .distinct()
                .count();
        assertThat(uniqueIds).isEqualTo(leadsFromDb.size());
    }

    @Test
    void testConcurrentLeadStatusUpdates() throws InterruptedException, ExecutionException {
        // Arrange: Create multiple leads first
        List<Lead> leads = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Lead lead = Lead.builder()
                    .contact(testContact)
                    .owner(testUser)
                    .status(Lead.LeadStatus.NEW)
                    .enquiries("[]")
                    .build();
            leads.add(leadRepository.save(lead));
        }

        // Act: Update lead statuses concurrently
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(leads.size());
        
        AtomicInteger successCount = new AtomicInteger(0);
        List<Future<Lead>> futures = new ArrayList<>();

        for (int i = 0; i < leads.size(); i++) {
            final Lead lead = leads.get(i);
            final Lead.LeadStatus newStatus = Lead.LeadStatus.values()[i % Lead.LeadStatus.values().length];
            
            Future<Lead> future = executor.submit(() -> {
                try {
                    startLatch.await();
                    Lead updatedLead = leadService.updateStatus(lead.getId(), newStatus, testUser);
                    successCount.incrementAndGet();
                    return updatedLead;
                } catch (Exception e) {
                    System.err.println("Failed to update lead " + lead.getId() + ": " + e.getMessage());
                    throw new RuntimeException(e);
                } finally {
                    completionLatch.countDown();
                }
            });
            futures.add(future);
        }

        // Start all updates simultaneously
        startLatch.countDown();
        
        // Wait for completion
        boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        executor.shutdown();

        // Assert: Verify all updates succeeded
        assertThat(successCount.get()).isEqualTo(leads.size());
        
        // Verify final states
        List<Lead> updatedLeads = leadService.getLeadsByContactId(testContact.getId(), testUser);
        assertThat(updatedLeads).hasSize(leads.size());
        
        // Each lead should have its expected status
        for (int i = 0; i < futures.size(); i++) {
            Lead updatedLead = futures.get(i).get();
            Lead.LeadStatus expectedStatus = Lead.LeadStatus.values()[i % Lead.LeadStatus.values().length];
            assertThat(updatedLead.getStatus()).isEqualTo(expectedStatus);
        }
    }

    @Test
    void testConcurrentEnquiryAddition() throws InterruptedException, ExecutionException {
        // Arrange: Create multiple leads
        List<Lead> leads = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Lead lead = Lead.builder()
                    .contact(testContact)
                    .owner(testUser)
                    .status(Lead.LeadStatus.NEW)
                    .enquiries("[]")
                    .build();
            leads.add(leadRepository.save(lead));
        }

        // Act: Add enquiries concurrently to different leads
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(20);
        
        AtomicInteger successCount = new AtomicInteger(0);
        List<Future<Void>> futures = new ArrayList<>();

        for (int i = 0; i < 20; i++) {
            final int enquiryIndex = i;
            final Lead targetLead = leads.get(i % leads.size());
            
            Future<Void> future = executor.submit(() -> {
                try {
                    startLatch.await();
                    
                    var enquiryRequest = new com.chatcrmlite.backend.dto.EnquiryRequest();
                    enquiryRequest.setMessage("Concurrent enquiry " + enquiryIndex);
                    enquiryRequest.setType("CONCURRENT_TEST");
                    enquiryRequest.setSource("Concurrency Test");
                    
                    leadService.addEnquiry(targetLead.getId(), enquiryRequest, testUser);
                    successCount.incrementAndGet();
                    return null;
                } catch (Exception e) {
                    System.err.println("Failed to add enquiry " + enquiryIndex + ": " + e.getMessage());
                    throw new RuntimeException(e);
                } finally {
                    completionLatch.countDown();
                }
            });
            futures.add(future);
        }

        // Start all operations simultaneously
        startLatch.countDown();
        
        // Wait for completion
        boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        executor.shutdown();

        // Assert: Verify all enquiries were added
        assertThat(successCount.get()).isEqualTo(20);
        
        // Verify enquiry distribution
        int totalEnquiries = 0;
        for (Lead lead : leads) {
            List<com.chatcrmlite.backend.dto.EnquiryDTO> enquiries = 
                leadService.getEnquiries(lead.getId(), testUser);
            totalEnquiries += enquiries.size();
            
            // Each enquiry should be properly formatted
            for (var enquiry : enquiries) {
                assertThat(enquiry.getMessage()).startsWith("Concurrent enquiry");
                assertThat(enquiry.getType()).isEqualTo("CONCURRENT_TEST");
                assertThat(enquiry.getSource()).isEqualTo("Concurrency Test");
                assertThat(enquiry.getId()).isNotNull();
                assertThat(enquiry.getCreatedAt()).isNotNull();
            }
        }
        
        assertThat(totalEnquiries).isEqualTo(20);
    }

    @Test
    void testConcurrentValidationAndCreation() throws InterruptedException, ExecutionException {
        // Arrange: Test concurrent validation calls
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(10);
        
        AtomicInteger validationSuccessCount = new AtomicInteger(0);
        AtomicInteger validationFailureCount = new AtomicInteger(0);

        // Act: Perform concurrent validations
        for (int i = 0; i < 10; i++) {
            final String enquiryType = (i % 2 == 0) ? "NEW_ENQUIRY" : "ONGOING";
            
            executor.submit(() -> {
                try {
                    startLatch.await();
                    
                    // Test validation
                    leadService.validateLeadCreation(testContact, testUser, enquiryType);
                    validationSuccessCount.incrementAndGet();
                    
                } catch (Exception e) {
                    validationFailureCount.incrementAndGet();
                    System.err.println("Validation failed: " + e.getMessage());
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        // Start all validations simultaneously
        startLatch.countDown();
        
        // Wait for completion
        boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        executor.shutdown();

        // Assert: All validations should succeed (no business rule violations)
        assertThat(validationSuccessCount.get()).isEqualTo(10);
        assertThat(validationFailureCount.get()).isEqualTo(0);
    }

    @Test
    void testConcurrentActiveLeadCountQueries() throws InterruptedException, ExecutionException {
        // Arrange: Create leads with mixed statuses
        for (int i = 0; i < 15; i++) {
            Lead.LeadStatus status = (i < 10) ? Lead.LeadStatus.NEW : Lead.LeadStatus.CLOSED_WON;
            Lead lead = Lead.builder()
                    .contact(testContact)
                    .owner(testUser)
                    .status(status)
                    .enquiries("[]")
                    .build();
            leadRepository.save(lead);
        }

        // Act: Query active lead count concurrently
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(20);
        
        List<Future<Long>> futures = new ArrayList<>();

        for (int i = 0; i < 20; i++) {
            Future<Long> future = executor.submit(() -> {
                try {
                    startLatch.await();
                    return leadService.getActiveLeadCountByContactId(testContact.getId(), testUser);
                } finally {
                    completionLatch.countDown();
                }
            });
            futures.add(future);
        }

        // Start all queries simultaneously
        startLatch.countDown();
        
        // Wait for completion
        boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        executor.shutdown();

        // Assert: All queries should return the same count (10 active leads)
        for (Future<Long> future : futures) {
            Long count = future.get();
            assertThat(count).isEqualTo(10L);
        }
    }
}