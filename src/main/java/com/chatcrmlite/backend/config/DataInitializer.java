package com.chatcrmlite.backend.config;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.LeadRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
public class DataInitializer implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private LeadRepository leadRepository;

    @Autowired
    private com.chatcrmlite.backend.repositories.MessageRepository messageRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) throws Exception {
        // --- CLEANUP MOCK DATA ---
        List<String> mockNames = Arrays.asList("John Doe", "Sarah Smith");
        for (String name : mockNames) {
            List<Contact> mockContacts = contactRepository.findByName(name);
            for (Contact contact : mockContacts) {
                // Delete associated leads
                List<Lead> leads = leadRepository.findAllByContact(contact);
                leadRepository.deleteAll(leads);
                
                // Delete associated messages
                var messages = messageRepository.findAllByContactOrderByTimestampAsc(contact);
                messageRepository.deleteAll(messages);
                
                // Delete contact
                contactRepository.delete(contact);
                log.info("[Init] Purged mock contact: {}", name);
            }
        }

        // System is initialized with an empty database to ensure production-like state.
        // Users should register through the Auth flow.
        if (userRepository.count() == 0) {
            log.info("[Init] No users found. Ready for new registrations.");
        }
    }
}
