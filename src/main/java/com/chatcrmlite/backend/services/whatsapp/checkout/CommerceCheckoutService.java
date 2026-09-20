package com.chatcrmlite.backend.services.whatsapp.checkout;

import com.chatcrmlite.backend.dto.MenuDto;
import com.chatcrmlite.backend.dto.payments.PaymentCreationContext;
import com.chatcrmlite.backend.dto.payments.ProviderPaymentCreationResult;
import com.chatcrmlite.backend.models.CommerceCheckoutSession;
import com.chatcrmlite.backend.models.CommerceOrder;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.CommerceCheckoutSessionRepository;
import com.chatcrmlite.backend.repositories.CommerceOrderRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.services.RedisStateService;
import com.chatcrmlite.backend.services.payments.providers.RazorpayDirectPaymentProvider;
import com.chatcrmlite.backend.services.whatsapp.WhatsAppOutboundService;
import com.chatcrmlite.backend.models.enums.*;
import com.chatcrmlite.backend.models.payments.PaymentTransaction;
import com.chatcrmlite.backend.models.payments.WhatsAppOrder;
import com.chatcrmlite.backend.repositories.payments.PaymentTransactionRepository;
import com.chatcrmlite.backend.repositories.payments.WhatsAppOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommerceCheckoutService {

    private final CommerceCheckoutSessionRepository checkoutSessionRepository;
    private final CommerceOrderRepository orderRepository;
    private final WhatsAppConfigRepository whatsappConfigRepository;
    private final WhatsAppOutboundService outboundService;
    private final CheckoutAddressParser addressParser;
    private final RazorpayDirectPaymentProvider razorpayDirectPaymentProvider;
    private final RedisStateService redisStateService;
    private final com.chatcrmlite.backend.repositories.UserRepository userRepository;
    private final WhatsAppOrderRepository whatsAppOrderRepository;
    private final PaymentTransactionRepository transactionRepository;

    private com.chatcrmlite.backend.models.User resolveOwner(Contact contact, UUID tenantId) {
        if (contact != null && contact.getOwner() != null) {
            return contact.getOwner();
        }
        if (contact != null && contact.getTenant() != null && userRepository != null) {
            List<com.chatcrmlite.backend.models.User> users = userRepository.findAllByTenant(contact.getTenant());
            if (!users.isEmpty()) {
                return users.stream()
                        .filter(u -> u.getRole() == com.chatcrmlite.backend.models.User.Role.OWNER || u.getRole() == com.chatcrmlite.backend.models.User.Role.ADMIN)
                        .findFirst()
                        .orElse(users.get(0));
            }
        }
        return null;
    }

    /**
     * Called when a new cart order arrives from WhatsApp native cart.
     */
    @Transactional
    public void initiateCheckout(CommerceOrder order, Contact contact) {
        UUID tenantId = order.getTenant().getId();
        String customerWaId = order.getCustomerWaId();

        // 1. Session lifecycle: supersede prior active sessions & create new session
        Optional<CommerceCheckoutSession> existingActiveOpt = checkoutSessionRepository.findActiveSession(tenantId, customerWaId);
        if (existingActiveOpt.isPresent()) {
            CommerceCheckoutSession oldSession = existingActiveOpt.get();
            oldSession.setCheckoutStep("SUPERSEDED");
            checkoutSessionRepository.save(oldSession);
            log.info("🔄 Superseded previous active checkout session {} for customer {}", oldSession.getId(), customerWaId);
        }

        CommerceCheckoutSession newSession = CommerceCheckoutSession.builder()
                .tenant(order.getTenant())
                .order(order)
                .customerWaId(customerWaId)
                .checkoutStep("AWAITING_ADDRESS")
                .expiresAt(LocalDateTime.now().plusMinutes(15))
                .build();
        checkoutSessionRepository.save(newSession);

        // 2. Dispatch address prompt outside DB transaction with idempotency lease
        String leaseKey = "checkout:" + order.getId() + ":address-prompt";
        String token = UUID.randomUUID().toString();
        boolean locked = redisStateService.tryLock(leaseKey, token, Duration.ofMinutes(15));
        if (!locked) {
            log.info("Address prompt already leased for order {}. Skipping duplicate prompt.", order.getId());
            return;
        }

        try {
            WhatsAppConfig config = whatsappConfigRepository.findByTenantId(tenantId).orElse(null);
            if (config == null) {
                log.warn("WhatsAppConfig not found for tenant {}. Cannot send address prompt.", tenantId);
                return;
            }

            String customerName = contact.getName() != null && !contact.getName().isBlank() ? contact.getName() : "Customer";
            String prompt = String.format(
                    "🛒 *Order Received!*\n" +
                    "Order ID: #%s\n" +
                    "Total Amount: ₹%s (%d item%s)\n\n" +
                    "Please reply with your delivery address & email to complete checkout:\n\n" +
                    "Name: %s\n" +
                    "Address: Flat/House No, Street, Area\n" +
                    "City: City Name\n" +
                    "PIN: 6-digit Pincode\n" +
                    "Email: name@example.com",
                    order.getId().toString().substring(0, 8),
                    order.getTotal().toPlainString(),
                    order.getItems().size(),
                    order.getItems().size() > 1 ? "s" : "",
                    customerName
            );

            outboundService.sendText(contact, prompt, config, resolveOwner(contact, tenantId));
            log.info("📤 Dispatched address prompt to customer {} for order {}", customerWaId, order.getId());
        } catch (Exception e) {
            log.error("Failed to send address prompt to customer {}: {}", customerWaId, e.getMessage(), e);
        }
    }

    /**
     * Checks if a customer currently has an active checkout session.
     */
    public boolean hasActiveSession(UUID tenantId, String customerWaId) {
        return checkoutSessionRepository.findActiveSession(tenantId, customerWaId).isPresent();
    }

    /**
     * Routes text message from customer to checkout session handler.
     * Returns true if message was consumed by checkout.
     */
    public boolean handleCustomerMessage(UUID tenantId, String customerWaId, String text, Contact contact) {
        Optional<CommerceCheckoutSession> sessionOpt = checkoutSessionRepository.findActiveSession(tenantId, customerWaId);
        if (sessionOpt.isEmpty()) {
            return false;
        }

        CommerceCheckoutSession session = sessionOpt.get();

        // Lazy expiry check
        if (session.getExpiresAt().isBefore(LocalDateTime.now())) {
            expireSession(session);
            return false;
        }

        WhatsAppConfig config = whatsappConfigRepository.findByTenantId(tenantId).orElse(null);
        if (config == null) return false;

        if ("AWAITING_ADDRESS".equals(session.getCheckoutStep())) {
            handleAddressSubmission(session, text, contact, config);
            return true;
        } else if ("AWAITING_PAYMENT_CHOICE".equals(session.getCheckoutStep())) {
            // If they replied text instead of clicking buttons, guide them
            String prompt = "Please tap one of the payment buttons above (Pay Online or Cash on Delivery) to continue.";
            outboundService.sendText(contact, prompt, config, resolveOwner(contact, tenantId));
            return true;
        } else if ("AWAITING_PAYMENT".equals(session.getCheckoutStep())) {
            // Already sent payment link
            CommerceOrder order = session.getOrder();
            if (order.getPaymentLinkUrl() != null) {
                String prompt = String.format(
                        "Please complete your payment using the link below to confirm your order:\n%s\n\nNeed help? Let us know!",
                        order.getPaymentLinkUrl()
                );
                outboundService.sendText(contact, prompt, config, resolveOwner(contact, tenantId));
                return true;
            }
        }

        return false;
    }

    /**
     * Handles interactive button reply for checkout payment selection.
     */
    public boolean handleInteractiveButton(UUID tenantId, String customerWaId, String buttonId, Contact contact) {
        if (buttonId == null || (!buttonId.startsWith("PAY_ONLINE_") && !buttonId.startsWith("PAY_COD_"))) {
            return false;
        }

        String orderIdStr = buttonId.startsWith("PAY_ONLINE_") 
                ? buttonId.substring("PAY_ONLINE_".length()) 
                : buttonId.substring("PAY_COD_".length());

        UUID orderId;
        try {
            orderId = UUID.fromString(orderIdStr);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid order UUID in buttonId: {}", buttonId);
            return false;
        }

        Optional<CommerceOrder> orderOpt = orderRepository.findByIdAndTenantId(orderId, tenantId);
        if (orderOpt.isEmpty()) {
            log.warn("Order {} not found for tenant {}", orderId, tenantId);
            return false;
        }

        CommerceOrder order = orderOpt.get();
        Optional<CommerceCheckoutSession> sessionOpt = checkoutSessionRepository.findByOrderId(orderId);
        CommerceCheckoutSession session = sessionOpt.orElse(null);

        WhatsAppConfig config = whatsappConfigRepository.findByTenantId(tenantId).orElse(null);
        if (config == null) return false;

        if (buttonId.startsWith("PAY_ONLINE_")) {
            if (session != null) {
                session.setCheckoutStep("AWAITING_PAYMENT");
                checkoutSessionRepository.save(session);
            }
            order.setPaymentMethod("ONLINE");
            orderRepository.save(order);

            generateAndSendPaymentLink(order, contact, config);
            return true;
        } else if (buttonId.startsWith("PAY_COD_")) {
            if (session != null) {
                session.setCheckoutStep("COMPLETED");
                checkoutSessionRepository.save(session);
            }
            order.setPaymentMethod("COD");
            order.setPaymentStatus("PENDING_COD");
            order.setStatus("PLACED");
            order.setCheckoutStatus("COMPLETED");
            orderRepository.save(order);

            String confirmation = String.format(
                    "🎉 *Order Confirmed (Cash on Delivery)*\n" +
                    "Order ID: #%s\n" +
                    "Total Amount: ₹%s\n\n" +
                    "📍 *Delivery Address:*\n%s\n\n" +
                    "We have received your order and are preparing it for delivery. Thank you for shopping with us!",
                    order.getId().toString().substring(0, 8),
                    order.getTotal().toPlainString(),
                    order.getShippingAddress() != null ? order.getShippingAddress() : "As provided"
            );
            outboundService.sendText(contact, confirmation, config, resolveOwner(contact, order.getTenant().getId()));
            log.info("📦 Order {} confirmed as COD for customer {}", order.getId(), customerWaId);
            return true;
        }

        return false;
    }

    private void handleAddressSubmission(CommerceCheckoutSession session, String text, Contact contact, WhatsAppConfig config) {
        ParsedAddress address = addressParser.parse(text, contact.getName());

        if (!address.isValid()) {
            sendAddressCorrectionPrompt(address, contact, config);
            return;
        }

        CommerceOrder order = session.getOrder();

        // Update order with validated address and contact details
        order.setShippingName(address.getShippingName());
        order.setAddressLine1(address.getAddressLine1());
        order.setAddressLine2(address.getAddressLine2());
        order.setCity(address.getCity());
        order.setState(address.getState());
        order.setPostalCode(address.getPostalCode());
        order.setCountry(address.getCountry());
        order.setShippingAddress(address.getFullFormattedAddress());
        order.setCustomerEmail(address.getCustomerEmail());
        order.setCheckoutStatus("DETAILS_COLLECTED");
        orderRepository.save(order);

        boolean onlineEnabled = order.isOnlinePaymentEnabledAtOrder();
        boolean codEnabled = order.isCodEnabledAtOrder();

        log.info("Address validated for order {}. onlineEnabled={}, codEnabled={}", order.getId(), onlineEnabled, codEnabled);

        if (onlineEnabled && codEnabled) {
            // Case A: Both options available -> Interactive Buttons
            session.setCheckoutStep("AWAITING_PAYMENT_CHOICE");
            checkoutSessionRepository.save(session);

            sendPaymentChoiceButtons(order, contact, config, address);
        } else if (onlineEnabled) {
            // Case B: Only Online Payment available
            session.setCheckoutStep("AWAITING_PAYMENT");
            checkoutSessionRepository.save(session);

            generateAndSendPaymentLink(order, contact, config);
        } else if (codEnabled) {
            // Case C: Only COD available
            session.setCheckoutStep("COMPLETED");
            checkoutSessionRepository.save(session);

            order.setPaymentMethod("COD");
            order.setPaymentStatus("PENDING_COD");
            order.setStatus("PLACED");
            order.setCheckoutStatus("COMPLETED");
            orderRepository.save(order);

            String confirmation = String.format(
                    "📍 *Delivery Details Saved!*\n%s\nEmail: %s\n\n" +
                    "🎉 *Order Confirmed (Cash on Delivery)*\n" +
                    "Order ID: #%s\n" +
                    "Total Amount: ₹%s\n\n" +
                    "Your order has been placed. You can pay with cash upon delivery!",
                    address.getFullFormattedAddress(),
                    address.getCustomerEmail(),
                    order.getId().toString().substring(0, 8),
                    order.getTotal().toPlainString()
            );
            outboundService.sendText(contact, confirmation, config, resolveOwner(contact, order.getTenant().getId()));
        } else {
            // Case D: Both disabled
            session.setCheckoutStep("COMPLETED");
            checkoutSessionRepository.save(session);

            order.setCheckoutStatus("PAYMENT_CONFIGURATION_REQUIRED");
            order.setStatus("PENDING_MANUAL_REVIEW");
            orderRepository.save(order);

            String fallbackMsg = String.format(
                    "📍 *Delivery Details Saved!*\n%s\nEmail: %s\n\n" +
                    "⚠️ Online payment and Cash on Delivery are currently unavailable for this catalog.\n" +
                    "Our support team will contact you shortly to confirm and complete your order.",
                    address.getFullFormattedAddress(),
                    address.getCustomerEmail()
            );
            outboundService.sendText(contact, fallbackMsg, config, resolveOwner(contact, order.getTenant().getId()));
            log.warn("⚠️ Both payment options disabled for order {}. Customer notified.", order.getId());
        }
    }

    private void sendAddressCorrectionPrompt(ParsedAddress address, Contact contact, WhatsAppConfig config) {
        String msg;
        if (address.isHasOnlyEmail()) {
            msg = String.format(
                    "✅ Email saved: %s\n\n" +
                    "Please now send your delivery address and 6-digit Pincode to proceed:\n\n" +
                    "Example:\nHouse 12, MG Road, Mumbai - 400001",
                    address.getCustomerEmail()
            );
        } else if (address.isHasOnlyAddress()) {
            msg = "✅ Delivery address noted!\n\n" +
                  "Please also provide your email address (e.g. yourname@example.com) to receive order updates and complete checkout.";
        } else {
            StringBuilder sb = new StringBuilder("⚠️ Some required details are missing:\n");
            for (String field : address.getMissingFields()) {
                sb.append("• ").append(field).append("\n");
            }
            sb.append("\nPlease reply with your complete address, 6-digit Pincode, and Email address.");
            msg = sb.toString();
        }
        outboundService.sendText(contact, msg, config, resolveOwner(contact, contact.getTenant() != null ? contact.getTenant().getId() : null));
    }

    private void sendPaymentChoiceButtons(CommerceOrder order, Contact contact, WhatsAppConfig config, ParsedAddress address) {
        String body = String.format(
                "📍 *Delivery Details Saved!*\n" +
                "%s\n" +
                "Email: %s\n\n" +
                "Total Amount: ₹%s\n\n" +
                "Please choose your preferred payment method:",
                address.getFullFormattedAddress(),
                address.getCustomerEmail(),
                order.getTotal().toPlainString()
        );

        MenuDto menu = MenuDto.builder()
                .type("button")
                .bodyText(body)
                .sections(List.of(
                        MenuDto.MenuSectionDto.builder()
                                .title("Payment")
                                .rows(List.of(
                                        MenuDto.MenuRowDto.builder()
                                                .id("PAY_ONLINE_" + order.getId())
                                                .title("Pay Online (UPI)")
                                                .build(),
                                        MenuDto.MenuRowDto.builder()
                                                .id("PAY_COD_" + order.getId())
                                                .title("Cash on Delivery")
                                                .build()
                                ))
                                .build()
                ))
                .build();

        outboundService.sendInteractiveMenu(contact, menu, config, resolveOwner(contact, order.getTenant().getId()));
        log.info("🔘 Sent payment options buttons to customer {} for order {}", contact.getWaId(), order.getId());
    }

    private void generateAndSendPaymentLink(CommerceOrder order, Contact contact, WhatsAppConfig config) {
        // If payment link already generated for this order, reuse it directly!
        if (order.getPaymentLinkUrl() != null && !order.getPaymentLinkUrl().isBlank()) {
            sendPaymentLinkMessage(order, contact, config, order.getPaymentLinkUrl());
            return;
        }

        String leaseKey = "checkout:" + order.getId() + ":payment-link";
        String token = UUID.randomUUID().toString();
        boolean locked = redisStateService.tryLock(leaseKey, token, Duration.ofSeconds(30));
        if (!locked) {
            log.info("Payment link creation already in progress for order {}", order.getId());
            return;
        }

        try {
            UUID tenantId = order.getTenant().getId();
            long amountMinor = order.getTotal().multiply(new BigDecimal("100")).longValue();

            PaymentCreationContext context = PaymentCreationContext.builder()
                    .amountMinor(amountMinor)
                    .currency(order.getCurrency() != null ? order.getCurrency() : "INR")
                    .orderReference(order.getId().toString())
                    .customerName(order.getShippingName() != null ? order.getShippingName() : contact.getName())
                    .customerWaId(contact.getWaId())
                    .build();

            // External API call strictly outside DB lock
            ProviderPaymentCreationResult result = razorpayDirectPaymentProvider.createProviderOrder(tenantId, context);

            if (result.isSuccess() && result.getCheckoutUrl() != null) {
                order.setPaymentLinkId(result.getProviderOrderId());
                order.setPaymentLinkUrl(result.getCheckoutUrl());
                order.setPaymentMethod("ONLINE");
                order.setPaymentProvider("RAZORPAY_DIRECT");
                order.setPaymentAmount(order.getTotal());
                order.setPaymentStatus("PAYMENT_LINK_CREATED");
                orderRepository.save(order);

                // Mirror into whatsapp_orders & payment_transactions so it immediately shows up in /payments dashboard
                try {
                    String refId = "INV-" + (System.currentTimeMillis() / 1000) + "-" + order.getId().toString().substring(0, 4).toUpperCase();
                    WhatsAppOrder wOrder = WhatsAppOrder.builder()
                        .tenantId(tenantId)
                        .referenceId(refId)
                        .externalReferenceId(order.getId().toString())
                        .customerId(order.getCustomerId())
                        .customerWaId(contact.getWaId())
                        .customerName(order.getShippingName() != null ? order.getShippingName() : contact.getName())
                        .currency(order.getCurrency() != null ? order.getCurrency() : "INR")
                        .subtotalMinor(order.getSubtotal().multiply(new BigDecimal("100")).longValue())
                        .discountMinor(order.getDiscount() != null ? order.getDiscount().multiply(new BigDecimal("100")).longValue() : 0L)
                        .taxMinor(order.getTax() != null ? order.getTax().multiply(new BigDecimal("100")).longValue() : 0L)
                        .shippingMinor(order.getShipping() != null ? order.getShipping().multiply(new BigDecimal("100")).longValue() : 0L)
                        .totalMinor(amountMinor)
                        .orderStatus(WhatsAppOrderStatus.CREATED)
                        .paymentStatus(WhatsAppOrderPaymentStatus.PAYMENT_PENDING)
                        .fulfillmentStatus(WhatsAppOrderFulfillmentStatus.UNFULFILLED)
                        .preferredPaymentMode(PaymentMode.DIRECT_LINK)
                        .preferredPaymentProvider(PaymentIntegrationType.RAZORPAY_DIRECT)
                        .build();
                    wOrder = whatsAppOrderRepository.save(wOrder);

                    PaymentTransaction tx = PaymentTransaction.builder()
                        .tenantId(tenantId)
                        .order(wOrder)
                        .provider(PaymentIntegrationType.RAZORPAY_DIRECT)
                        .paymentMode(PaymentMode.DIRECT_LINK)
                        .idempotencyKey("TX_" + order.getId() + "_" + UUID.randomUUID().toString().substring(0, 8))
                        .providerOrderId(result.getProviderOrderId())
                        .checkoutUrl(result.getCheckoutUrl())
                        .amountMinor(amountMinor)
                        .currency(order.getCurrency() != null ? order.getCurrency() : "INR")
                        .status(PaymentTransactionStatus.PENDING)
                        .build();
                    transactionRepository.save(tx);
                    log.info("🧾 Registered Invoice {} & Transaction in whatsapp_orders for CommerceOrder {}", refId, order.getId());
                } catch (Exception ex) {
                    log.warn("Could not register whatsapp_order for commerce order {}: {}", order.getId(), ex.getMessage());
                }

                sendPaymentLinkMessage(order, contact, config, result.getCheckoutUrl());
            } else {
                log.error("Failed to create Razorpay payment link for order {}: {}", order.getId(), result.getErrorMessage());
                String errorMsg = "⚠️ We encountered an issue creating your online payment link. Our support team will assist you shortly.";
                outboundService.sendText(contact, errorMsg, config, resolveOwner(contact, tenantId));
            }
        } catch (IllegalStateException e) {
            // Payment gateway not configured for this tenant — inform customer gracefully
            log.error("⚠️ Payment gateway not configured for tenant {} on order {}: {}", order.getTenant().getId(), order.getId(), e.getMessage());
            try {
                // Update order so staff knows action is needed
                order.setCheckoutStatus("PAYMENT_CONFIGURATION_REQUIRED");
                order.setStatus("PENDING_MANUAL_REVIEW");
                orderRepository.save(order);

                // Friendly message to customer
                String noGatewayMsg = String.format(
                    "📍 *Order Details Received!*\n\n" +
                    "Thank you! Your delivery details have been saved for Order #%s (₹%s).\n\n" +
                    "⚠️ *Online payment is not available at the moment.*\n" +
                    "Our support team will contact you shortly to complete your order.\n\n" +
                    "We apologise for the inconvenience!",
                    order.getId().toString().substring(0, 8),
                    order.getTotal().toPlainString()
                );
                outboundService.sendText(contact, noGatewayMsg, config, resolveOwner(contact, order.getTenant().getId()));

                // Expire the checkout session so customer isn't stuck
                checkoutSessionRepository.findActiveSession(order.getTenant().getId(), contact.getWaId())
                    .ifPresent(s -> {
                        s.setCheckoutStep("COMPLETED");
                        checkoutSessionRepository.save(s);
                    });
            } catch (Exception notifyErr) {
                log.error("Failed to notify customer of gateway config error for order {}: {}", order.getId(), notifyErr.getMessage());
            }
        } catch (Exception e) {
            log.error("Error creating payment link for order {}: {}", order.getId(), e.getMessage(), e);
            try {
                String errorMsg = "⚠️ We encountered an issue creating your payment link. Our support team will contact you shortly to complete your order.";
                outboundService.sendText(contact, errorMsg, config, resolveOwner(contact, order.getTenant().getId()));
            } catch (Exception notifyErr) {
                log.error("Failed to notify customer of payment error for order {}: {}", order.getId(), notifyErr.getMessage());
            }
        } finally {
            redisStateService.delete(leaseKey);
        }
    }

    private void sendPaymentLinkMessage(CommerceOrder order, Contact contact, WhatsAppConfig config, String paymentUrl) {
        String msg = String.format(
                "💳 *Payment Link - Order #%s*\n\n" +
                "Amount to Pay: ₹%s\n\n" +
                "👉 Click here to pay securely via UPI, Cards, or NetBanking:\n%s\n\n" +
                "Once your payment is complete, your order will be confirmed immediately!",
                order.getId().toString().substring(0, 8),
                order.getTotal().toPlainString(),
                paymentUrl
        );

        outboundService.sendText(contact, msg, config, resolveOwner(contact, order.getTenant().getId()));
        log.info("🔗 Sent payment link to customer {} for order {}", contact.getWaId(), order.getId());
    }

    private void expireSession(CommerceCheckoutSession session) {
        try {
            session.setCheckoutStep("EXPIRED");
            checkoutSessionRepository.save(session);

            CommerceOrder order = session.getOrder();
            if ("AWAITING_CUSTOMER_DETAILS".equals(order.getCheckoutStatus())) {
                order.setCheckoutStatus("EXPIRED");
                order.setStatus("CANCELLED");
                orderRepository.save(order);
            }
            log.info("Checkout session {} expired for order {}", session.getId(), order.getId());
        } catch (Exception e) {
            log.error("Failed to expire session {}: {}", session.getId(), e.getMessage());
        }
    }
}
