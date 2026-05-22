package com.chatcrmlite.backend.flow;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.ConversationState.FlowType;
import com.chatcrmlite.backend.models.User;

import java.util.Map;

/**
 * Immutable context object passed to a {@link FlowHandler} when a flow completes.
 */
public class FlowContext {

    private final Contact contact;
    private final User owner;
    private final FlowType flowType;
    private final Map<String, String> collectedData;
    private final String messageId;

    public FlowContext(Contact contact, User owner, FlowType flowType, Map<String, String> collectedData, String messageId) {
        this.contact = contact;
        this.owner = owner;
        this.flowType = flowType;
        this.collectedData = collectedData;
        this.messageId = messageId;
    }

    public Contact getContact() { return contact; }
    public User getOwner() { return owner; }
    public FlowType getFlowType() { return flowType; }
    public Map<String, String> getCollectedData() { return collectedData; }
    public String getMessageId() { return messageId; }

    public static FlowContextBuilder builder() {
        return new FlowContextBuilder();
    }

    public static class FlowContextBuilder {
        private Contact contact;
        private User owner;
        private FlowType flowType;
        private Map<String, String> collectedData;
        private String messageId;

        public FlowContextBuilder contact(Contact contact) { this.contact = contact; return this; }
        public FlowContextBuilder owner(User owner) { this.owner = owner; return this; }
        public FlowContextBuilder flowType(FlowType flowType) { this.flowType = flowType; return this; }
        public FlowContextBuilder collectedData(Map<String, String> collectedData) { this.collectedData = collectedData; return this; }
        public FlowContextBuilder messageId(String messageId) { this.messageId = messageId; return this; }

        public FlowContext build() {
            return new FlowContext(contact, owner, flowType, collectedData, messageId);
        }
    }
}
