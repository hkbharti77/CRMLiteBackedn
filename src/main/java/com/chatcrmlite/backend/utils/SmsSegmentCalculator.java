package com.chatcrmlite.backend.utils;

import lombok.Builder;
import lombok.Data;

import java.util.regex.Pattern;

public class SmsSegmentCalculator {

    // Standard GSM 03.38 character set regex pattern
    private static final Pattern GSM_7_PATTERN = Pattern.compile("^[A-Za-z0-9 \\r\\n@£$¥èéùìòÇØøÅåΔ_ΦΓΛΩΠΨΣΘΞ\\^{}\\\\[~\\\\]|€ÆæßÉ!\"#%&'()*+,-./:;<=>?]*$");

    @Data
    @Builder
    public static class SegmentInfo {
        private String encoding; // GSM-7 or UNICODE
        private int charCount;
        private int segments;
    }

    public static SegmentInfo calculateSegments(String text) {
        if (text == null || text.isEmpty()) {
            return SegmentInfo.builder()
                    .encoding("GSM-7")
                    .charCount(0)
                    .segments(1)
                    .build();
        }

        boolean isGsm7 = GSM_7_PATTERN.matcher(text).matches();
        int charCount = text.length();
        int segments;

        if (isGsm7) {
            if (charCount <= 160) {
                segments = 1;
            } else {
                segments = (int) Math.ceil((double) charCount / 153.0);
            }
            return SegmentInfo.builder()
                    .encoding("GSM-7")
                    .charCount(charCount)
                    .segments(segments)
                    .build();
        } else {
            if (charCount <= 70) {
                segments = 1;
            } else {
                segments = (int) Math.ceil((double) charCount / 67.0);
            }
            return SegmentInfo.builder()
                    .encoding("UNICODE")
                    .charCount(charCount)
                    .segments(segments)
                    .build();
        }
    }
}
