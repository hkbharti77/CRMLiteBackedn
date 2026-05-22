package com.chatcrmlite.backend.models;

import java.util.ArrayList;
import java.util.List;

public enum Permission {
    VIEW_LEADS,
    MANAGE_LEADS(VIEW_LEADS),
    VIEW_TICKETS,
    MANAGE_TICKETS(VIEW_TICKETS),
    EXPORT_DATA,
    MANAGE_BILLING;

    private final Permission[] implied;

    Permission(Permission... implied) {
        this.implied = implied;
    }

    public List<Permission> getImpliedPermissions() {
        List<Permission> list = new ArrayList<>();
        list.add(this);
        if (implied != null) {
            for (Permission p : implied) {
                list.addAll(p.getImpliedPermissions());
            }
        }
        return list;
    }
}
