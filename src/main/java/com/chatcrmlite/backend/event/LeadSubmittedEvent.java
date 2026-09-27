package com.chatcrmlite.backend.event;

import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.WhatsAppAttribution;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

@Getter
public class LeadSubmittedEvent extends ApplicationEvent {

    private final Lead lead;
    private final WhatsAppAttribution attribution;

    public LeadSubmittedEvent(Object source, Lead lead, WhatsAppAttribution attribution) {
        super(source);
        this.lead = lead;
        this.attribution = attribution;
    }
}
