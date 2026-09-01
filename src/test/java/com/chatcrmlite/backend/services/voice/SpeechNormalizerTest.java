package com.chatcrmlite.backend.services.voice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpeechNormalizerTest {

    private SpeechNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new SpeechNormalizer();
    }

    @Test
    @DisplayName("Should normalize Indian Rupee currency representations into speakable text")
    void testCurrencyNormalization() {
        assertEquals("The consultation fee is 1,299 rupees only.", 
                normalizer.normalize("The consultation fee is ₹1,299 only."));
        assertEquals("Total cost is 500 rupees per session.", 
                normalizer.normalize("Total cost is Rs. 500 per session."));
    }

    @Test
    @DisplayName("Should normalize URLs and markdown links into clean speech")
    void testUrlAndLinkNormalization() {
        String input = "Please [Book Appointment](https://example.com/book) or visit https://gyanvaniai.online/contact for details.";
        String output = normalizer.normalize(input);
        assertFalse(output.contains("http://"));
        assertFalse(output.contains("https://"));
        assertTrue(output.contains("Book Appointment"));
        assertTrue(output.contains("our website at gyanvaniai dot online contact"));
    }

    @Test
    @DisplayName("Should strip emojis and markdown formatting without reciting asterisks")
    void testMarkdownAndEmojiScrubbing() {
        String input = "Hello! 👋 Welcome to **GyanVani AI**! 🚀\n* Feature 1\n* Feature 2";
        String output = normalizer.normalize(input);
        assertFalse(output.contains("👋"));
        assertFalse(output.contains("🚀"));
        assertFalse(output.contains("*"));
        assertTrue(output.contains("Welcome to GyanVani AI!"));
    }

    @Test
    @DisplayName("Should normalize phone numbers into spaced cadence")
    void testPhoneNumberNormalization() {
        String input = "Call us at 9876543210 for emergency support.";
        String output = normalizer.normalize(input);
        assertTrue(output.contains("9 8 7 6 5 4 3 2 1 0"));
    }
}
