package com.chatcrmlite.backend.security;

import com.chatcrmlite.backend.services.PermissionService;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

@Aspect
@Component
@RequiredArgsConstructor
public class PermissionAspect {
    private final PermissionService permissionService;

    @Before("@annotation(requiresPermission)")
    public void checkPermission(RequiresPermission requiresPermission) {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            throw new AccessDeniedException("User is not authenticated");
        }

        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        String email;
        if (principal instanceof UserDetails) {
            email = ((UserDetails) principal).getUsername();
        } else {
            email = principal.toString();
        }

        if (!permissionService.hasPermission(email, requiresPermission.value())) {
            throw new AccessDeniedException("User is not authorized with required permission: " + requiresPermission.value());
        }
    }
}
