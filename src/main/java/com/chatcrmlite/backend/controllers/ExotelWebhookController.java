package com.chatcrmlite.backend.controllers;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/exotel")
public class ExotelWebhookController {

    /**
     * Called by Exotel when a new inbound call is received on the Exophone.
     * We return ExoML with the <Stream> verb to start the WebSocket.
     */
    @PostMapping(value = "/incoming", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> handleIncomingCall(
            @RequestParam Map<String, String> exotelParams,
            @RequestHeader Map<String, String> headers) {
        
        // TODO: Validate Exotel Signature

        String callSid = exotelParams.get("CallSid");
        String from = exotelParams.get("From");
        String to = exotelParams.get("To");
        
        log.info("Incoming Exotel Call - CallSid: {}, From: {}, To: {}", callSid, from, to);

        // The WebSocket URL must be public. Typically handled by ngrok in dev or a domain in prod.
        // E.g. wss://<domain>/ws/exotel/stream
        // Exotel requires a fully qualified URL for the stream.
        String streamUrl = System.getenv("EXOTEL_STREAM_URL");
        if (streamUrl == null || streamUrl.isBlank()) {
            streamUrl = "wss://api.yourdomain.com/ws/exotel/stream"; // Fallback placeholder
        }

        // ExoML to connect to AgentStream
        String exoml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<Response>\n" +
                "    <Stream url=\"" + streamUrl + "\" track=\"both_tracks\" bidirectional=\"true\">\n" +
                "    </Stream>\n" +
                "</Response>";

        return ResponseEntity.ok(exoml);
    }
}
