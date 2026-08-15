package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.BusinessCategory;
import com.chatcrmlite.backend.models.BusinessSubCategory;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.BusinessCategoryRepository;
import com.chatcrmlite.backend.repositories.BusinessSubCategoryRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping({"/api/v1/business-categories", "/api/v1/categories"})
public class BusinessCategoryController {

    @Autowired
    private BusinessCategoryRepository categoryRepository;

    @Autowired
    private BusinessSubCategoryRepository subCategoryRepository;

    @Autowired
    private UserRepository userRepository;

    @GetMapping
    public ResponseEntity<?> getAllCategories() {
        List<BusinessCategory> categories = categoryRepository.findAll();
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (BusinessCategory cat : categories) {
            result.put(
                cat.getName(),
                cat.getSubCategories().stream()
                    .map(BusinessSubCategory::getName)
                    .collect(Collectors.toList())
            );
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/details")
    public ResponseEntity<?> getAllCategoryDetails(@AuthenticationPrincipal String email) {
        requireOwnerOrAdmin(email);
        return ResponseEntity.ok(categoryRepository.findAll());
    }

    @PostMapping
    public ResponseEntity<?> createCategory(
            @AuthenticationPrincipal String email,
            @RequestBody CategoryRequest request) {
        requireOwnerOrAdmin(email);

        if (categoryRepository.existsByName(request.getName())) {
            return ResponseEntity.badRequest().body("Category '" + request.getName() + "' already exists.");
        }

        BusinessCategory category = BusinessCategory.builder()
                .name(request.getName())
                .build();
        return ResponseEntity.ok(categoryRepository.save(category));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateCategory(
            @AuthenticationPrincipal String email,
            @PathVariable Long id,
            @RequestBody CategoryRequest request) {
        requireOwnerOrAdmin(email);

        BusinessCategory category = categoryRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Category not found"));
        category.setName(request.getName());
        return ResponseEntity.ok(categoryRepository.save(category));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteCategory(
            @AuthenticationPrincipal String email,
            @PathVariable Long id) {
        requireOwnerOrAdmin(email);

        if (!categoryRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        categoryRepository.deleteById(id);
        return ResponseEntity.ok("Category deleted successfully.");
    }

    @PostMapping("/{categoryId}/subcategories")
    public ResponseEntity<?> addSubCategory(
            @AuthenticationPrincipal String email,
            @PathVariable Long categoryId,
            @RequestBody SubCategoryRequest request) {
        requireOwnerOrAdmin(email);

        BusinessCategory category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new RuntimeException("Category not found"));

        if (subCategoryRepository.existsByNameAndCategoryId(request.getName(), categoryId)) {
            return ResponseEntity.badRequest().body("Sub-category '" + request.getName() + "' already exists under this category.");
        }

        BusinessSubCategory sub = BusinessSubCategory.builder()
                .name(request.getName())
                .category(category)
                .build();
        return ResponseEntity.ok(subCategoryRepository.save(sub));
    }

    @PutMapping("/subcategories/{subId}")
    public ResponseEntity<?> updateSubCategory(
            @AuthenticationPrincipal String email,
            @PathVariable Long subId,
            @RequestBody SubCategoryRequest request) {
        requireOwnerOrAdmin(email);

        BusinessSubCategory sub = subCategoryRepository.findById(subId)
                .orElseThrow(() -> new RuntimeException("Sub-category not found"));
        sub.setName(request.getName());
        return ResponseEntity.ok(subCategoryRepository.save(sub));
    }

    @DeleteMapping("/subcategories/{subId}")
    public ResponseEntity<?> deleteSubCategory(
            @AuthenticationPrincipal String email,
            @PathVariable Long subId) {
        requireOwnerOrAdmin(email);

        if (!subCategoryRepository.existsById(subId)) {
            return ResponseEntity.notFound().build();
        }
        subCategoryRepository.deleteById(subId);
        return ResponseEntity.ok("Sub-category deleted successfully.");
    }

    private void requireOwnerOrAdmin(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.OWNER && user.getRole() != User.Role.ADMIN) {
            throw new RuntimeException("Access denied: Only owners and admins can manage categories.");
        }
    }

    public static class CategoryRequest {
        private String name;
        public CategoryRequest() {}
        public CategoryRequest(String name) { this.name = name; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    public static class SubCategoryRequest {
        private String name;
        public SubCategoryRequest() {}
        public SubCategoryRequest(String name) { this.name = name; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }
}
