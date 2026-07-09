package com.chatcrmlite.backend.dto.ai;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

public enum Decision {
    CALL_AI, CLARIFY, MENU, IGNORE, REUSE, WARNING, GREETING,
    @JsonEnumDefaultValue UNKNOWN
}
