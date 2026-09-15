package com.chatcrmlite.backend.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatResponseFormatterTest {

    @Test
    void convertsMarkdownTableToBullets() {
        String raw = """
                From the Products sheet, the only product with a price greater than ₹20,000 is:

                | Product | Category | Price (₹) |
                |---|---|---|
                | AI CRM Enterprise | AI CRM | ₹24,999 |

                For reference, others fall below ₹20,000:
                """;
        String out = ChatResponseFormatter.forChatWidget(raw);
        assertFalse(out.contains("|---|"));
        assertFalse(out.contains("| Product |"));
        assertTrue(out.contains("• AI CRM Enterprise — AI CRM — ₹24,999"));
    }

    @Test
    void stripsTruncationNotes() {
        String raw = """
                • AI CRM Starter — ₹4,999

                ⚠️ Note: The product list appears truncated — additional products like WhatsApp...
                """;
        String out = ChatResponseFormatter.forChatWidget(raw);
        assertTrue(out.contains("AI CRM Starter"));
        assertFalse(out.toLowerCase().contains("truncated"));
        assertFalse(out.contains("⚠️"));
    }

    @Test
    void stripsKnowledgeBaseIntroAndTechnicalMissingField() {
        String raw = """
                Based on the product data available in my knowledge base, here's what I found:

                I don't have specific information about the number of AI agents per product — my knowledge base doesn't contain a field tracking AI agent counts for individual products.
                """;
        String out = ChatResponseFormatter.forChatWidget(raw);
        assertFalse(out.toLowerCase().contains("knowledge base"));
        assertFalse(out.toLowerCase().contains("here's what i found"));
        assertFalse(out.toLowerCase().contains("field tracking"));
        assertTrue(out.toLowerCase().contains("don't have that detail"));
    }

    @Test
    void convertsRawRowDumpToBullet() {
        String raw = "Row: Product_Name: AI CRM Pro | Category: AI CRM | Price_INR: 9999";
        String out = ChatResponseFormatter.forChatWidget(raw);
        assertTrue(out.startsWith("• "));
        assertFalse(out.contains("Row:"));
        assertTrue(out.contains("AI CRM Pro"));
        assertTrue(out.contains("9999"));
    }

    @Test
    void looksRoboticDetectsKnowledgeBase() {
        assertTrue(ChatResponseFormatter.looksRobotic("Based on my knowledge base, here are products."));
        assertFalse(ChatResponseFormatter.looksRobotic("• AI CRM Pro — ₹9,999"));
    }
}
