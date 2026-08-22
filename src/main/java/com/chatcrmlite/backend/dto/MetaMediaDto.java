package com.chatcrmlite.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MetaMediaDto {

    private String id;
    private String url;

    @JsonProperty("mime_type")
    private String mimeType;

    private String sha256;

    @JsonProperty("file_size")
    private Long fileSize;

    @JsonProperty("messaging_product")
    private String messagingProduct;
}
