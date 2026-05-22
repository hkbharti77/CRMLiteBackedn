package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.io.Serializable;
import java.util.UUID;

@Entity
@Table(name = "role_permission_configs", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"tenant_id", "role"})
})
public class RolePermissionConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private User.Role role;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "permissions", columnDefinition = "jsonb", nullable = false)
    private String permissions = "[]";

    public RolePermissionConfig() {}

    public RolePermissionConfig(UUID id, Tenant tenant, User.Role role, String permissions) {
        this.id = id;
        this.tenant = tenant;
        this.role = role;
        this.permissions = permissions != null ? permissions : "[]";
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public Tenant getTenant() { return tenant; }
    public void setTenant(Tenant tenant) { this.tenant = tenant; }

    public User.Role getRole() { return role; }
    public void setRole(User.Role role) { this.role = role; }

    public String getPermissions() { return permissions; }
    public void setPermissions(String permissions) { this.permissions = permissions; }

    public static RolePermissionConfigBuilder builder() { return new RolePermissionConfigBuilder(); }

    public static class RolePermissionConfigBuilder {
        private UUID id;
        private Tenant tenant;
        private User.Role role;
        private String permissions = "[]";

        public RolePermissionConfigBuilder id(UUID id) { this.id = id; return this; }
        public RolePermissionConfigBuilder tenant(Tenant tenant) { this.tenant = tenant; return this; }
        public RolePermissionConfigBuilder role(User.Role role) { this.role = role; return this; }
        public RolePermissionConfigBuilder permissions(String permissions) { this.permissions = permissions; return this; }

        public RolePermissionConfig build() {
            return new RolePermissionConfig(id, tenant, role, permissions);
        }
    }
}
