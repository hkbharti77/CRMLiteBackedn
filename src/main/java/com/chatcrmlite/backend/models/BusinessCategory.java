package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "business_categories")
public class BusinessCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @OneToMany(mappedBy = "category", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<BusinessSubCategory> subCategories = new ArrayList<>();

    public BusinessCategory() {}

    public BusinessCategory(Long id, String name, List<BusinessSubCategory> subCategories) {
        this.id = id;
        this.name = name;
        this.subCategories = subCategories != null ? subCategories : new ArrayList<>();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public List<BusinessSubCategory> getSubCategories() { return subCategories; }
    public void setSubCategories(List<BusinessSubCategory> subCategories) { this.subCategories = subCategories; }

    public static BusinessCategoryBuilder builder() {
        return new BusinessCategoryBuilder();
    }

    public static class BusinessCategoryBuilder {
        private Long id;
        private String name;
        private List<BusinessSubCategory> subCategories = new ArrayList<>();

        public BusinessCategoryBuilder id(Long id) { this.id = id; return this; }
        public BusinessCategoryBuilder name(String name) { this.name = name; return this; }
        public BusinessCategoryBuilder subCategories(List<BusinessSubCategory> subCategories) { this.subCategories = subCategories; return this; }

        public BusinessCategory build() {
            return new BusinessCategory(id, name, subCategories);
        }
    }
}
