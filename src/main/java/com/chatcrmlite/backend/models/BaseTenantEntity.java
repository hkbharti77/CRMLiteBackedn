package com.chatcrmlite.backend.models;

import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

import java.io.Serializable;
import java.util.UUID;

/**
 * Base class for all tenant-aware entities.
 * Defines the Hibernate filter used for automatic isolation.
 */
@MappedSuperclass
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = UUID.class))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public abstract class BaseTenantEntity implements Serializable {
    private static final long serialVersionUID = 1L;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Tenant tenant;

    @com.fasterxml.jackson.annotation.JsonIgnore
    public Tenant getTenant() { return tenant; }
    public void setTenant(Tenant tenant) { this.tenant = tenant; }

    @PrePersist
    @PreUpdate
    protected void populateTenant() {
        if (this.tenant == null) {
            // 1. Try to get tenant from TenantContext
            UUID contextTenantId = com.chatcrmlite.backend.security.TenantContext.getTenantId();
            if (contextTenantId != null) {
                Tenant t = new Tenant();
                t.setId(contextTenantId);
                this.tenant = t;
                return;
            }

            // 2. Try to get tenant from owner (e.g., in Lead, Contact, Ticket, Appointment, BusinessService)
            try {
                java.lang.reflect.Field ownerField = getField(this.getClass(), "owner");
                if (ownerField != null) {
                    ownerField.setAccessible(true);
                    Object ownerObj = ownerField.get(this);
                    if (ownerObj instanceof User) {
                        User user = (User) ownerObj;
                        if (user.getTenant() != null) {
                            this.tenant = user.getTenant();
                            return;
                        }
                    }
                }
            } catch (Exception ignored) {}

            // 3. Try to get tenant from contact (e.g., in Message, Lead)
            try {
                java.lang.reflect.Field contactField = getField(this.getClass(), "contact");
                if (contactField != null) {
                    contactField.setAccessible(true);
                    Object contactObj = contactField.get(this);
                    if (contactObj instanceof Contact) {
                        Contact c = (Contact) contactObj;
                        if (c.getTenant() != null) {
                            this.tenant = c.getTenant();
                            return;
                        } else if (c.getOwner() != null && c.getOwner().getTenant() != null) {
                            this.tenant = c.getOwner().getTenant();
                            return;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private java.lang.reflect.Field getField(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }
}
