package com.chatcrmlite.backend.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class TimezoneController {

    public static class TimezoneDto {
        private String id;
        private String name;
        private String offset;

        public TimezoneDto(String id, String name, String offset) {
            this.id = id;
            this.name = name;
            this.offset = offset;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getOffset() { return offset; }
    }

    public static class CountryDto {
        private String code;
        private String name;
        private String currency;
        private String defaultTimezone;

        public CountryDto(String code, String name, String currency, String defaultTimezone) {
            this.code = code;
            this.name = name;
            this.currency = currency;
            this.defaultTimezone = defaultTimezone;
        }

        public String getCode() { return code; }
        public String getName() { return name; }
        public String getCurrency() { return currency; }
        public String getDefaultTimezone() { return defaultTimezone; }
    }

    @GetMapping("/timezones")
    public ResponseEntity<List<TimezoneDto>> getTimezones() {
        List<TimezoneDto> list = new ArrayList<>();
        Instant now = Instant.now();

        Set<String> zoneIds = ZoneId.getAvailableZoneIds();
        List<String> sortedZoneIds = new ArrayList<>(zoneIds);
        Collections.sort(sortedZoneIds);

        for (String zoneIdStr : sortedZoneIds) {
            if (zoneIdStr.startsWith("Etc/") || zoneIdStr.startsWith("SystemV/") || zoneIdStr.contains("GMT")) {
                continue;
            }
            try {
                ZoneId zoneId = ZoneId.of(zoneIdStr);
                ZonedDateTime zdt = now.atZone(zoneId);
                ZoneOffset offset = zdt.getOffset();
                
                String offsetStr = offset.getId().equals("Z") ? "+00:00" : offset.getId();
                String displayName = String.format("%s (UTC%s)", zoneIdStr, offsetStr);
                
                list.add(new TimezoneDto(zoneIdStr, displayName, offsetStr));
            } catch (Exception ignored) {
            }
        }

        list.sort((a, b) -> {
            int comp = a.getOffset().compareTo(b.getOffset());
            if (comp != 0) return comp;
            return a.getId().compareTo(b.getId());
        });

        return ResponseEntity.ok(list);
    }

    @GetMapping("/countries")
    public ResponseEntity<List<CountryDto>> getCountries() {
        List<CountryDto> countries = new ArrayList<>();
        for (String countryCode : Locale.getISOCountries()) {
            Locale locale = new Locale("", countryCode);
            String countryName = locale.getDisplayCountry(Locale.ENGLISH);
            
            Currency currency;
            try {
                currency = Currency.getInstance(locale);
            } catch (Exception e) {
                currency = null;
            }
            String currencyCode = currency != null ? currency.getCurrencyCode() : "USD";

            String defaultTz = getDefaultTimezoneForCountry(countryCode);

            countries.add(new CountryDto(countryCode, countryName, currencyCode, defaultTz));
        }

        countries.sort(Comparator.comparing(CountryDto::getName));
        return ResponseEntity.ok(countries);
    }

    private String getDefaultTimezoneForCountry(String code) {
        switch (code.toUpperCase()) {
            case "IN": return "Asia/Kolkata";
            case "US": return "America/New_York";
            case "GB": return "Europe/London";
            case "AE": return "Asia/Dubai";
            case "AU": return "Australia/Sydney";
            case "CA": return "America/Toronto";
            case "DE": return "Europe/Berlin";
            case "FR": return "Europe/Paris";
            case "JP": return "Asia/Tokyo";
            case "SG": return "Asia/Singapore";
            case "BR": return "America/Sao_Paulo";
            case "MX": return "America/Mexico_City";
            case "ZA": return "Africa/Johannesburg";
            case "NZ": return "Pacific/Auckland";
            default: return "UTC";
        }
    }
}
