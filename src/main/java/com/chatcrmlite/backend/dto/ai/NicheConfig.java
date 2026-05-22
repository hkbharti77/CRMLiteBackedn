package com.chatcrmlite.backend.dto.ai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.io.Serializable;
import java.util.Map;
import java.util.Set;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NicheConfig implements Serializable {
    private String niche;
    private Set<String> intents;
    private Map<String, String> entities;
    private Map<String, String> hinglish;
    private Map<String, String> priorities;
}
