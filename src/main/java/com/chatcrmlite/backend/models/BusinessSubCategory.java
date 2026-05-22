package com.chatcrmlite.backend.models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

@Entity
@Table(name = "business_sub_categories")
public class BusinessSubCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String triggerLabel;
    private String servicesLabel;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    @JsonIgnore
    private BusinessCategory category;

    public BusinessSubCategory() {}

    public BusinessSubCategory(Long id, String name, String triggerLabel, String servicesLabel, BusinessCategory category) {
        this.id = id;
        this.name = name;
        this.triggerLabel = triggerLabel;
        this.servicesLabel = servicesLabel;
        this.category = category;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTriggerLabel() { return triggerLabel; }
    public void setTriggerLabel(String triggerLabel) { this.triggerLabel = triggerLabel; }
    public String getServicesLabel() { return servicesLabel; }
    public void setServicesLabel(String servicesLabel) { this.servicesLabel = servicesLabel; }
    public BusinessCategory getCategory() { return category; }
    public void setCategory(BusinessCategory category) { this.category = category; }

    public static BusinessSubCategoryBuilder builder() {
        return new BusinessSubCategoryBuilder();
    }

    public static class BusinessSubCategoryBuilder {
        private Long id;
        private String name;
        private String triggerLabel;
        private String servicesLabel;
        private BusinessCategory category;

        public BusinessSubCategoryBuilder id(Long id) { this.id = id; return this; }
        public BusinessSubCategoryBuilder name(String name) { this.name = name; return this; }
        public BusinessSubCategoryBuilder triggerLabel(String triggerLabel) { this.triggerLabel = triggerLabel; return this; }
        public BusinessSubCategoryBuilder servicesLabel(String servicesLabel) { this.servicesLabel = servicesLabel; return this; }
        public BusinessSubCategoryBuilder category(BusinessCategory category) { this.category = category; return this; }

        public BusinessSubCategory build() {
            return new BusinessSubCategory(id, name, triggerLabel, servicesLabel, category);
        }
    }
}
