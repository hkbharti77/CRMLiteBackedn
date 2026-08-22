package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.clients.MetaWhatsAppClient;
import com.chatcrmlite.backend.clients.WhatsAppClient;
import com.chatcrmlite.backend.config.WhatsAppMediaProperties;
import com.chatcrmlite.backend.dto.MetaMediaDto;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Message;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.ConversationStateRepository;
import com.chatcrmlite.backend.repositories.MessageRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.services.IdempotencyService;
import com.chatcrmlite.backend.services.storage.CloudinaryStorageService;
import com.chatcrmlite.backend.services.websocket.DistributedWebSocketPublisher;
import com.chatcrmlite.backend.services.workflow.ProcessingContext;
import com.chatcrmlite.backend.utils.BoundedCountingInputStream;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class WhatsAppMediaPipelineTest {

    @Mock
    private ContactRepository contactRepository;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private WhatsAppConfigRepository whatsappConfigRepository;

    @Mock
    private ConversationStateRepository conversationStateRepository;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private DistributedWebSocketPublisher distributedWebSocketPublisher;

    @Mock
    private MetaWhatsAppClient metaWhatsAppClient;

    @Mock
    private CloudinaryStorageService cloudinaryStorageService;

    @Mock
    private RestTemplate restTemplate;

    private WhatsAppMediaProperties mediaProperties;
    private WhatsAppMediaSizeValidator sizeValidator;
    private WhatsAppIngressService ingressService;
    private MetaWhatsAppClient clientUnderTest;
    private ObjectMapper objectMapper = new ObjectMapper();

    private final UUID tenantId = UUID.randomUUID();
    private final String waId = "919876543210";
    private final String phoneNumberId = "phone_123";
    private final String accessToken = "EAAB_test_access_token";

    @BeforeEach
    void setUp() {
        mediaProperties = new WhatsAppMediaProperties();
        sizeValidator = new WhatsAppMediaSizeValidator(mediaProperties);

        ingressService = new WhatsAppIngressService(
                contactRepository,
                messageRepository,
                whatsappConfigRepository,
                conversationStateRepository,
                idempotencyService,
                distributedWebSocketPublisher,
                objectMapper
        );

        ReflectionTestUtils.setField(ingressService, "whatsappClient", metaWhatsAppClient);
        ReflectionTestUtils.setField(ingressService, "cloudinaryStorageService", cloudinaryStorageService);
        ReflectionTestUtils.setField(ingressService, "mediaSizeValidator", sizeValidator);

        clientUnderTest = new MetaWhatsAppClient();
        ReflectionTestUtils.setField(clientUnderTest, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(clientUnderTest, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(clientUnderTest, "retryRegistry", RetryRegistry.ofDefaults());
    }

    private WhatsAppConfig createConfig() {
        WhatsAppConfig cfg = new WhatsAppConfig();
        cfg.setPhoneNumberId(phoneNumberId);
        cfg.setAccessToken(accessToken);
        Tenant tenant = new Tenant();
        tenant.setId(tenantId);
        tenant.setBusinessName("Test Corp");
        cfg.setTenant(tenant);
        return cfg;
    }

    private Contact createContact() {
        Contact contact = new Contact();
        contact.setId(UUID.randomUUID());
        contact.setWaId(waId);
        contact.setName("Test User");
        return contact;
    }

    // =========================================================================
    // 1. CONFIGURATION TESTS
    // =========================================================================
    @Nested
    @DisplayName("Configuration & Properties Tests")
    class ConfigurationTests {

        @Test
        @DisplayName("1. Default image limit is 16 MB (16,777,216 bytes)")
        void testDefaultImageLimit() {
            assertEquals(16L * 1024 * 1024, mediaProperties.getMaxImageSize());
            assertEquals(16L * 1024 * 1024, mediaProperties.getMaxLimitBytes("IMAGE"));
        }

        @Test
        @DisplayName("2. Default video limit is 64 MB (67,108,864 bytes)")
        void testDefaultVideoLimit() {
            assertEquals(64L * 1024 * 1024, mediaProperties.getMaxVideoSize());
            assertEquals(64L * 1024 * 1024, mediaProperties.getMaxLimitBytes("VIDEO"));
        }

        @Test
        @DisplayName("3. Default audio limit is 32 MB (33,554,432 bytes)")
        void testDefaultAudioLimit() {
            assertEquals(32L * 1024 * 1024, mediaProperties.getMaxAudioSize());
            assertEquals(32L * 1024 * 1024, mediaProperties.getMaxLimitBytes("AUDIO"));
            assertEquals(32L * 1024 * 1024, mediaProperties.getMaxLimitBytes("VOICE"));
        }

        @Test
        @DisplayName("4. Default document limit is 100 MB (104,857,600 bytes)")
        void testDefaultDocumentLimit() {
            assertEquals(100L * 1024 * 1024, mediaProperties.getMaxDocumentSize());
            assertEquals(100L * 1024 * 1024, mediaProperties.getMaxLimitBytes("DOCUMENT"));
        }

        @Test
        @DisplayName("5. Default sticker limit is 5 MB (5,242,880 bytes)")
        void testDefaultStickerLimit() {
            assertEquals(5L * 1024 * 1024, mediaProperties.getMaxStickerSize());
            assertEquals(5L * 1024 * 1024, mediaProperties.getMaxLimitBytes("STICKER"));
        }

        @Test
        @DisplayName("6. Environment / property overrides dynamically update configured limits")
        void testPropertyOverrides() {
            WhatsAppMediaProperties customProps = new WhatsAppMediaProperties();
            customProps.setMaxImageSize(8L * 1024 * 1024);
            customProps.setMaxVideoSize(30L * 1024 * 1024);
            customProps.setMaxAudioSize(10L * 1024 * 1024);
            customProps.setMaxDocumentSize(50L * 1024 * 1024);
            customProps.setMaxStickerSize(2L * 1024 * 1024);

            assertEquals(8L * 1024 * 1024, customProps.getMaxLimitBytes("IMAGE"));
            assertEquals(30L * 1024 * 1024, customProps.getMaxLimitBytes("VIDEO"));
            assertEquals(10L * 1024 * 1024, customProps.getMaxLimitBytes("AUDIO"));
            assertEquals(50L * 1024 * 1024, customProps.getMaxLimitBytes("DOCUMENT"));
            assertEquals(2L * 1024 * 1024, customProps.getMaxLimitBytes("STICKER"));
        }
    }

    // =========================================================================
    // 2. CENTRALIZED VALIDATION TESTS
    // =========================================================================
    @Nested
    @DisplayName("Media Size Validation Tests")
    class ValidationTests {

        @Test
        @DisplayName("7. Image below limit is accepted")
        void testImageBelowLimitAccepted() {
            assertTrue(sizeValidator.validateReportedSize("IMAGE", 5L * 1024 * 1024));
        }

        @Test
        @DisplayName("8. Image above limit is rejected")
        void testImageAboveLimitRejected() {
            assertFalse(sizeValidator.validateReportedSize("IMAGE", 20L * 1024 * 1024));
        }

        @Test
        @DisplayName("9. Video above limit is rejected")
        void testVideoAboveLimitRejected() {
            assertFalse(sizeValidator.validateReportedSize("VIDEO", 70L * 1024 * 1024));
        }

        @Test
        @DisplayName("10. Document above limit is rejected")
        void testDocumentAboveLimitRejected() {
            assertFalse(sizeValidator.validateReportedSize("DOCUMENT", 150L * 1024 * 1024));
        }

        @Test
        @DisplayName("11. Unknown media type handled safely with fallback limit")
        void testUnknownMediaTypeHandledSafely() {
            assertTrue(sizeValidator.validateReportedSize("CUSTOM_UNKNOWN", 10L * 1024 * 1024));
            assertFalse(sizeValidator.validateReportedSize("CUSTOM_UNKNOWN", 200L * 1024 * 1024));
            assertFalse(sizeValidator.isSupportedMediaType("UNSUPPORTED_TYPE_XYZ"));
        }

        @Test
        @DisplayName("12. Missing (null) media size handled safely (allowed to proceed to bounded stream)")
        void testMissingMediaSizeHandledSafely() {
            assertTrue(sizeValidator.validateReportedSize("IMAGE", null));
            assertFalse(sizeValidator.validateReportedSize("IMAGE", -100L));
        }
    }

    // =========================================================================
    // 3. STREAMING & MEMORY SAFETY TESTS
    // =========================================================================
    @Nested
    @DisplayName("Streaming & Memory Safety Tests")
    class MemorySafetyTests {

        @Test
        @DisplayName("13. InputStream is properly closed after successful processing")
        void testInputStreamClosedAfterSuccess() throws IOException {
            AtomicBoolean streamClosed = new AtomicBoolean(false);
            byte[] dummyData = new byte[]{1, 2, 3, 4, 5};
            InputStream rawIn = new ByteArrayInputStream(dummyData) {
                @Override
                public void close() throws IOException {
                    super.close();
                    streamClosed.set(true);
                }
            };

            BoundedCountingInputStream boundedIn = new BoundedCountingInputStream(rawIn, 100);
            boundedIn.readAllBytes();
            boundedIn.close();

            assertTrue(streamClosed.get());
        }

        @Test
        @DisplayName("14. InputStream is properly closed even when an exception occurs")
        void testInputStreamClosedAfterFailure() {
            AtomicBoolean streamClosed = new AtomicBoolean(false);
            byte[] dummyData = new byte[]{1, 2, 3, 4, 5};
            InputStream rawIn = new ByteArrayInputStream(dummyData) {
                @Override
                public void close() throws IOException {
                    super.close();
                    streamClosed.set(true);
                }
            };

            try (BoundedCountingInputStream boundedIn = new BoundedCountingInputStream(rawIn, 2)) {
                boundedIn.readAllBytes(); // Exceeds limit of 2 bytes
            } catch (IOException ignored) {
            }

            assertTrue(streamClosed.get());
        }

        @Test
        @DisplayName("15. Oversized stream exceeding bounded limit is aborted immediately")
        void testOversizedStreamAborted() {
            byte[] largeData = new byte[100];
            BoundedCountingInputStream boundedIn = new BoundedCountingInputStream(new ByteArrayInputStream(largeData), 50);

            IOException ex = assertThrows(IOException.class, boundedIn::readAllBytes);
            assertTrue(ex.getMessage().contains("exceeded configured maximum size limit"));
        }

        @Test
        @DisplayName("16. MetaWhatsAppClient - Successfully fetches media metadata from Meta Graph API")
        void testMetaClientFetchMediaMetadata() {
            String mediaId = "media_id_999";
            MetaMediaDto mockDto = MetaMediaDto.builder()
                    .id(mediaId)
                    .url("https://lookaside.fbsbx.com/whatsapp_business/attachments/temp123")
                    .mimeType("image/jpeg")
                    .fileSize(102400L)
                    .sha256("dummy_sha256")
                    .build();

            when(restTemplate.exchange(
                    eq("https://graph.facebook.com/v18.0/" + mediaId),
                    eq(HttpMethod.GET),
                    any(HttpEntity.class),
                    eq(MetaMediaDto.class)
            )).thenReturn(ResponseEntity.ok(mockDto));

            MetaMediaDto result = clientUnderTest.fetchMediaMetadata(mediaId, accessToken);
            assertNotNull(result);
            assertEquals(mediaId, result.getId());
            assertEquals("image/jpeg", result.getMimeType());
            assertEquals(102400L, result.getFileSize());
            assertEquals("https://lookaside.fbsbx.com/whatsapp_business/attachments/temp123", result.getUrl());
        }
    }

    // =========================================================================
    // 4. END-TO-END PIPELINE & REGRESSION TESTS
    // =========================================================================
    @Nested
    @DisplayName("End-to-End Pipeline & Regression Tests")
    class PipelineRegressionTests {

        @Test
        @DisplayName("17. Text message continues to work seamlessly")
        void testTextMessageProcessing() {
            String messageId = "wamid.txt.001";
            String jsonPayload = "{" +
                    "  \"entry\": [{" +
                    "    \"changes\": [{" +
                    "      \"value\": {" +
                    "        \"messaging_product\": \"whatsapp\"," +
                    "        \"contacts\": [{\"profile\": {\"name\": \"Alice\"}, \"wa_id\": \"" + waId + "\"}]," +
                    "        \"messages\": [{" +
                    "          \"from\": \"" + waId + "\"," +
                    "          \"id\": \"" + messageId + "\"," +
                    "          \"timestamp\": \"1700000000\"," +
                    "          \"type\": \"text\"," +
                    "          \"text\": {\"body\": \"Hello Support!\"}" +
                    "        }]" +
                    "      }" +
                    "    }]" +
                    "  }]" +
                    "}";

            ProcessingContext context = ProcessingContext.builder()
                    .messageId(messageId)
                    .tenantId(tenantId)
                    .waId(waId)
                    .payload(jsonPayload)
                    .timestamp(1700000000000L)
                    .build();

            WhatsAppConfig config = createConfig();
            Contact contact = createContact();

            when(idempotencyService.markAsProcessing(messageId, tenantId)).thenReturn(true);
            when(whatsappConfigRepository.findByTenantId(tenantId)).thenReturn(Optional.of(config));
            when(contactRepository.findByWaIdAndTenant_Id(eq(waId), any())).thenReturn(Optional.of(contact));

            ingressService.resolveAndSaveIngress(context);

            ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
            verify(messageRepository).save(captor.capture());
            Message saved = captor.getValue();

            assertEquals("Hello Support!", saved.getContent());
            assertNull(saved.getMediaUrl());
            verify(distributedWebSocketPublisher).publishMessage(eq(tenantId), anyMap());
        }

        @Test
        @DisplayName("18. Incoming Image message streamed directly to Cloudinary with metadata persistence")
        @SuppressWarnings("unchecked")
        void testIncomingImageProcessingSuccess() throws Exception {
            String messageId = "wamid.img.001";
            String mediaId = "media_meta_101";
            String jsonPayload = "{" +
                    "  \"entry\": [{" +
                    "    \"changes\": [{" +
                    "      \"value\": {" +
                    "        \"messaging_product\": \"whatsapp\"," +
                    "        \"contacts\": [{\"profile\": {\"name\": \"John Doe\"}, \"wa_id\": \"" + waId + "\"}]," +
                    "        \"messages\": [{" +
                    "          \"from\": \"" + waId + "\"," +
                    "          \"id\": \"" + messageId + "\"," +
                    "          \"timestamp\": \"1700000000\"," +
                    "          \"type\": \"image\"," +
                    "          \"image\": {" +
                    "            \"id\": \"" + mediaId + "\"," +
                    "            \"mime_type\": \"image/jpeg\"," +
                    "            \"sha256\": \"abc123\"," +
                    "            \"caption\": \"Check out this receipt\"" +
                    "          }" +
                    "        }]" +
                    "      }" +
                    "    }]" +
                    "  }]" +
                    "}";

            ProcessingContext context = ProcessingContext.builder()
                    .messageId(messageId)
                    .tenantId(tenantId)
                    .waId(waId)
                    .payload(jsonPayload)
                    .timestamp(1700000000000L)
                    .build();

            WhatsAppConfig config = createConfig();
            Contact contact = createContact();

            when(idempotencyService.markAsProcessing(messageId, tenantId)).thenReturn(true);
            when(whatsappConfigRepository.findByTenantId(tenantId)).thenReturn(Optional.of(config));
            when(contactRepository.findByWaIdAndTenant_Id(eq(waId), any())).thenReturn(Optional.of(contact));
            when(messageRepository.findFirstByMediaIdOrderByTimestampDesc(mediaId)).thenReturn(Optional.empty());

            MetaMediaDto metaMedia = MetaMediaDto.builder()
                    .id(mediaId)
                    .url("https://lookaside.fbsbx.com/whatsapp_business/attachments/temp101")
                    .mimeType("image/jpeg")
                    .fileSize(2048L)
                    .build();
            when(metaWhatsAppClient.fetchMediaMetadata(mediaId, accessToken)).thenReturn(metaMedia);

            when(metaWhatsAppClient.streamMedia(eq(metaMedia.getUrl()), eq(accessToken), anyLong(), any(WhatsAppClient.MediaStreamConsumer.class)))
                    .thenAnswer(inv -> {
                        WhatsAppClient.MediaStreamConsumer<String> consumer = inv.getArgument(3);
                        InputStream dummyStream = new ByteArrayInputStream(new byte[]{1, 2, 3});
                        return consumer.consume(dummyStream);
                    });

            when(cloudinaryStorageService.isConfigured()).thenReturn(true);
            when(cloudinaryStorageService.uploadTenantStream(eq(tenantId), eq("whatsapp_media"), anyString(), any(InputStream.class), eq("image")))
                    .thenReturn("https://res.cloudinary.com/demo/image/upload/v1/tenants/test/receipt.jpg");

            ingressService.resolveAndSaveIngress(context);

            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            verify(messageRepository).save(messageCaptor.capture());
            Message saved = messageCaptor.getValue();

            assertNotNull(saved);
            assertEquals("IMAGE", saved.getMediaType());
            assertEquals("https://res.cloudinary.com/demo/image/upload/v1/tenants/test/receipt.jpg", saved.getMediaUrl());
            assertEquals(mediaId, saved.getMediaId());
            assertEquals("image/jpeg", saved.getMimeType());
            assertEquals("Check out this receipt", saved.getContent());
            assertEquals(2048L, saved.getFileSize());

            verify(distributedWebSocketPublisher).publishMessage(eq(tenantId), anyMap());
        }

        @Test
        @DisplayName("19. Incoming Voice note message processed and streamed as video resource type")
        @SuppressWarnings("unchecked")
        void testIncomingVoiceNoteProcessing() throws Exception {
            String messageId = "wamid.voice.004";
            String mediaId = "media_meta_voice";
            String jsonPayload = "{" +
                    "  \"entry\": [{" +
                    "    \"changes\": [{" +
                    "      \"value\": {" +
                    "        \"messaging_product\": \"whatsapp\"," +
                    "        \"contacts\": [{\"profile\": {\"name\": \"Alice\"}, \"wa_id\": \"" + waId + "\"}]," +
                    "        \"messages\": [{" +
                    "          \"from\": \"" + waId + "\"," +
                    "          \"id\": \"" + messageId + "\"," +
                    "          \"timestamp\": \"1700000000\"," +
                    "          \"type\": \"audio\"," +
                    "          \"audio\": {" +
                    "            \"id\": \"" + mediaId + "\"," +
                    "            \"mime_type\": \"audio/ogg; codecs=opus\"," +
                    "            \"voice\": true" +
                    "          }" +
                    "        }]" +
                    "      }" +
                    "    }]" +
                    "  }]" +
                    "}";

            ProcessingContext context = ProcessingContext.builder()
                    .messageId(messageId)
                    .tenantId(tenantId)
                    .waId(waId)
                    .payload(jsonPayload)
                    .timestamp(1700000000000L)
                    .build();

            WhatsAppConfig config = createConfig();
            Contact contact = createContact();

            when(idempotencyService.markAsProcessing(messageId, tenantId)).thenReturn(true);
            when(whatsappConfigRepository.findByTenantId(tenantId)).thenReturn(Optional.of(config));
            when(contactRepository.findByWaIdAndTenant_Id(eq(waId), any())).thenReturn(Optional.of(contact));
            when(messageRepository.findFirstByMediaIdOrderByTimestampDesc(mediaId)).thenReturn(Optional.empty());

            MetaMediaDto metaMedia = MetaMediaDto.builder()
                    .id(mediaId)
                    .url("https://lookaside.fbsbx.com/whatsapp_business/attachments/voice123")
                    .mimeType("audio/ogg")
                    .fileSize(15000L)
                    .build();
            when(metaWhatsAppClient.fetchMediaMetadata(mediaId, accessToken)).thenReturn(metaMedia);

            when(metaWhatsAppClient.streamMedia(eq(metaMedia.getUrl()), eq(accessToken), anyLong(), any(WhatsAppClient.MediaStreamConsumer.class)))
                    .thenAnswer(inv -> {
                        WhatsAppClient.MediaStreamConsumer<String> consumer = inv.getArgument(3);
                        InputStream dummyStream = new ByteArrayInputStream(new byte[15000]);
                        return consumer.consume(dummyStream);
                    });

            when(cloudinaryStorageService.isConfigured()).thenReturn(true);
            when(cloudinaryStorageService.uploadTenantStream(eq(tenantId), eq("whatsapp_media"), anyString(), any(InputStream.class), eq("video")))
                    .thenReturn("https://res.cloudinary.com/demo/video/upload/v1/voice.ogg");

            ingressService.resolveAndSaveIngress(context);

            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            verify(messageRepository).save(messageCaptor.capture());
            Message saved = messageCaptor.getValue();

            assertNotNull(saved);
            assertEquals("VOICE", saved.getMediaType());
            assertEquals("https://res.cloudinary.com/demo/video/upload/v1/voice.ogg", saved.getMediaUrl());
            assertEquals(15000L, saved.getFileSize());
        }

        @Test
        @DisplayName("20. Incoming Document message handled with PDF/raw resource type")
        @SuppressWarnings("unchecked")
        void testIncomingDocumentProcessing() throws Exception {
            String messageId = "wamid.doc.005";
            String mediaId = "media_meta_doc";
            String jsonPayload = "{" +
                    "  \"entry\": [{" +
                    "    \"changes\": [{" +
                    "      \"value\": {" +
                    "        \"messaging_product\": \"whatsapp\"," +
                    "        \"contacts\": [{\"profile\": {\"name\": \"Bob\"}, \"wa_id\": \"" + waId + "\"}]," +
                    "        \"messages\": [{" +
                    "          \"from\": \"" + waId + "\"," +
                    "          \"id\": \"" + messageId + "\"," +
                    "          \"timestamp\": \"1700000000\"," +
                    "          \"type\": \"document\"," +
                    "          \"document\": {" +
                    "            \"id\": \"" + mediaId + "\"," +
                    "            \"mime_type\": \"application/pdf\"," +
                    "            \"filename\": \"contract_2026.pdf\"" +
                    "          }" +
                    "        }]" +
                    "      }" +
                    "    }]" +
                    "  }]" +
                    "}";

            ProcessingContext context = ProcessingContext.builder()
                    .messageId(messageId)
                    .tenantId(tenantId)
                    .waId(waId)
                    .payload(jsonPayload)
                    .timestamp(1700000000000L)
                    .build();

            WhatsAppConfig config = createConfig();
            Contact contact = createContact();

            when(idempotencyService.markAsProcessing(messageId, tenantId)).thenReturn(true);
            when(whatsappConfigRepository.findByTenantId(tenantId)).thenReturn(Optional.of(config));
            when(contactRepository.findByWaIdAndTenant_Id(eq(waId), any())).thenReturn(Optional.of(contact));
            when(messageRepository.findFirstByMediaIdOrderByTimestampDesc(mediaId)).thenReturn(Optional.empty());

            MetaMediaDto metaMedia = MetaMediaDto.builder()
                    .id(mediaId)
                    .url("https://lookaside.fbsbx.com/whatsapp_business/attachments/doc123")
                    .mimeType("application/pdf")
                    .fileSize(50000L)
                    .build();
            when(metaWhatsAppClient.fetchMediaMetadata(mediaId, accessToken)).thenReturn(metaMedia);

            when(metaWhatsAppClient.streamMedia(eq(metaMedia.getUrl()), eq(accessToken), anyLong(), any(WhatsAppClient.MediaStreamConsumer.class)))
                    .thenAnswer(inv -> {
                        WhatsAppClient.MediaStreamConsumer<String> consumer = inv.getArgument(3);
                        InputStream dummyStream = new ByteArrayInputStream(new byte[50000]);
                        return consumer.consume(dummyStream);
                    });

            when(cloudinaryStorageService.isConfigured()).thenReturn(true);
            when(cloudinaryStorageService.uploadTenantStream(eq(tenantId), eq("whatsapp_media"), anyString(), any(InputStream.class), eq("raw")))
                    .thenReturn("https://res.cloudinary.com/demo/raw/upload/v1/contract_2026.pdf");

            ingressService.resolveAndSaveIngress(context);

            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            verify(messageRepository).save(messageCaptor.capture());
            Message saved = messageCaptor.getValue();

            assertNotNull(saved);
            assertEquals("DOCUMENT", saved.getMediaType());
            assertEquals("contract_2026.pdf", saved.getFileName());
            assertEquals("https://res.cloudinary.com/demo/raw/upload/v1/contract_2026.pdf", saved.getMediaUrl());
        }

        @Test
        @DisplayName("21. Media deduplication reuses existing Cloudinary URL without re-downloading")
        void testIncomingMediaDeduplication() {
            String messageId = "wamid.dedup.002";
            String mediaId = "media_meta_duplicate";
            String jsonPayload = "{" +
                    "  \"entry\": [{" +
                    "    \"changes\": [{" +
                    "      \"value\": {" +
                    "        \"messaging_product\": \"whatsapp\"," +
                    "        \"contacts\": [{\"profile\": {\"name\": \"John Doe\"}, \"wa_id\": \"" + waId + "\"}]," +
                    "        \"messages\": [{" +
                    "          \"from\": \"" + waId + "\"," +
                    "          \"id\": \"" + messageId + "\"," +
                    "          \"timestamp\": \"1700000000\"," +
                    "          \"type\": \"image\"," +
                    "          \"image\": {" +
                    "            \"id\": \"" + mediaId + "\"," +
                    "            \"mime_type\": \"image/png\"" +
                    "          }" +
                    "        }]" +
                    "      }" +
                    "    }]" +
                    "  }]" +
                    "}";

            ProcessingContext context = ProcessingContext.builder()
                    .messageId(messageId)
                    .tenantId(tenantId)
                    .waId(waId)
                    .payload(jsonPayload)
                    .timestamp(1700000000000L)
                    .build();

            WhatsAppConfig config = createConfig();
            Contact contact = createContact();

            Message existingMsg = Message.builder()
                    .mediaId(mediaId)
                    .mediaType("IMAGE")
                    .mimeType("image/png")
                    .fileName("cached_image.png")
                    .mediaUrl("https://res.cloudinary.com/demo/image/upload/v1/cached_image.png")
                    .fileSize(5000L)
                    .build();

            when(idempotencyService.markAsProcessing(messageId, tenantId)).thenReturn(true);
            when(whatsappConfigRepository.findByTenantId(tenantId)).thenReturn(Optional.of(config));
            when(contactRepository.findByWaIdAndTenant_Id(eq(waId), any())).thenReturn(Optional.of(contact));
            when(messageRepository.findFirstByMediaIdOrderByTimestampDesc(mediaId)).thenReturn(Optional.of(existingMsg));

            ingressService.resolveAndSaveIngress(context);

            verify(metaWhatsAppClient, never()).fetchMediaMetadata(anyString(), anyString());
            verify(metaWhatsAppClient, never()).streamMedia(anyString(), anyString(), anyLong(), any());

            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            verify(messageRepository).save(messageCaptor.capture());
            Message saved = messageCaptor.getValue();

            assertEquals("https://res.cloudinary.com/demo/image/upload/v1/cached_image.png", saved.getMediaUrl());
            assertEquals("IMAGE", saved.getMediaType());
            assertEquals(5000L, saved.getFileSize());
        }

        @Test
        @DisplayName("22. Oversized media reported in Meta metadata is rejected before downloading")
        void testOversizedMetadataRejectedBeforeDownload() {
            String messageId = "wamid.oversized.006";
            String mediaId = "media_meta_huge";
            String jsonPayload = "{" +
                    "  \"entry\": [{" +
                    "    \"changes\": [{" +
                    "      \"value\": {" +
                    "        \"messaging_product\": \"whatsapp\"," +
                    "        \"contacts\": [{\"profile\": {\"name\": \"John Doe\"}, \"wa_id\": \"" + waId + "\"}]," +
                    "        \"messages\": [{" +
                    "          \"from\": \"" + waId + "\"," +
                    "          \"id\": \"" + messageId + "\"," +
                    "          \"timestamp\": \"1700000000\"," +
                    "          \"type\": \"video\"," +
                    "          \"video\": {" +
                    "            \"id\": \"" + mediaId + "\"," +
                    "            \"mime_type\": \"video/mp4\"" +
                    "          }" +
                    "        }]" +
                    "      }" +
                    "    }]" +
                    "  }]" +
                    "}";

            ProcessingContext context = ProcessingContext.builder()
                    .messageId(messageId)
                    .tenantId(tenantId)
                    .waId(waId)
                    .payload(jsonPayload)
                    .timestamp(1700000000000L)
                    .build();

            WhatsAppConfig config = createConfig();
            Contact contact = createContact();

            when(idempotencyService.markAsProcessing(messageId, tenantId)).thenReturn(true);
            when(whatsappConfigRepository.findByTenantId(tenantId)).thenReturn(Optional.of(config));
            when(contactRepository.findByWaIdAndTenant_Id(eq(waId), any())).thenReturn(Optional.of(contact));
            when(messageRepository.findFirstByMediaIdOrderByTimestampDesc(mediaId)).thenReturn(Optional.empty());

            // 150 MB reported in metadata (Limit for video is 64 MB)
            MetaMediaDto metaMedia = MetaMediaDto.builder()
                    .id(mediaId)
                    .url("https://lookaside.fbsbx.com/whatsapp_business/attachments/huge_vid")
                    .mimeType("video/mp4")
                    .fileSize(150L * 1024 * 1024)
                    .build();
            when(metaWhatsAppClient.fetchMediaMetadata(mediaId, accessToken)).thenReturn(metaMedia);

            ingressService.resolveAndSaveIngress(context);

            // Stream body must NEVER be requested
            verify(metaWhatsAppClient, never()).streamMedia(anyString(), anyString(), anyLong(), any());

            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            verify(messageRepository).save(messageCaptor.capture());
            Message saved = messageCaptor.getValue();

            assertEquals("[VIDEO: Size limit exceeded]", saved.getContent());
            assertNull(saved.getMediaUrl());
        }

        @Test
        @DisplayName("23. Graceful fallback when Meta download encounters 404/failure")
        void testIncomingMediaGracefulFallbackOnMetaFailure() {
            String messageId = "wamid.fail.003";
            String mediaId = "media_meta_broken";
            String jsonPayload = "{" +
                    "  \"entry\": [{" +
                    "    \"changes\": [{" +
                    "      \"value\": {" +
                    "        \"messaging_product\": \"whatsapp\"," +
                    "        \"contacts\": [{\"profile\": {\"name\": \"John Doe\"}, \"wa_id\": \"" + waId + "\"}]," +
                    "        \"messages\": [{" +
                    "          \"from\": \"" + waId + "\"," +
                    "          \"id\": \"" + messageId + "\"," +
                    "          \"timestamp\": \"1700000000\"," +
                    "          \"type\": \"document\"," +
                    "          \"document\": {" +
                    "            \"id\": \"" + mediaId + "\"," +
                    "            \"mime_type\": \"application/pdf\"," +
                    "            \"filename\": \"contract.pdf\"" +
                    "          }" +
                    "        }]" +
                    "      }" +
                    "    }]" +
                    "  }]" +
                    "}";

            ProcessingContext context = ProcessingContext.builder()
                    .messageId(messageId)
                    .tenantId(tenantId)
                    .waId(waId)
                    .payload(jsonPayload)
                    .timestamp(1700000000000L)
                    .build();

            WhatsAppConfig config = createConfig();
            Contact contact = createContact();

            when(idempotencyService.markAsProcessing(messageId, tenantId)).thenReturn(true);
            when(whatsappConfigRepository.findByTenantId(tenantId)).thenReturn(Optional.of(config));
            when(contactRepository.findByWaIdAndTenant_Id(eq(waId), any())).thenReturn(Optional.of(contact));
            when(messageRepository.findFirstByMediaIdOrderByTimestampDesc(mediaId)).thenReturn(Optional.empty());

            when(metaWhatsAppClient.fetchMediaMetadata(mediaId, accessToken))
                    .thenThrow(new RuntimeException("Meta API 404: Media not found or expired"));

            ingressService.resolveAndSaveIngress(context);

            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            verify(messageRepository).save(messageCaptor.capture());
            Message saved = messageCaptor.getValue();

            assertNotNull(saved);
            assertEquals("DOCUMENT", saved.getMediaType());
            assertEquals("contract.pdf", saved.getFileName());
            assertEquals("[DOCUMENT: Download failed]", saved.getContent());
            assertNull(saved.getMediaUrl());
        }
    }
}
