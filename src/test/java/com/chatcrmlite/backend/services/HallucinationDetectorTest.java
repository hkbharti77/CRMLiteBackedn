package com.chatcrmlite.backend.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class HallucinationDetectorTest {

    private HallucinationDetector detector;

    @BeforeEach
    void setUp() {
        detector = new HallucinationDetector();
    }

    @Test
    @DisplayName("1. Exact price match → PASS")
    void testExactPriceMatch_Passes() {
        String context = "Consultation fee is ₹500.";
        String answer = "Consultation fee is ₹500.";
        assertTrue(detector.isValid(answer, context));
    }

    @Test
    @DisplayName("2. Wrong price → FAIL")
    void testWrongPrice_Fails() {
        String context = "Consultation fee is ₹500.";
        String answer = "Consultation fee is ₹1500.";
        assertFalse(detector.isValid(answer, context));
    }

    @Test
    @DisplayName("3. Currency normalization (₹500 vs Rs. 500 / 500 INR) → PASS")
    void testCurrencyNormalization_Passes() {
        String context = "Consultation fee is ₹500.";
        String answer = "The consultation costs Rs. 500.";
        assertTrue(detector.isValid(answer, context));

        String answer2 = "The fee is 500 INR.";
        assertTrue(detector.isValid(answer2, context));
    }

    @Test
    @DisplayName("4. Exact operating hours → PASS")
    void testExactOperatingHours_Passes() {
        String context = "The clinic is open from 9 AM to 6 PM.";
        String answer = "Clinic is open from 9 AM to 6 PM.";
        assertTrue(detector.isValid(answer, context));
    }

    @Test
    @DisplayName("5. Wrong operating hours → FAIL")
    void testWrongOperatingHours_Fails() {
        String context = "Clinic is open from 9 AM to 6 PM.";
        String answer = "Clinic is open from 10 AM to 8 PM.";
        assertFalse(detector.isValid(answer, context));
    }

    @Test
    @DisplayName("6. Time formatting differences (9:00 AM vs 9 AM) → PASS")
    void testTimeFormattingDifferences_Passes() {
        String context = "Open from 9 AM to 6 PM.";
        String answer = "We are open from 9:00 AM to 6:00 PM.";
        assertTrue(detector.isValid(answer, context));
    }

    @Test
    @DisplayName("7. Supported phone → PASS")
    void testSupportedPhone_Passes() {
        String context = "Contact our team at 9876543210 for bookings.";
        String answer = "You can call us at 9876543210.";
        assertTrue(detector.isValid(answer, context));
    }

    @Test
    @DisplayName("8. Wrong phone → FAIL")
    void testWrongPhone_Fails() {
        String context = "Call us at 9876543210.";
        String answer = "You can reach us directly at 9123456789.";
        assertFalse(detector.isValid(answer, context));
    }

    @Test
    @DisplayName("9. Supported email → PASS")
    void testSupportedEmail_Passes() {
        String context = "Email us at support@chatcrmlite.com.";
        String answer = "Please write to Support@ChatCrmLite.com for inquiries.";
        assertTrue(detector.isValid(answer, context));
    }

    @Test
    @DisplayName("10. Wrong email → FAIL")
    void testWrongEmail_Fails() {
        String context = "Email us at support@chatcrmlite.com.";
        String answer = "Please contact fake@scamdomain.com.";
        assertFalse(detector.isValid(answer, context));
    }

    @Test
    @DisplayName("11. Supported URL → PASS")
    void testSupportedUrl_Passes() {
        String context = "Visit our site at https://www.chatcrmlite.com/pricing.";
        String answer = "You can check our plans at http://chatcrmlite.com/pricing.";
        assertTrue(detector.isValid(answer, context));
    }

    @Test
    @DisplayName("12. Wrong URL → FAIL")
    void testWrongUrl_Fails() {
        String context = "Visit our site at https://chatcrmlite.com.";
        String answer = "Visit https://malicious-site.com for discounts.";
        assertFalse(detector.isValid(answer, context));
    }

    @Test
    @DisplayName("13. Supported city → PASS")
    void testSupportedCity_Passes() {
        String context = "Our clinic is located in Delhi.";
        String answer = "We provide medical services in Delhi.";
        assertTrue(detector.isValid(answer, context));
    }

    @Test
    @DisplayName("14. Wrong city → FAIL")
    void testWrongCity_Fails() {
        String context = "Our clinic is located in Delhi.";
        String answer = "We provide medical services in Mumbai.";
        assertFalse(detector.isValid(answer, context));
    }

    @Test
    @DisplayName("15. Supported doctor/entity → PASS")
    void testSupportedDoctorEntity_Passes() {
        String context = "Dr. Sharma provides dental consultation in Delhi.";
        String answer = "Dr. Sharma offers dental consultations in Delhi.";
        assertTrue(detector.isValid(answer, context));
    }

    @Test
    @DisplayName("16. Wrong doctor/entity → FAIL")
    void testWrongDoctorEntity_Fails() {
        String context = "Dr. Sharma provides dental consultation in Delhi.";
        String answer = "Dr. Verma provides dental consultation in Delhi.";
        assertFalse(detector.isValid(answer, context));
    }

    @Test
    @DisplayName("17. Supported paraphrase → PASS")
    void testSupportedParaphrase_Passes() {
        String contextTime = "The clinic is open from 9 AM until 6 PM.";
        String answerTime = "You can visit the clinic between 9 in the morning and 6 in the evening.";
        assertTrue(detector.isValid(answerTime, contextTime));

        String contextFee = "The consultation charge is ₹500.";
        String answerFee = "A consultation costs five hundred rupees.";
        assertTrue(detector.isValid(answerFee, contextFee));
    }

    @Test
    @DisplayName("18. Unsupported mixed claim (1 supported + 1 unsupported) → FAIL")
    void testUnsupportedMixedClaim_Fails() {
        String context = "Consultation fee is ₹500. Clinic is open 9 AM to 6 PM.";
        String answer = "Consultation costs ₹500 and the clinic is open 10 AM to 8 PM.";
        assertFalse(detector.isValid(answer, context));
    }

    @Test
    @DisplayName("19. Empty context + generic response → PASS")
    void testEmptyContext_GenericResponse_Passes() {
        String answer = "Hello! Welcome to our CRM. How can I assist you today?";
        assertTrue(detector.isValid(answer, ""));
        assertTrue(detector.isValid(answer, null));
    }

    @Test
    @DisplayName("20. Empty context + invented price → FAIL")
    void testEmptyContext_InventedPrice_Fails() {
        String answer = "Our premium plan costs ₹1500 per month.";
        assertFalse(detector.isValid(answer, ""));
    }

    @Test
    @DisplayName("21. Existing refusal handling → GROUNDED_REFUSAL")
    void testRefusalHandling_Fails() {
        String context = "Some context here.";
        String answer = "I don't know based on the provided information.";
        assertEquals(HallucinationCheckResult.GROUNDED_REFUSAL, detector.check(answer, context, null));
        assertFalse(detector.isValid(answer, context));
    }

    @Test
    @DisplayName("22. Hedging behavior (2+ hedging phrases) → PASS (no longer fails)")
    void testExcessiveHedging_Passes() {
        String context = "Fee is ₹500.";
        String answer = "I think it is possible that the fee is ₹500, but perhaps it might vary.";
        assertTrue(detector.isValid(answer, context));
        assertEquals(HallucinationCheckResult.GROUNDED, detector.check(answer, context, null));
    }

    @Test
    @DisplayName("23. Semantic check skipped if AiOrchestrator is missing → GROUNDED")
    void testSemanticCheckSkipped() {
        String context2 = "Dental implants start at ₹40,000.";
        String answer2 = "Dental implants start at ₹40,000 and include a free consultation.";
        assertEquals(HallucinationCheckResult.GROUNDED, detector.check(answer2, context2, null));
    }
}
