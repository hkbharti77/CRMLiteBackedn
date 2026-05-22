package com.chatcrmlite.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

/**
 * FIX #10: Added Jakarta Bean Validation annotations to ensure menu structure validity
 * before sending to WhatsApp API.
 */
public class MenuDto {
    // FIX #10: Menu type must be 'button' or 'list'
    @NotNull(message = "Menu type cannot be null")
    @Pattern(regexp = "button|list", message = "Menu type must be 'button' or 'list'")
    private String type;
    
    // FIX #10: Body text is required
    @NotBlank(message = "Menu body text cannot be blank")
    private String bodyText;
    
    private String title;
    private String headerImageUrl;
    private String button;
    
    // FIX #10: Sections must not be empty
    @NotEmpty(message = "Menu must have at least one section")
    private List<MenuSectionDto> sections = new ArrayList<>();

    public MenuDto() {}

    public MenuDto(String type, String bodyText, String title, String headerImageUrl, String button, List<MenuSectionDto> sections) {
        this.type = type;
        this.bodyText = bodyText;
        this.title = title;
        this.headerImageUrl = headerImageUrl;
        this.button = button;
        this.sections = (sections != null) ? sections : new ArrayList<>();
    }

    public String getType() { return type; }
    public String getBodyText() { return bodyText; }
    public String getTitle() { return title; }
    public String getHeaderImageUrl() { return headerImageUrl; }
    public String getButton() { return button; }
    
    // FIX #20: Return unmodifiable list to prevent external modification
    public List<MenuSectionDto> getSections() { 
        return sections != null ? Collections.unmodifiableList(sections) : Collections.emptyList();
    }

    public void setType(String type) { this.type = type; }
    public void setBodyText(String bodyText) { this.bodyText = bodyText; }
    public void setTitle(String title) { this.title = title; }
    public void setHeaderImageUrl(String headerImageUrl) { this.headerImageUrl = headerImageUrl; }
    public void setButton(String button) { this.button = button; }
    public void setSections(List<MenuSectionDto> sections) { this.sections = sections; }

    public static MenuDtoBuilder builder() { return new MenuDtoBuilder(); }

    public static class MenuDtoBuilder {
        private String type;
        private String bodyText;
        private String title;
        private String headerImageUrl;
        private String button;
        private List<MenuSectionDto> sections;

        public MenuDtoBuilder type(String type) { this.type = type; return this; }
        public MenuDtoBuilder bodyText(String bodyText) { this.bodyText = bodyText; return this; }
        public MenuDtoBuilder title(String title) { this.title = title; return this; }
        public MenuDtoBuilder headerImageUrl(String headerImageUrl) { this.headerImageUrl = headerImageUrl; return this; }
        public MenuDtoBuilder button(String button) { this.button = button; return this; }
        public MenuDtoBuilder sections(List<MenuSectionDto> sections) { this.sections = sections; return this; }

        public MenuDto build() {
            return new MenuDto(type, bodyText, title, headerImageUrl, button, sections);
        }
    }

    public static class MenuSectionDto {
        private String title;
        
        // FIX #10: Rows must not be empty
        @NotEmpty(message = "Menu section must have at least one row")
        private List<MenuRowDto> rows = new ArrayList<>();

        public MenuSectionDto() {}

        public MenuSectionDto(String title, List<MenuRowDto> rows) {
            this.title = title;
            this.rows = (rows != null) ? rows : new ArrayList<>();
        }

        public String getTitle() { return title; }
        
        // FIX #20: Return unmodifiable list
        public List<MenuRowDto> getRows() { 
            return rows != null ? Collections.unmodifiableList(rows) : Collections.emptyList();
        }

        public void setTitle(String title) { this.title = title; }
        public void setRows(List<MenuRowDto> rows) { this.rows = rows; }

        public static MenuSectionDtoBuilder builder() { return new MenuSectionDtoBuilder(); }

        public static class MenuSectionDtoBuilder {
            private String title;
            private List<MenuRowDto> rows;

            public MenuSectionDtoBuilder title(String title) { this.title = title; return this; }
            public MenuSectionDtoBuilder rows(List<MenuRowDto> rows) { this.rows = rows; return this; }

            public MenuSectionDto build() {
                return new MenuSectionDto(title, rows);
            }
        }
    }

    public static class MenuRowDto {
        // FIX #10: Row ID is required
        @NotBlank(message = "Menu row ID cannot be blank")
        private String id;
        
        // FIX #10: Row title is required and limited to 24 chars (WhatsApp limit)
        @NotBlank(message = "Menu row title cannot be blank")
        @Size(max = 24, message = "Menu row title cannot exceed 24 characters")
        private String title;
        
        private String description;

        public MenuRowDto() {}

        public MenuRowDto(String id, String title, String description) {
            this.id = id;
            this.title = title;
            this.description = description;
        }

        public String getId() { return id; }
        public String getTitle() { return title; }
        public String getDescription() { return description; }

        public void setId(String id) { this.id = id; }
        public void setTitle(String title) { this.title = title; }
        public void setDescription(String description) { this.description = description; }

        public static MenuRowDtoBuilder builder() { return new MenuRowDtoBuilder(); }

        public static class MenuRowDtoBuilder {
            private String id;
            private String title;
            private String description;

            public MenuRowDtoBuilder id(String id) { this.id = id; return this; }
            public MenuRowDtoBuilder title(String title) { this.title = title; return this; }
            public MenuRowDtoBuilder description(String description) { this.description = description; return this; }

            public MenuRowDto build() {
                return new MenuRowDto(id, title, description);
            }
        }
    }
}
