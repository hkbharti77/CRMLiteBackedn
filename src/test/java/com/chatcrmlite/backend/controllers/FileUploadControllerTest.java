package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.storage.CloudinaryStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class FileUploadControllerTest {

    @Mock
    private CloudinaryStorageService cloudinaryStorageService;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private FileUploadController fileUploadController;

    private User mockUser;
    private MockMultipartFile mockFile;

    @BeforeEach
    void setUp() {
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());

        mockUser = new User();
        mockUser.setId(UUID.randomUUID());
        mockUser.setEmail("test@example.com");
        mockUser.setTenant(tenant);

        mockFile = new MockMultipartFile("file", "test.jpg", "image/jpeg", "dummy image content".getBytes());
    }

    @Test
    void testUploadFile_LegitimateFolder() throws Exception {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(mockUser));
        when(cloudinaryStorageService.isConfigured()).thenReturn(true);
        when(cloudinaryStorageService.buildTenantKey(any(UUID.class), eq("media"), anyString())).thenReturn("tenant_123/media/test.jpg");
        when(cloudinaryStorageService.uploadFile(anyString(), any())).thenReturn("http://cloudinary.com/test.jpg");

        ResponseEntity<?> response = fileUploadController.uploadFile(mockFile, "media", "test@example.com");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(cloudinaryStorageService).buildTenantKey(any(UUID.class), eq("media"), anyString());
    }

    @Test
    void testUploadFile_NestedLegitimateFolder() throws Exception {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(mockUser));
        when(cloudinaryStorageService.isConfigured()).thenReturn(true);
        when(cloudinaryStorageService.buildTenantKey(any(UUID.class), eq("assets/images"), anyString())).thenReturn("tenant_123/assets/images/test.jpg");
        when(cloudinaryStorageService.uploadFile(anyString(), any())).thenReturn("http://cloudinary.com/test.jpg");

        ResponseEntity<?> response = fileUploadController.uploadFile(mockFile, "assets/images", "test@example.com");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(cloudinaryStorageService).buildTenantKey(any(UUID.class), eq("assets/images"), anyString());
    }

    @Test
    void testUploadFile_PathTraversalRejected_DotDot() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(mockUser));
        ResponseEntity<?> response = fileUploadController.uploadFile(mockFile, "../victim", "test@example.com");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().toString().contains("Path traversal is not allowed"));
        verifyNoInteractions(cloudinaryStorageService);
    }

    @Test
    void testUploadFile_PathTraversalRejected_DoubleDotDot() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(mockUser));
        ResponseEntity<?> response = fileUploadController.uploadFile(mockFile, "../../victim", "test@example.com");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().toString().contains("Path traversal is not allowed"));
        verifyNoInteractions(cloudinaryStorageService);
    }

    @Test
    void testUploadFile_PathTraversalRejected_NestedDotDot() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(mockUser));
        ResponseEntity<?> response = fileUploadController.uploadFile(mockFile, "foo/../../victim", "test@example.com");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().toString().contains("Path traversal is not allowed"));
        verifyNoInteractions(cloudinaryStorageService);
    }

    @Test
    void testUploadFile_PathTraversalRejected_BackslashDotDot() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(mockUser));
        ResponseEntity<?> response = fileUploadController.uploadFile(mockFile, "foo\\..\\victim", "test@example.com");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().toString().contains("Path traversal is not allowed"));
        verifyNoInteractions(cloudinaryStorageService);
    }

    @Test
    void testUploadFile_PathTraversalRejected_AbsolutePath() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(mockUser));
        ResponseEntity<?> response = fileUploadController.uploadFile(mockFile, "/etc/passwd", "test@example.com");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().toString().contains("Path traversal is not allowed"));
        verifyNoInteractions(cloudinaryStorageService);
    }

    @Test
    void testUploadFile_InvalidCharactersRejected() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(mockUser));
        ResponseEntity<?> response = fileUploadController.uploadFile(mockFile, "folder name", "test@example.com");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().toString().contains("Contains invalid characters"));
        verifyNoInteractions(cloudinaryStorageService);
    }
}