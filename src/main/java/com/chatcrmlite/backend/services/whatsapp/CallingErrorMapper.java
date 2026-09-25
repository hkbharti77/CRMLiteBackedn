package com.chatcrmlite.backend.services.whatsapp;

public class CallingErrorMapper {

    public record CallingErrorDetails(String internalCode, boolean isRetryable, String description) {}

    public static CallingErrorDetails map(int metaErrorCode) {
        return switch (metaErrorCode) {
            case 100 -> new CallingErrorDetails("INVALID_REQUEST_SCHEMA", false, "Request payload missing required fields");
            case 613 -> new CallingErrorDetails("CALL_PERMISSION_API_RATE_LIMIT", true, "Call permission API rate limit reached");
            case 131009 -> new CallingErrorDetails("VOICE_CALL_MESSAGE_NOT_SUPPORTED", false, "Voice call interactive message type is not supported");
            case 131030 -> new CallingErrorDetails("PTN_RECIPIENT_NOT_ALLOWED", false, "Phone number test recipient not on allowed recipient list");
            case 131044 -> new CallingErrorDetails("PAYMENT_ERROR", false, "Payment error encountered on user-initiated calling leg");
            case 131055 -> new CallingErrorDetails("GRAPH_CALLING_FORBIDDEN_ON_SIP", false, "Phone number is configured for SIP calling; Graph API calling is not permitted");
            case 138000 -> new CallingErrorDetails("CALLING_NOT_ENABLED", false, "WhatsApp calling is not enabled on this phone number");
            case 138001 -> new CallingErrorDetails("RECEIVER_UNCALLABLE", false, "Receiver cannot receive calls or privacy settings prevent calling");
            case 138002 -> new CallingErrorDetails("CONCURRENT_CALL_LIMIT", true, "Concurrent call limit reached on phone number");
            case 138003 -> new CallingErrorDetails("DUPLICATE_CALL", false, "Call with specified ID already exists or in progress");
            case 138004 -> new CallingErrorDetails("CONNECTION_ERROR", true, "General signaling connection error with Meta");
            case 138005 -> new CallingErrorDetails("CALL_RATE_LIMIT", true, "Call initiation rate limit exceeded for this 24-hour window");
            case 138006 -> new CallingErrorDetails("CALL_PERMISSION_DENIED", false, "No approved call permission exists for this business-initiated call");
            case 138007 -> new CallingErrorDetails("CONNECT_TIMEOUT", true, "Connect or SDP offer/answer negotiation timed out");
            case 138009 -> new CallingErrorDetails("PERMISSION_REQUEST_LIMIT", true, "Call permission request rate limit reached (1/24h or 2/7d)");
            case 138012 -> new CallingErrorDetails("BIC_CONNECTED_CALL_LIMIT", true, "24-hour business-initiated connected call limit reached");
            case 138013 -> new CallingErrorDetails("BIC_UNAVAILABLE", false, "Business-initiated calling unavailable for this business number country");
            case 138014 -> new CallingErrorDetails("CALLING_TEMPORARILY_DISABLED", true, "Calling temporarily disabled due to low quality rating");
            case 138015 -> new CallingErrorDetails("CALLING_CANNOT_BE_ENABLED", false, "Phone number does not satisfy prerequisites to enable calling");
            case 138017 -> new CallingErrorDetails("PERMANENT_PERMISSION_EXISTS", false, "Permanent call permission already exists for this user");
            case 138018 -> new CallingErrorDetails("CALLING_PREREQUISITES_NOT_MET", false, "Calling prerequisites (messaging tier, 2FA, verified name) not met");
            case 138019 -> new CallingErrorDetails("CALL_SETUP_FAILED", true, "Call setup failed during media/signaling negotiation");
            case 138020 -> new CallingErrorDetails("RELAY_CONNECTION_FAILED", true, "Failed to connect to Meta media relay");
            case 138021 -> new CallingErrorDetails("MEDIA_RECEIVE_TIMEOUT", true, "Timed out waiting to receive media from Meta");
            case 138022 -> new CallingErrorDetails("MEDIA_TRANSMIT_TIMEOUT", true, "Timed out sending media to Meta relay");
            case 138023 -> new CallingErrorDetails("ACCEPTED_NO_MEDIA", true, "Call was accepted but no WebRTC media connection signals established");
            default -> new CallingErrorDetails("UNKNOWN_CALL_ERROR", true, "Meta Error Code: " + metaErrorCode);
        };
    }
}
