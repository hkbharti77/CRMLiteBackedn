package com.chatcrmlite.backend.config;

import com.chatcrmlite.backend.models.BusinessCategory;
import com.chatcrmlite.backend.models.BusinessSubCategory;
import com.chatcrmlite.backend.repositories.BusinessCategoryRepository;
import com.chatcrmlite.backend.repositories.BusinessSubCategoryRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.chatcrmlite.backend.security.TenantContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Configuration
public class CategorySeeder {

    @Bean
    public CommandLineRunner seedCategories(BusinessCategoryRepository categoryRepository, 
                                           BusinessSubCategoryRepository subCategoryRepository) {
        return args -> {
            TenantContext.setAdminMode(true);
            try {
                record Niche(String name, String trigger, String services) {}
                Map<String, List<Niche>> seedData = new HashMap<>();

                seedData.put("Health & Personal Care", List.of(
                    new Niche("Dental Clinics", "📅 Book Appointment", "📋 View Services"),
                    new Niche("Skin & Aesthetic Clinics", "📅 Book Appointment", "📋 View Services"),
                    new Niche("Homeopathy & Ayurveda Doctors", "🌿 Book Consult", "📋 View Services"),
                    new Niche("Premium Salons & Hair Clinics", "💇 Book My Slot", "📋 View Services"),
                    new Niche("Physiotherapy & Chiropractic Centers", "📅 Book Appointment", "📋 View Services")
                ));

                seedData.put("Education & Fitness", List.of(
                    new Niche("Independent Tutors", "📚 Enquire Now", "📚 View Classes"),
                    new Niche("Gym / Personal Fitness Trainers", "🏋️ Join / Trial", "📋 View Services"),
                    new Niche("Yoga & Meditation Instructors", "🧘 Join Session", "📚 View Classes"),
                    new Niche("Career & Study Abroad Counselors", "🎓 Book Session", "📋 View Services"),
                    new Niche("Music & Art Classes", "🎨 Register Now", "📚 View Classes")
                ));

                seedData.put("Real Estate & High-Ticket Sales", List.of(
                    new Niche("Property Brokers", "🏠 Property Inquiry", "🏢 View Properties"),
                    new Niche("Auto / Used Car Dealers", "🚗 Book Test Drive", "🚘 View Vehicles"),
                    new Niche("Insurance Agents", "📄 Get Quote", "📋 View Services"),
                    new Niche("Solar Panel / Smart Home Installers", "☀️ Site Survey", "📦 View Products"),
                    new Niche("Premium Tour & Travel Operators", "🏝️ Plan Trip", "✈️ View Packages")
                ));

                seedData.put("Independent Freelancers & Creatives", List.of(
                    new Niche("Wedding & Portrait Photographers", "📸 Book Shoot", "📋 View Services"),
                    new Niche("Freelance Makeup Artists (MUA)", "💄 Book Session", "📋 View Services"),
                    new Niche("Event & Wedding Planners", "🎊 Plan Event", "📋 View Services"),
                    new Niche("Interior Designers & Architects", "🏡 Free Consult", "🏢 View Properties"),
                    new Niche("Freelance Web/Graphic Designers", "💻 Start Project", "🎨 View My Portfolio")
                ));

                seedData.put("Other", List.of(new Niche("Other", "Enquire Now", "📋 View Services")));

                // FIX N+1: Load ALL existing categories and subcategories in 2 bulk queries
                // instead of one SELECT per niche (was ~60+ queries, now just 2)
                Map<String, BusinessCategory> existingCategories = categoryRepository.findAll()
                        .stream()
                        .collect(Collectors.toMap(BusinessCategory::getName, c -> c));

                Map<String, BusinessSubCategory> existingSubCategories = subCategoryRepository.findAll()
                        .stream()
                        .collect(Collectors.toMap(BusinessSubCategory::getName, s -> s));

                List<BusinessSubCategory> toSave = new ArrayList<>();

                for (Map.Entry<String, List<Niche>> entry : seedData.entrySet()) {
                    // 1. Find or Create Category — from in-memory map, no DB hit
                    BusinessCategory category = existingCategories.computeIfAbsent(
                            entry.getKey(),
                            name -> categoryRepository.save(BusinessCategory.builder().name(name).build())
                    );

                    for (Niche niche : entry.getValue()) {
                        // 2. Find or Create SubCategory — from in-memory map, no DB hit
                        BusinessSubCategory sub = existingSubCategories.computeIfAbsent(
                                niche.name(),
                                name -> BusinessSubCategory.builder().name(name).category(category).build()
                        );

                        // 3. Update labels only if changed (avoids unnecessary dirty writes)
                        if (!niche.trigger().equals(sub.getTriggerLabel())
                                || !niche.services().equals(sub.getServicesLabel())) {
                            sub.setTriggerLabel(niche.trigger());
                            sub.setServicesLabel(niche.services());
                            toSave.add(sub);
                        } else if (sub.getId() == null) {
                            // New entity — must be saved
                            toSave.add(sub);
                        }
                    }
                }

                // FIX N+1: Single bulk saveAll instead of one save() per niche
                if (!toSave.isEmpty()) {
                    subCategoryRepository.saveAll(toSave);
                }

                System.out.println("[CategorySeeder] Successfully synchronized labels for all business niches.");
            } finally {
                TenantContext.setAdminMode(false);
            }
        };
    }
}
