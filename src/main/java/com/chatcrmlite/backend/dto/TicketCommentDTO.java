package com.chatcrmlite.backend.dto;

public class TicketCommentDTO {

    private String id;
    private String authorName;
    private String authorRole;
    private String message;
    private String createdAt;

    public TicketCommentDTO() {}

    public TicketCommentDTO(String id, String authorName, String authorRole, String message, String createdAt) {
        this.id = id;
        this.authorName = authorName;
        this.authorRole = authorRole;
        this.message = message;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAuthorName() { return authorName; }
    public void setAuthorName(String authorName) { this.authorName = authorName; }
    public String getAuthorRole() { return authorRole; }
    public void setAuthorRole(String authorRole) { this.authorRole = authorRole; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public static TicketCommentDTOBuilder builder() { return new TicketCommentDTOBuilder(); }

    public static class TicketCommentDTOBuilder {
        private String id;
        private String authorName;
        private String authorRole;
        private String message;
        private String createdAt;

        public TicketCommentDTOBuilder id(String id) { this.id = id; return this; }
        public TicketCommentDTOBuilder authorName(String authorName) { this.authorName = authorName; return this; }
        public TicketCommentDTOBuilder authorRole(String authorRole) { this.authorRole = authorRole; return this; }
        public TicketCommentDTOBuilder message(String message) { this.message = message; return this; }
        public TicketCommentDTOBuilder createdAt(String createdAt) { this.createdAt = createdAt; return this; }

        public TicketCommentDTO build() {
            return new TicketCommentDTO(id, authorName, authorRole, message, createdAt);
        }
    }
}
