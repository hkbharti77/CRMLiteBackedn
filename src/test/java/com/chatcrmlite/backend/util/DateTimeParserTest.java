package com.chatcrmlite.backend.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DateTimeParserTest {

    @Test
    void testParseDayMonthFormats() {
        LocalDateTime dt9Sep = DateTimeParser.parse("9 sep");
        assertEquals(9, dt9Sep.getDayOfMonth());
        assertEquals(Month.SEPTEMBER, dt9Sep.getMonth());

        LocalDateTime dt20Oct = DateTimeParser.parse("20 oct");
        assertEquals(20, dt20Oct.getDayOfMonth());
        assertEquals(Month.OCTOBER, dt20Oct.getMonth());
    }

    @Test
    void testParseOrdinalSuffixes() {
        LocalDateTime dt9th = DateTimeParser.parse("9th Sep");
        assertEquals(9, dt9th.getDayOfMonth());
        assertEquals(Month.SEPTEMBER, dt9th.getMonth());

        LocalDateTime dt20th = DateTimeParser.parse("20th October");
        assertEquals(20, dt20th.getDayOfMonth());
        assertEquals(Month.OCTOBER, dt20th.getMonth());
    }

    @Test
    void testParseMonthDayFormats() {
        LocalDateTime dtSep9 = DateTimeParser.parse("Sep 9");
        assertEquals(9, dtSep9.getDayOfMonth());
        assertEquals(Month.SEPTEMBER, dtSep9.getMonth());

        LocalDateTime dtOct20 = DateTimeParser.parse("October 20");
        assertEquals(20, dtOct20.getDayOfMonth());
        assertEquals(Month.OCTOBER, dtOct20.getMonth());
    }

    @Test
    void testParseNumericFormats() {
        LocalDateTime dt1 = DateTimeParser.parse("09/09/2026");
        assertEquals(LocalDate.of(2026, 9, 9), dt1.toLocalDate());

        LocalDateTime dt2 = DateTimeParser.parse("20/10/2026");
        assertEquals(LocalDate.of(2026, 10, 20), dt2.toLocalDate());

        LocalDateTime dt3 = DateTimeParser.parse("2026-10-20");
        assertEquals(LocalDate.of(2026, 10, 20), dt3.toLocalDate());
    }

    @Test
    void testExtractAndParseFromMapWithDifferentKeys() {
        Map<String, String> mapPreferredDate = new HashMap<>();
        mapPreferredDate.put("preferred_date", "9 sep");
        LocalDateTime dt1 = DateTimeParser.extractAndParse(mapPreferredDate);
        assertEquals(9, dt1.getDayOfMonth());
        assertEquals(Month.SEPTEMBER, dt1.getMonth());

        Map<String, String> mapDateAndTime = new HashMap<>();
        mapDateAndTime.put("date", "20 Oct");
        mapDateAndTime.put("time", "4 PM");
        LocalDateTime dt2 = DateTimeParser.extractAndParse(mapDateAndTime);
        assertEquals(20, dt2.getDayOfMonth());
        assertEquals(Month.OCTOBER, dt2.getMonth());
        assertEquals(16, dt2.getHour());

        Map<String, String> mapPreferredSlot = new HashMap<>();
        mapPreferredSlot.put("preferred_slot", "20 Oct 11:30 am");
        LocalDateTime dt3 = DateTimeParser.extractAndParse(mapPreferredSlot);
        assertEquals(20, dt3.getDayOfMonth());
        assertEquals(Month.OCTOBER, dt3.getMonth());
        assertEquals(11, dt3.getHour());
        assertEquals(30, dt3.getMinute());
    }

    @Test
    void testRelativeDateKeywords() {
        LocalDateTime tmrw = DateTimeParser.parse("tomorrow");
        assertEquals(LocalDate.now().plusDays(1), tmrw.toLocalDate());

        LocalDateTime today = DateTimeParser.parse("today");
        assertEquals(LocalDate.now(), today.toLocalDate());
    }
}
