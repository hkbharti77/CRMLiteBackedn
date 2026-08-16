package com.chatcrmlite.backend.security;

import com.chatcrmlite.backend.models.entitlements.EntitlementType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresEntitlement {
    EntitlementType type();
    String key();
}
