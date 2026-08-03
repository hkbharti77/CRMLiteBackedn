package com.chatcrmlite.backend.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Month;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Robust date and time parsing utility for appointment and booking systems.
 * Supports extracting date/time from multi-key collected flow maps and parsing
 * natural language formats (e.g. "9 Sep", "20 Oct", "9th Sep", "20th Oct", "Sep 9", "09/09/2026", "20/10/2026", "tomorrow").
 */
public class DateTimeParser {
    private static final Logger log = LoggerFactory.getLogger(DateTimeParser.class);

    private static final List<String> DATE_KEYS = List.of(
            "date_time", "preferred_date", "appointment_date", "date",
            "event_date", "preferred_slot", "slot", "datetime",
            "date_and_time", "booking_date"
    );

    private static final List<String> TIME_KEYS = List.of(
            "time", "preferred_time", "appointment_time",
            "preferred_contact_time", "time_slot"
    );

    private static final Pattern ORDINAL_PATTERN = Pattern.compile("(?i)(\\d{1,2})(st|nd|rd|th)");

    private static final Pattern DAY_MONTH_PATTERN = Pattern.compile("(?i)^.*?(\\d{1,2})\\s*([a-zA-Z]{3,9})(?:\\s*(\\d{4}))?.*$");
    private static final Pattern MONTH_DAY_PATTERN = Pattern.compile("(?i)^.*?([a-zA-Z]{3,9})\\s*(\\d{1,2})(?:\\s*,?\\s*(\\d{4}))?.*$");

    private static final Pattern FULL_NUMERIC_DATE_PATTERN = Pattern.compile("(\\d{4})[/-](\\d{1,2})[/-](\\d{1,2})");
    private static final Pattern DMY_NUMERIC_DATE_PATTERN = Pattern.compile("(\\d{1,2})[/-](\\d{1,2})[/-](\\d{2,4})");
    private static final Pattern SHORT_NUMERIC_DATE_PATTERN = Pattern.compile("(\\d{1,2})[/-](\\d{1,2})");

    private static final Pattern TIME_12H_PATTERN = Pattern.compile("(?i)(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)");
    private static final Pattern TIME_24H_PATTERN = Pattern.compile("(?:at\\s+)?(\\d{1,2}):(\\d{2})");

    /**
     * Extracts date and time fields from collected flow data map and parses them.
     */
    public static LocalDateTime extractAndParse(Map<String, String> data) {
        if (data == null || data.isEmpty()) {
            return getDefaultFallback();
        }

        String dateVal = null;
        for (String key : DATE_KEYS) {
            String val = data.get(key);
            if (val != null && !val.isBlank() && looksLikeDate(val)) {
                dateVal = val.trim();
                break;
            }
        }

        String timeVal = null;
        for (String key : TIME_KEYS) {
            String val = data.get(key);
            if (val != null && !val.isBlank()) {
                timeVal = val.trim();
                break;
            }
        }

        if (dateVal != null && timeVal != null) {
            return parse(dateVal + " " + timeVal);
        } else if (dateVal != null) {
            return parse(dateVal);
        } else if (timeVal != null) {
            LocalTime t = parseTime(timeVal);
            return LocalDate.now().plusDays(1).atTime(t);
        }

        return getDefaultFallback();
    }

    private static boolean looksLikeDate(String val) {
        if (val == null || val.isBlank()) return false;
        String lower = val.toLowerCase().trim();
        // Exclude common service/option names if mistakenly stored under date key
        if (lower.contains("installation") || lower.contains("setup") || lower.contains("demo") 
                || lower.contains("repair") || lower.contains("maintenance") || lower.contains("pricing")) {
            return false;
        }
        // Must contain digits or relative date terms
        if (lower.contains("today") || lower.contains("tomorrow") || lower.contains("kal") || lower.contains("aaj") || lower.contains("parso")) {
            return true;
        }
        return val.matches(".*\\d.*") || lower.matches(".*(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec).*");
    }

    /**
     * Parses a raw date string (e.g. "9 Sep", "20 Oct", "9th Sep 4 PM", "20/10/2026", "tomorrow") into a LocalDateTime.
     */
    public static LocalDateTime parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return getDefaultFallback();
        }

        String clean = raw.trim();
        String lower = clean.toLowerCase();

        // Extract time component if present
        LocalTime extractedTime = parseTime(clean);

        // Check relative date terms
        LocalDate resolvedDate = null;
        if (lower.contains("tomorrow") || lower.contains("kal")) {
            resolvedDate = LocalDate.now().plusDays(1);
        } else if (lower.contains("day after tomorrow") || lower.contains("parso")) {
            resolvedDate = LocalDate.now().plusDays(2);
        } else if (lower.contains("today") || lower.contains("aaj")) {
            resolvedDate = LocalDate.now();
        }

        if (resolvedDate != null) {
            return resolvedDate.atTime(extractedTime);
        }

        // Standard ISO parses
        try { return LocalDateTime.parse(clean); } catch (Exception ignored) {}
        try { return LocalDate.parse(clean).atTime(extractedTime); } catch (Exception ignored) {}

        // Remove ordinal suffixes (e.g., 9th -> 9, 20th -> 20)
        String normalized = ORDINAL_PATTERN.matcher(clean).replaceAll("$1");

        // Try Day-Month pattern ("9 Sep", "9 September 2026")
        try {
            Matcher m = DAY_MONTH_PATTERN.matcher(normalized);
            if (m.find()) {
                int day = Integer.parseInt(m.group(1));
                Month month = parseMonth(m.group(2));
                if (month != null) {
                    int year = m.group(3) != null ? Integer.parseInt(m.group(3)) : resolveYear(month, day);
                    return LocalDate.of(year, month, day).atTime(extractedTime);
                }
            }
        } catch (Exception ignored) {}

        // Try Month-Day pattern ("Sep 9", "October 20", "Sep 9 2026")
        try {
            Matcher m = MONTH_DAY_PATTERN.matcher(normalized);
            if (m.find()) {
                Month month = parseMonth(m.group(1));
                int day = Integer.parseInt(m.group(2));
                if (month != null) {
                    int year = m.group(3) != null ? Integer.parseInt(m.group(3)) : resolveYear(month, day);
                    return LocalDate.of(year, month, day).atTime(extractedTime);
                }
            }
        } catch (Exception ignored) {}

        // Try YYYY-MM-DD numeric
        try {
            Matcher m = FULL_NUMERIC_DATE_PATTERN.matcher(normalized);
            if (m.find()) {
                int year = Integer.parseInt(m.group(1));
                int month = Integer.parseInt(m.group(2));
                int day = Integer.parseInt(m.group(3));
                return LocalDate.of(year, month, day).atTime(extractedTime);
            }
        } catch (Exception ignored) {}

        // Try DD/MM/YYYY or MM/DD/YYYY
        try {
            Matcher m = DMY_NUMERIC_DATE_PATTERN.matcher(normalized);
            if (m.find()) {
                int first = Integer.parseInt(m.group(1));
                int second = Integer.parseInt(m.group(2));
                int rawYear = Integer.parseInt(m.group(3));
                int year = rawYear < 100 ? 2000 + rawYear : rawYear;

                int day, monthInt;
                if (second > 12 && first <= 12) {
                    monthInt = first;
                    day = second;
                } else {
                    day = first;
                    monthInt = second;
                }
                if (monthInt >= 1 && monthInt <= 12 && day >= 1 && day <= 31) {
                    return LocalDate.of(year, monthInt, day).atTime(extractedTime);
                }
            }
        } catch (Exception ignored) {}

        // Try DD/MM
        try {
            Matcher m = SHORT_NUMERIC_DATE_PATTERN.matcher(normalized);
            if (m.find()) {
                int day = Integer.parseInt(m.group(1));
                int monthInt = Integer.parseInt(m.group(2));
                if (monthInt >= 1 && monthInt <= 12 && day >= 1 && day <= 31) {
                    Month month = Month.of(monthInt);
                    int year = resolveYear(month, day);
                    return LocalDate.of(year, monthInt, day).atTime(extractedTime);
                }
            }
        } catch (Exception ignored) {}

        log.warn("[DateTimeParser] Could not parse date text: '{}', using fallback tomorrow 10AM", raw);
        return getDefaultFallback();
    }

    private static Month parseMonth(String monthStr) {
        if (monthStr == null || monthStr.isBlank()) return null;
        String mLower = monthStr.toLowerCase();
        if (mLower.startsWith("sept")) return Month.SEPTEMBER;

        for (Month m : Month.values()) {
            String nameLower = m.name().toLowerCase();
            if (nameLower.startsWith(mLower.substring(0, Math.min(3, mLower.length())))) {
                return m;
            }
        }
        return null;
    }

    private static int resolveYear(Month month, int day) {
        LocalDate now = LocalDate.now();
        int year = now.getYear();
        try {
            LocalDate target = LocalDate.of(year, month, day);
            if (target.isBefore(now.minusDays(30))) {
                year++;
            }
        } catch (Exception ignored) {}
        return year;
    }

    public static LocalTime parseTime(String raw) {
        if (raw == null || raw.isBlank()) return LocalTime.of(10, 0);

        // 12-hour AM/PM matching
        Matcher m12 = TIME_12H_PATTERN.matcher(raw);
        if (m12.find()) {
            int hour = Integer.parseInt(m12.group(1));
            int minute = m12.group(2) != null ? Integer.parseInt(m12.group(2)) : 0;
            String ampm = m12.group(3).toLowerCase();
            if ("pm".equals(ampm) && hour < 12) hour += 12;
            if ("am".equals(ampm) && hour == 12) hour = 0;
            return LocalTime.of(hour, minute);
        }

        // 24-hour HH:MM matching
        Matcher m24 = TIME_24H_PATTERN.matcher(raw);
        if (m24.find()) {
            int hour = Integer.parseInt(m24.group(1));
            int minute = Integer.parseInt(m24.group(2));
            if (hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59) {
                return LocalTime.of(hour, minute);
            }
        }

        // Textual time windows
        String lower = raw.toLowerCase();
        if (lower.contains("afternoon") || lower.contains("12–4pm") || lower.contains("12-4pm")) {
            return LocalTime.of(14, 0);
        }
        if (lower.contains("evening") || lower.contains("4pm–8pm") || lower.contains("4pm-8pm")) {
            return LocalTime.of(17, 0);
        }
        if (lower.contains("morning") || lower.contains("9am–12pm") || lower.contains("9am-12pm")) {
            return LocalTime.of(10, 0);
        }

        return LocalTime.of(10, 0);
    }

    public static LocalDateTime getDefaultFallback() {
        return LocalDateTime.now().plusDays(1)
                .withHour(10).withMinute(0).withSecond(0).withNano(0);
    }
}
