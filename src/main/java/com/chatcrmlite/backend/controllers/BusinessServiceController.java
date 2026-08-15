package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.BusinessService;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.BusinessServiceRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/business-services")
public class BusinessServiceController {

    @Autowired
    private BusinessServiceRepository serviceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private com.chatcrmlite.backend.services.storage.CloudinaryStorageService cloudinaryStorageService;

    @GetMapping
    public ResponseEntity<?> getAllServices(@AuthenticationPrincipal String email) {
        if (email == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) return ResponseEntity.notFound().build();

        List<BusinessService> services = serviceRepository.findByOwner(user);
        for (BusinessService s : services) {
            if ((s.getImageUrl() == null || s.getImageUrl().isBlank()) && s.getImageData() != null) {
                String baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
                s.setImageUrl(baseUrl + "/public/images/" + s.getId());
            }
            s.setImageData(null);
        }
        return ResponseEntity.ok(services);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> createService(
            @RequestParam("name") String name,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @AuthenticationPrincipal String email) {

        if (email == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) return ResponseEntity.notFound().build();

        System.out.println("[Service] Creating service: " + name);

        BusinessService businessService = BusinessService.builder()
                .name(name)
                .description(description != null ? description : "")
                .owner(user)
                .build();

        if (file != null && !file.isEmpty()) {
            if (file.getSize() > 50L * 1024 * 1024) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("File size must not exceed 50 MB");
            }
            try {
                if (cloudinaryStorageService.isConfigured()) {
                    String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "product_file";
                    String sanitized = originalFilename.replaceAll("[^a-zA-Z0-9._-]", "_");
                    java.util.UUID tenantId = (user.getTenant() != null ? user.getTenant().getId() : user.getId());
                    String key = cloudinaryStorageService.buildTenantKey(tenantId, "catalog", sanitized);
                    String mediaUrl = cloudinaryStorageService.uploadFile(key, file);
                    businessService.setImageUrl(mediaUrl);
                } else {
                    businessService.setImageData(file.getBytes());
                    businessService.setImageContentType(file.getContentType());
                }
            } catch (Exception e) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("Failed to upload file: " + e.getMessage());
            }
        }

        BusinessService saved = serviceRepository.save(businessService);
        
        if (saved.getImageUrl() == null && saved.getImageData() != null) {
            String baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
            saved.setImageUrl(baseUrl + "/public/images/" + saved.getId());
            serviceRepository.save(saved);
        }
        
        saved.setImageData(null); 
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PutMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> updateService(
            @PathVariable UUID id,
            @RequestParam("name") String name,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @AuthenticationPrincipal String email) {

        if (email == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) return ResponseEntity.notFound().build();

        Optional<BusinessService> opt = serviceRepository.findByIdAndOwner(id, user);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();

        BusinessService existing = opt.get();
        existing.setName(name);
        existing.setDescription(description != null ? description : "");

        if (file != null && !file.isEmpty()) {
            if (file.getSize() > 50L * 1024 * 1024) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("File size must not exceed 50 MB");
            }
            try {
                if (cloudinaryStorageService.isConfigured()) {
                    String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "product_file";
                    String sanitized = originalFilename.replaceAll("[^a-zA-Z0-9._-]", "_");
                    java.util.UUID tenantId = (user.getTenant() != null ? user.getTenant().getId() : user.getId());
                    String key = cloudinaryStorageService.buildTenantKey(tenantId, "catalog", sanitized);
                    String mediaUrl = cloudinaryStorageService.uploadFile(key, file);
                    existing.setImageUrl(mediaUrl);
                    existing.setImageData(null);
                } else {
                    existing.setImageData(file.getBytes());
                    existing.setImageContentType(file.getContentType());
                    String baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
                    existing.setImageUrl(baseUrl + "/public/images/" + existing.getId());
                }
            } catch (Exception e) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("Failed to upload file: " + e.getMessage());
            }
        }

        BusinessService saved = serviceRepository.save(existing);
        saved.setImageData(null);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteService(@PathVariable UUID id, @AuthenticationPrincipal String email) {
        if (email == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) return ResponseEntity.notFound().build();

        Optional<BusinessService> opt = serviceRepository.findByIdAndOwner(id, user);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();

        serviceRepository.delete(opt.get());
        return ResponseEntity.noContent().build();
    }
}
