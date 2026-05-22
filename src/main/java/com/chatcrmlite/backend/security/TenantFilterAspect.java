package com.chatcrmlite.backend.security;

import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Session;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Aspect to automatically enable the Hibernate tenant filter.
 * Intercepts repository methods and sets the tenantId parameter.
 */
@Aspect
@Component
@Slf4j
public class TenantFilterAspect {

    @Autowired
    private EntityManager entityManager;

    @Pointcut("execution(* com.chatcrmlite.backend.repositories..*(..))")
    public void repositoryMethods() {}

    @Before("repositoryMethods()")
    public void enableTenantFilter() {
        if (TenantContext.isAdminMode()) {
            log.debug("Tenant filter bypassed (Admin Mode)");
            return;
        }

        UUID tenantId = TenantContext.getTenantId();
        if (tenantId != null) {
            Session session = entityManager.unwrap(Session.class);
            session.enableFilter("tenantFilter").setParameter("tenantId", tenantId);
            log.trace("Tenant filter enabled for ID: {}", tenantId);
        } else {
            log.debug("No tenant ID found in context for repository operation!");

        }
    }
}
