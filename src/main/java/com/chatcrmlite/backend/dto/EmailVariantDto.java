package com.chatcrmlite.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailVariantDto {
    private UUID id;
    private String variantCode;
    private String name;
    private String subject;
    private String body;
    private String builderJson;
    private int sampleCount;
    private int sentCount;
    private int deliveredCount;
    private int openedCount;
    private int clickedCount;
    private double openRate;
    private double ctr;
    private boolean isWinner;
    private boolean isLeader;
}
