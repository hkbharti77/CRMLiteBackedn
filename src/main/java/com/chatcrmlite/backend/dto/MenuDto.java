package com.chatcrmlite.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MenuDto {
    private String type; // "list" or "button"
    private String bodyText;
    private String title;
    private String headerImageUrl;
    private String button;
    private List<MenuSectionDto> sections;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class MenuSectionDto {
        private String title;
        private List<MenuRowDto> rows;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class MenuRowDto {
        private String id;
        private String title;
        private String description;
    }
}
