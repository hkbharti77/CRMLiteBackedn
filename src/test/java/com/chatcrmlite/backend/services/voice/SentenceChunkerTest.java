package com.chatcrmlite.backend.services.voice;

import org.junit.jupiter.api.Test;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SentenceChunkerTest {

    @Test
    void testBasicEnglishChunking() {
        SentenceChunker chunker = new SentenceChunker();
        
        List<String> result1 = chunker.accept("Hello! ");
        assertEquals(1, result1.size());
        assertEquals("Hello!", result1.get(0));

        List<String> result2 = chunker.accept("How are you today? I am fine.");
        assertEquals(2, result2.size());
        assertEquals("How are you today?", result2.get(0));
        assertEquals("I am fine.", result2.get(1));
    }

    @Test
    void testHindiChunkingWithDanda() {
        SentenceChunker chunker = new SentenceChunker();
        
        List<String> result1 = chunker.accept("Hello! मेरा नाम Himanshu है। How can ");
        assertEquals(2, result1.size()); // "Hello!" and "मेरा नाम Himanshu है।"
        assertEquals("Hello!", result1.get(0));
        assertEquals("मेरा नाम Himanshu है।", result1.get(1));
        
        List<String> result2 = chunker.accept("I help"); // No punctuation yet
        assertTrue(result2.isEmpty());
        
        List<String> flushed = chunker.flush();
        assertEquals(1, flushed.size());
        assertEquals("How can I help", flushed.get(0));
    }

    @Test
    void testAbbreviationsAreNotChunked() {
        SentenceChunker chunker = new SentenceChunker();
        
        List<String> result = chunker.accept("Mr. Smith went to Dr. Jones. It cost Rs. 500.");
        assertEquals(2, result.size()); 
        assertEquals("Mr. Smith went to Dr. Jones.", result.get(0));
        assertEquals("It cost Rs. 500.", result.get(1));
    }

    @Test
    void testDecimalsAreNotChunked() {
        SentenceChunker chunker = new SentenceChunker();
        
        List<String> result = chunker.accept("The price is ₹10.50 today.");
        assertEquals(1, result.size());
        assertEquals("The price is ₹10.50 today.", result.get(0));
    }
}
