package com.chatcrmlite.backend.dto;

import java.util.List;

public class ContactImportRowDTO {
    private String file;
    private Integer row;
    private String name;
    private String email;
    private String waId;
    private List<String> tags;

    // Getters and Setters
    public String getFile() { return file; }
    public void setFile(String file) { this.file = file; }

    public Integer getRow() { return row; }
    public void setRow(Integer row) { this.row = row; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getWaId() { return waId; }
    public void setWaId(String waId) { this.waId = waId; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }
}
