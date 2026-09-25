package com.chatcrmlite.backend.services.whatsapp;

public record MetaCallingLimits(
    int initiationLimit24h,       // Default 10,000 / 24h
    int connectedLimit24h,        // 100 for Graph WebRTC mode; SIP account-resolved for SIP mode
    int permissionRequestLimit24h, // Default 1 / 24h per user
    int permissionRequestLimit7d   // Default 2 / 7d per user
) {
    public static MetaCallingLimits defaultGraphLimits() {
        return new MetaCallingLimits(10000, 100, 1, 2);
    }

    public static MetaCallingLimits defaultSipLimits() {
        return new MetaCallingLimits(10000, 5, 1, 2);
    }
}
