package com.chatcrmlite.backend.controllers.platform;

import com.chatcrmlite.backend.models.NicheTemplate;
import com.chatcrmlite.backend.repositories.NicheTemplateRepository;
import com.chatcrmlite.backend.services.platform.PlatformAuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/platform/templates")
public class PlatformTemplateController {

    private final NicheTemplateRepository templateRepository;
    private final PlatformAuditService auditService;

    public PlatformTemplateController(NicheTemplateRepository templateRepository,
                                       PlatformAuditService auditService) {
        this.templateRepository = templateRepository;
        this.auditService = auditService;
    }

    @GetMapping
    public ResponseEntity<List<NicheTemplate>> listTemplates(HttpServletRequest request) {
        ensureDefaultTemplates();
        auditService.record("LIST_TEMPLATES", "SUCCESS", "Platform", null, "{}", request);
        return ResponseEntity.ok(templateRepository.findAll());
    }

    private void ensureDefaultTemplates() {
        if (templateRepository.count() >= 21) {
            return;
        }
        List<NicheTemplate> defaultTemplates = List.of(
            createObj("tmpl-auto-dealers", "Auto Sales & Used Cars", "Automotive", "car", "#1E293B", "Lead intake, test drive scheduling, financing approval, and vehicle delivery tracking.", "[\"New Lead\", \"Test Drive Scheduled\", \"Financing Check\", \"Offer Made\", \"Delivered\", \"Closed Won\"]", 12),
            createObj("tmpl-career-counseling", "Career & Study Abroad", "Education", "graduationCap", "#2563EB", "Student counseling, university application tracking, document review, and visa assistance.", "[\"Inquiry\", \"Profile Evaluation\", \"University Shortlist\", \"Application Filed\", \"Visa Approved\", \"Enrolled\"]", 9),
            createObj("tmpl-dental-clinic", "Dental & Medical Practice", "Healthcare", "stethoscope", "#0D9488", "Patient intake, consultation scheduling, diagnostic check, and treatment plan follow-ups.", "[\"New Inquiry\", \"Consultation\", \"Treatment Plan\", \"Scheduled\", \"Completed\"]", 15),
            createObj("tmpl-wedding-planners", "Wedding & Event Planning", "Events", "sparkles", "#BE185D", "Client consultation, venue selection, vendor booking, budget review, and event execution.", "[\"Initial Consultation\", \"Venue Tour\", \"Proposal Sent\", \"Contract Signed\", \"Event Ready\", \"Completed\"]", 7),
            createObj("tmpl-makeup-artists", "Freelance Makeup & MUA", "Beauty", "palette", "#9D174D", "Bridal & party makeup inquiries, trial sessions, advance payments, and date confirmation.", "[\"Inquiry\", \"Trial Session\", \"Advance Received\", \"Date Confirmed\", \"Completed\"]", 11),
            createObj("tmpl-web-designers", "Web & Graphic Design Agency", "Services", "laptop", "#5B21B6", "Project discovery, wireframe approval, milestone payments, development, and site launch.", "[\"Discovery\", \"Proposal Sent\", \"Design Approval\", \"Development\", \"QA Review\", \"Launched\"]", 18),
            createObj("tmpl-fitness-trainers", "Gym & Personal Fitness", "Fitness", "dumbbell", "#9A3412", "Trial class signup, body assessment, custom workout plan, and membership enrollment.", "[\"Lead\", \"Free Trial\", \"Fitness Assessment\", \"Package Selection\", \"Enrolled\"]", 14),
            createObj("tmpl-ayurveda-doctors", "Ayurveda & Homeopathy Clinic", "Healthcare", "leaf", "#065F46", "Holistic health consultation, pulse diagnosis, remedy prescription, and follow-up care.", "[\"Inquiry\", \"Consultation\", \"Prescription\", \"Follow-up\", \"Completed\"]", 8),
            createObj("tmpl-tutors", "Private Tutoring & Coaching", "Education", "bookOpen", "#A16207", "Subject inquiry, demo class booking, parent consultation, and student batch enrollment.", "[\"Inquiry\", \"Demo Class\", \"Parent Meeting\", \"Batch Assigned\", \"Enrolled\"]", 20),
            createObj("tmpl-insurance-agents", "Insurance & Wealth Management", "Finance", "shieldCheck", "#1E40AF", "Policy inquiry, risk assessment, premium calculation, document check, and policy issuance.", "[\"Prospect\", \"Needs Analysis\", \"Quote Sent\", \"Underwriting\", \"Policy Issued\"]", 16),
            createObj("tmpl-interior-designers", "Interior Design & Architecture", "Real Estate", "home", "#78350F", "Site visit, 3D layout consultation, material selection, estimate approval, and execution.", "[\"Inquiry\", \"Site Visit\", \"3D Design Sent\", \"Contract Signed\", \"Execution\", \"Handover\"]", 10),
            createObj("tmpl-music-classes", "Music & Art Academy", "Education", "music", "#6D28D9", "Instrument trial, skill assessment, schedule matching, and class enrollment.", "[\"Inquiry\", \"Demo Lesson\", \"Batch Allocation\", \"Fees Paid\", \"Active Student\"]", 6),
            createObj("tmpl-physiotherapy", "Physiotherapy & Rehab Center", "Healthcare", "heartPulse", "#2563EB", "Patient assessment, rehab program creation, session scheduling, and recovery tracking.", "[\"New Patient\", \"Initial Assessment\", \"Therapy Plan\", \"In Progress\", \"Recovered\"]", 9),
            createObj("tmpl-salons", "Salons & Hair Clinics", "Beauty", "scissors", "#1F2937", "Appointment booking, hair/skin consultation, service execution, and re-booking.", "[\"Booking Request\", \"Confirmed\", \"Consultation\", \"Service Done\", \"Feedback\"]", 25),
            createObj("tmpl-travel-operators", "Tour & Travel Operators", "Travel", "plane", "#047857", "Holiday inquiry, customized itinerary quote, booking confirmation, and travel support.", "[\"Inquiry\", \"Itinerary Sent\", \"Advance Booking\", \"Tickets Issued\", \"Travel Done\"]", 13),
            createObj("tmpl-property-brokers", "Property Brokerage & Real Estate", "Real Estate", "building", "#1E293B", "Property inquiry, buyer site visits, negotiation, agreement signing, and deal closure.", "[\"Lead\", \"Property Matching\", \"Site Visit\", \"Negotiation\", \"Agreement\", \"Closed\"]", 31),
            createObj("tmpl-aesthetic-clinics", "Skin & Aesthetic Clinic", "Healthcare", "sparkles", "#9D174D", "Skin analysis consultation, treatment recommendation, procedure package, and post-care.", "[\"Consultation\", \"Skin Analysis\", \"Package Quote\", \"Treatment Started\", \"Completed\"]", 17),
            createObj("tmpl-solar-installers", "Solar & Smart Home Contracting", "Contracting", "sun", "#065F46", "Rooftop assessment, solar energy quote, net-metering approval, and system installation.", "[\"Site Survey\", \"Proposal Sent\", \"Net Metering Approval\", \"Installation\", \"Commissioned\"]", 11),
            createObj("tmpl-photographers", "Photography & Studio Services", "Media", "camera", "#1F2937", "Shoot inquiry, package selection, shoot date lock, editing, and album delivery.", "[\"Inquiry\", \"Package Sent\", \"Booking Confirmed\", \"Shoot Done\", \"Album Delivered\"]", 14),
            createObj("tmpl-yoga-instructors", "Yoga & Wellness Studio", "Wellness", "userCheck", "#3730A3", "Batch trial, health profile evaluation, slot allocation, and monthly membership.", "[\"Inquiry\", \"Trial Class\", \"Health Assessment\", \"Membership Joined\", \"Active\"]", 8),
            createObj("tmpl-generic-business", "Generic Corporate CRM", "General", "bot", "#0F172A", "Universal sales pipeline template adaptable for any service or product company.", "[\"New Lead\", \"Contacted\", \"Qualified\", \"Proposal Sent\", \"Negotiation\", \"Closed Won\"]", 42)
        );
        for (NicheTemplate t : defaultTemplates) {
            if (!templateRepository.existsById(t.getId())) {
                templateRepository.save(t);
            }
        }
    }

    private NicheTemplate createObj(String id, String name, String niche, String icon, String color, String desc, String stages, int tenants) {
        NicheTemplate t = new NicheTemplate();
        t.setId(id);
        t.setName(name);
        t.setNiche(niche);
        t.setIcon(icon);
        t.setColor(color);
        t.setDescription(desc);
        t.setStages(stages);
        t.setStatus("published");
        t.setTenantsUsing(tenants);
        return t;
    }

    @PostMapping
    public ResponseEntity<NicheTemplate> createTemplate(@RequestBody NicheTemplate template,
                                                         HttpServletRequest request) {
        if (template.getId() == null || template.getId().isBlank()) {
            template.setId("tmpl-" + UUID.randomUUID().toString().substring(0, 8));
        }
        NicheTemplate saved = templateRepository.save(template);
        auditService.record("CREATE_TEMPLATE", "SUCCESS", "Template", saved.getId(),
                "Created template: " + saved.getName(), request);
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<NicheTemplate> updateTemplate(@PathVariable String id,
                                                         @RequestBody NicheTemplate body,
                                                         HttpServletRequest request) {
        NicheTemplate existing = templateRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Template not found"));

        if (body.getName() != null) existing.setName(body.getName());
        if (body.getNiche() != null) existing.setNiche(body.getNiche());
        if (body.getIcon() != null) existing.setIcon(body.getIcon());
        if (body.getColor() != null) existing.setColor(body.getColor());
        if (body.getDescription() != null) existing.setDescription(body.getDescription());
        if (body.getStages() != null) existing.setStages(body.getStages());
        if (body.getStatus() != null) existing.setStatus(body.getStatus());

        NicheTemplate saved = templateRepository.save(existing);
        auditService.record("UPDATE_TEMPLATE", "SUCCESS", "Template", id,
                "Updated template: " + saved.getName(), request);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> deleteTemplate(@PathVariable String id,
                                                               HttpServletRequest request) {
        if (!templateRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        templateRepository.deleteById(id);
        auditService.record("DELETE_TEMPLATE", "SUCCESS", "Template", id,
                "Deleted template: " + id, request);
        return ResponseEntity.ok(Map.of("id", id, "status", "DELETED"));
    }
}
