package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.BusinessService;
import com.chatcrmlite.backend.repositories.BusinessServiceRepository;
import com.chatcrmlite.backend.models.MenuMedia;
import com.chatcrmlite.backend.repositories.MenuMediaRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/public/images")
public class PublicImageController {

    @Autowired
    private BusinessServiceRepository serviceRepository;

    @Autowired
    private MenuMediaRepository menuMediaRepository;

    @GetMapping("/{id}")
    public ResponseEntity<byte[]> getImage(@PathVariable UUID id) {
        Optional<BusinessService> opt = serviceRepository.findById(id);
        
        if (opt.isEmpty() || opt.get().getImageData() == null) {
            return ResponseEntity.notFound().build();
        }
        
        BusinessService saved = opt.get();

        String contentType = saved.getImageContentType() != null ? saved.getImageContentType() : "image/jpeg";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(contentType));
        
        return new ResponseEntity<>(saved.getImageData(), headers, HttpStatus.OK);
    }

    @GetMapping("/menu/{id}")
    public ResponseEntity<byte[]> getMenuImage(@PathVariable UUID id) {
        Optional<MenuMedia> opt = menuMediaRepository.findById(id);
        
        if (opt.isEmpty() || opt.get().getImageData() == null) {
            return ResponseEntity.notFound().build();
        }
        
        MenuMedia media = opt.get();
        String contentType = media.getContentType() != null ? media.getContentType() : "image/jpeg";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(contentType));
        
        return new ResponseEntity<>(media.getImageData(), headers, HttpStatus.OK);
    }
}
