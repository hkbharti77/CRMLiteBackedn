package com.chatcrmlite.backend.dto;

import java.util.List;

public class MenuSectionDTO {
    private String title;
    private List<MenuCardDTO> cards;

    public MenuSectionDTO() {}

    public MenuSectionDTO(String title, List<MenuCardDTO> cards) {
        this.title = title;
        this.cards = cards;
    }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public List<MenuCardDTO> getCards() { return cards; }
    public void setCards(List<MenuCardDTO> cards) { this.cards = cards; }
}
