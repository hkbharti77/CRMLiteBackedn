-- Migration: V10083__Seed_All_21_Niche_Templates.sql
-- Description: Seed all 21 pre-configured business niche CRM templates into niche_templates table

INSERT INTO niche_templates (id, name, niche, icon, color, description, stages, status, tenants_using)
VALUES 
('tmpl-auto-dealers', 'Auto Sales & Used Cars', 'Automotive', 'car', '#1E293B', 'Lead intake, test drive scheduling, financing approval, and vehicle delivery tracking.', '["New Lead", "Test Drive Scheduled", "Financing Check", "Offer Made", "Delivered", "Closed Won"]', 'published', 12),
('tmpl-career-counseling', 'Career & Study Abroad', 'Education', 'graduationCap', '#2563EB', 'Student counseling, university application tracking, document review, and visa assistance.', '["Inquiry", "Profile Evaluation", "University Shortlist", "Application Filed", "Visa Approved", "Enrolled"]', 'published', 9),
('tmpl-dental-clinic', 'Dental & Medical Practice', 'Healthcare', 'stethoscope', '#0D9488', 'Patient intake, consultation scheduling, diagnostic check, and treatment plan follow-ups.', '["New Inquiry", "Consultation", "Treatment Plan", "Scheduled", "Completed"]', 'published', 15),
('tmpl-wedding-planners', 'Wedding & Event Planning', 'Events', 'sparkles', '#BE185D', 'Client consultation, venue selection, vendor booking, budget review, and event execution.', '["Initial Consultation", "Venue Tour", "Proposal Sent", "Contract Signed", "Event Ready", "Completed"]', 'published', 7),
('tmpl-makeup-artists', 'Freelance Makeup & MUA', 'Beauty', 'palette', '#9D174D', 'Bridal & party makeup inquiries, trial sessions, advance payments, and date confirmation.', '["Inquiry", "Trial Session", "Advance Received", "Date Confirmed", "Completed"]', 'published', 11),
('tmpl-web-designers', 'Web & Graphic Design Agency', 'Services', 'laptop', '#5B21B6', 'Project discovery, wireframe approval, milestone payments, development, and site launch.', '["Discovery", "Proposal Sent", "Design Approval", "Development", "QA Review", "Launched"]', 'published', 18),
('tmpl-fitness-trainers', 'Gym & Personal Fitness', 'Fitness', 'dumbbell', '#9A3412', 'Trial class signup, body assessment, custom workout plan, and membership enrollment.', '["Lead", "Free Trial", "Fitness Assessment", "Package Selection", "Enrolled"]', 'published', 14),
('tmpl-ayurveda-doctors', 'Ayurveda & Homeopathy Clinic', 'Healthcare', 'leaf', '#065F46', 'Holistic health consultation, pulse diagnosis, remedy prescription, and follow-up care.', '["Inquiry", "Consultation", "Prescription", "Follow-up", "Completed"]', 'published', 8),
('tmpl-tutors', 'Private Tutoring & Coaching', 'Education', 'bookOpen', '#A16207', 'Subject inquiry, demo class booking, parent consultation, and student batch enrollment.', '["Inquiry", "Demo Class", "Parent Meeting", "Batch Assigned", "Enrolled"]', 'published', 20),
('tmpl-insurance-agents', 'Insurance & Wealth Management', 'Finance', 'shieldCheck', '#1E40AF', 'Policy inquiry, risk assessment, premium calculation, document check, and policy issuance.', '["Prospect", "Needs Analysis", "Quote Sent", "Underwriting", "Policy Issued"]', 'published', 16),
('tmpl-interior-designers', 'Interior Design & Architecture', 'Real Estate', 'home', '#78350F', 'Site visit, 3D layout consultation, material selection, estimate approval, and execution.', '["Inquiry", "Site Visit", "3D Design Sent", "Contract Signed", "Execution", "Handover"]', 'published', 10),
('tmpl-music-classes', 'Music & Art Academy', 'Education', 'music', '#6D28D9', 'Instrument trial, skill assessment, schedule matching, and class enrollment.', '["Inquiry", "Demo Lesson", "Batch Allocation", "Fees Paid", "Active Student"]', 'published', 6),
('tmpl-physiotherapy', 'Physiotherapy & Rehab Center', 'Healthcare', 'heartPulse', '#2563EB', 'Patient assessment, rehab program creation, session scheduling, and recovery tracking.', '["New Patient", "Initial Assessment", "Therapy Plan", "In Progress", "Recovered"]', 'published', 9),
('tmpl-salons', 'Salons & Hair Clinics', 'Beauty', 'scissors', '#1F2937', 'Appointment booking, hair/skin consultation, service execution, and re-booking.', '["Booking Request", "Confirmed", "Consultation", "Service Done", "Feedback"]', 'published', 25),
('tmpl-travel-operators', 'Tour & Travel Operators', 'Travel', 'plane', '#047857', 'Holiday inquiry, customized itinerary quote, booking confirmation, and travel support.', '["Inquiry", "Itinerary Sent", "Advance Booking", "Tickets Issued", "Travel Done"]', 'published', 13),
('tmpl-property-brokers', 'Property Brokerage & Real Estate', 'Real Estate', 'building', '#1E293B', 'Property inquiry, buyer site visits, negotiation, agreement signing, and deal closure.', '["Lead", "Property Matching", "Site Visit", "Negotiation", "Agreement", "Closed"]', 'published', 31),
('tmpl-aesthetic-clinics', 'Skin & Aesthetic Clinic', 'Healthcare', 'sparkles', '#9D174D', 'Skin analysis consultation, treatment recommendation, procedure package, and post-care.', '["Consultation", "Skin Analysis", "Package Quote", "Treatment Started", "Completed"]', 'published', 17),
('tmpl-solar-installers', 'Solar & Smart Home Contracting', 'Contracting', 'sun', '#065F46', 'Rooftop assessment, solar energy quote, net-metering approval, and system installation.', '["Site Survey", "Proposal Sent", "Net Metering Approval", "Installation", "Commissioned"]', 'published', 11),
('tmpl-photographers', 'Photography & Studio Services', 'Media', 'camera', '#1F2937', 'Shoot inquiry, package selection, shoot date lock, editing, and album delivery.', '["Inquiry", "Package Sent", "Booking Confirmed", "Shoot Done", "Album Delivered"]', 'published', 14),
('tmpl-yoga-instructors', 'Yoga & Wellness Studio', 'Wellness', 'userCheck', '#3730A3', 'Batch trial, health profile evaluation, slot allocation, and monthly membership.', '["Inquiry", "Trial Class", "Health Assessment", "Membership Joined", "Active"]', 'published', 8),
('tmpl-generic-business', 'Generic Corporate CRM', 'General', 'bot', '#0F172A', 'Universal sales pipeline template adaptable for any service or product company.', '["New Lead", "Contacted", "Qualified", "Proposal Sent", "Negotiation", "Closed Won"]', 'published', 42)
ON CONFLICT (id) DO UPDATE SET
  name = EXCLUDED.name,
  niche = EXCLUDED.niche,
  icon = EXCLUDED.icon,
  color = EXCLUDED.color,
  description = EXCLUDED.description,
  stages = EXCLUDED.stages,
  status = EXCLUDED.status,
  tenants_using = EXCLUDED.tenants_using;
