package com.chatcrmlite.backend.services.whatsapp.checkout;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CheckoutAddressParserTest {

    private CheckoutAddressParser parser;

    @BeforeEach
    void setUp() {
        parser = new CheckoutAddressParser();
    }

    @Test
    void testStructuredAddressParsing() {
        String input = "Name: Rahul Sharma\n" +
                "Address: Flat 402, Sunshine Heights, Andheri West\n" +
                "City: Mumbai\n" +
                "State: Maharashtra\n" +
                "PIN: 400053\n" +
                "Email: rahul.sharma@example.com";

        ParsedAddress result = parser.parse(input, "Rahul");

        assertTrue(result.isValid());
        assertEquals("Rahul Sharma", result.getShippingName());
        assertEquals("Flat 402, Sunshine Heights, Andheri West", result.getAddressLine1());
        assertEquals("Mumbai", result.getCity());
        assertEquals("Maharashtra", result.getState());
        assertEquals("400053", result.getPostalCode());
        assertEquals("rahul.sharma@example.com", result.getCustomerEmail());
        assertFalse(result.getFullFormattedAddress().isBlank());
    }

    @Test
    void testFreeformAddressParsing() {
        String input = "Plot 14, Sector 18, Cyber City, Gurgaon, 122002, test.user@gmail.com";

        ParsedAddress result = parser.parse(input, "Test User");

        assertTrue(result.isValid());
        assertEquals("122002", result.getPostalCode());
        assertEquals("test.user@gmail.com", result.getCustomerEmail());
        assertTrue(result.getAddressLine1().contains("Plot 14"));
    }

    @Test
    void testPartialOnlyEmailProvided() {
        String input = "my email is customer123@gmail.com";

        ParsedAddress result = parser.parse(input, "Customer");

        assertFalse(result.isValid());
        assertEquals("customer123@gmail.com", result.getCustomerEmail());
        assertTrue(result.isHasOnlyEmail());
        assertTrue(result.getMissingFields().contains("Street / House address"));
        assertTrue(result.getMissingFields().contains("6-digit Pincode"));
    }

    @Test
    void testPartialOnlyAddressProvided() {
        String input = "Flat 101, Blue Ridge, Hinjewadi, Pune - 411057";

        ParsedAddress result = parser.parse(input, "Vikram");

        assertFalse(result.isValid());
        assertEquals("411057", result.getPostalCode());
        assertNull(result.getCustomerEmail());
        assertTrue(result.isHasOnlyAddress());
        assertTrue(result.getMissingFields().contains("Email address (e.g. name@example.com)"));
    }

    @Test
    void testEmptyInput() {
        ParsedAddress result = parser.parse("", "Default Customer");

        assertFalse(result.isValid());
        assertEquals("Default Customer", result.getShippingName());
        assertFalse(result.getMissingFields().isEmpty());
    }
}
