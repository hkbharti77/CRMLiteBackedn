package com.chatcrmlite.backend.dto;

import java.util.List;

public class ContactImportBatchRequestDTO {
    
    private List<ContactImportRowDTO> contacts;

    public List<ContactImportRowDTO> getContacts() {
        return contacts;
    }

    public void setContacts(List<ContactImportRowDTO> contacts) {
        this.contacts = contacts;
    }
}
