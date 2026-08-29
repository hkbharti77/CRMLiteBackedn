package com.chatcrmlite.backend.security;

import com.chatcrmlite.backend.controllers.TestEmailController;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class TestEmailDispatcherSecurityTest {

    @Test
    public void testEmailDispatcher_isInaccessibleInProduction() {
        Profile profileAnnotation = TestEmailController.class.getAnnotation(Profile.class);
        assertNotNull(profileAnnotation, "TestEmailController must have a @Profile annotation");
        assertArrayEquals(new String[]{"dev", "test"}, profileAnnotation.value(), 
                "TestEmailController must be restricted to 'dev' and 'test' profiles");
    }
}
