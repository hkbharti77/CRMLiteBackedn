package com.chatcrmlite.backend.dto;

import java.util.List;
import java.util.UUID;

import java.io.Serializable;

public class ContactDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private UUID id;
    private String waId;
    private String name;
    private List<String> tags;
    private String source;

    public ContactDTO() {}

    public ContactDTO(UUID id, String waId, String name, List<String> tags, String source) {
        this.id = id;
        this.waId = waId;
        this.name = name;
        this.tags = tags;
        this.source = source;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getWaId() { return waId; }
    public void setWaId(String waId) { this.waId = waId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public static ContactDTOBuilder builder() {
        return new ContactDTOBuilder();
    }

    public static class ContactDTOBuilder {
        private UUID id;
        private String waId;
        private String name;
        private List<String> tags;
        private String source;

        public ContactDTOBuilder id(UUID id) { this.id = id; return this; }
        public ContactDTOBuilder waId(String waId) { this.waId = waId; return this; }
        public ContactDTOBuilder name(String name) { this.name = name; return this; }
        public ContactDTOBuilder tags(List<String> tags) { this.tags = tags; return this; }
        public ContactDTOBuilder source(String source) { this.source = source; return this; }

        public ContactDTO build() {
            return new ContactDTO(id, waId, name, tags, source);
        }
    }
}
