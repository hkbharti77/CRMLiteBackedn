package com.chatcrmlite.backend.services.payments;

import com.chatcrmlite.backend.dto.payments.meta.InteractiveButtonMessageDto;
import com.chatcrmlite.backend.dto.payments.meta.OrderDetailsMessageDto;
import com.chatcrmlite.backend.dto.payments.meta.OrderStatusMessageDto;
import com.chatcrmlite.backend.models.payments.PaymentTransaction;
import com.chatcrmlite.backend.models.payments.TenantPaymentConfig;
import com.chatcrmlite.backend.models.payments.WhatsAppOrder;
import com.chatcrmlite.backend.models.payments.WhatsAppOrderItem;
import org.springframework.stereotype.Component;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

@Component
public class WhatsAppPaymentMessageFactory {

    public OrderDetailsMessageDto buildMetaNativeOrderDetails(
        WhatsAppOrder order,
        PaymentTransaction transaction,
        TenantPaymentConfig config
    ) {
        List<OrderDetailsMessageDto.OrderItem> items = new ArrayList<>();
        for (WhatsAppOrderItem item : order.getItems()) {
            items.add(OrderDetailsMessageDto.OrderItem.builder()
                .retailerId(item.getSku() != null ? item.getSku() : item.getId().toString())
                .name(item.getName())
                .amount(OrderDetailsMessageDto.Amount.builder()
                    .value(item.getUnitPriceMinor())
                    .offset(100)
                    .build())
                .quantity(item.getQuantity())
                .build());
        }

        OrderDetailsMessageDto.Order metaOrder = OrderDetailsMessageDto.Order.builder()
            .status("pending")
            .items(items)
            .subtotal(OrderDetailsMessageDto.Amount.builder().value(order.getSubtotalMinor()).offset(100).build())
            .tax(OrderDetailsMessageDto.Amount.builder().value(order.getTaxMinor()).offset(100).build())
            .discount(OrderDetailsMessageDto.Amount.builder().value(order.getDiscountMinor()).offset(100).build())
            .shipping(OrderDetailsMessageDto.Amount.builder().value(order.getShippingMinor()).offset(100).build())
            .build();

        String paymentConfigName = config != null ? config.getMetaPaymentConfigurationName() : "default";

        OrderDetailsMessageDto.ActionParameters parameters = OrderDetailsMessageDto.ActionParameters.builder()
            .referenceId(order.getReferenceId())
            .type("digital-goods")
            .paymentType("upi")
            .paymentConfiguration(paymentConfigName)
            .currency(order.getCurrency())
            .totalAmount(OrderDetailsMessageDto.Amount.builder().value(order.getTotalMinor()).offset(100).build())
            .order(metaOrder)
            .build();

        return OrderDetailsMessageDto.builder()
            .to(order.getCustomerWaId())
            .interactive(OrderDetailsMessageDto.Interactive.builder()
                .type("order_details")
                .header(OrderDetailsMessageDto.Header.builder()
                    .type("text")
                    .text("Invoice #" + order.getReferenceId())
                    .build())
                .body(OrderDetailsMessageDto.Body.builder()
                    .text("Hello " + (order.getCustomerName() != null ? order.getCustomerName() : "there") +
                          ", here is your bill. Please tap 'Review and Pay' below to complete payment.")
                    .build())
                .footer(OrderDetailsMessageDto.Footer.builder()
                    .text("Powered by ChatCRMLite Payments")
                    .build())
                .action(OrderDetailsMessageDto.Action.builder()
                    .name("review_and_pay")
                    .parameters(parameters)
                    .build())
                .build())
            .build();
    }

    public InteractiveButtonMessageDto buildDirectGatewayCtaMessage(
        WhatsAppOrder order,
        PaymentTransaction transaction
    ) {
        String checkoutUrl = transaction.getCheckoutUrl();
        double totalRupees = order.getTotalMinor() / 100.0;
        String formattedAmount = String.format("₹%.2f", totalRupees);

        return InteractiveButtonMessageDto.builder()
            .to(order.getCustomerWaId())
            .interactive(InteractiveButtonMessageDto.Interactive.builder()
                .type("cta_url")
                .header(InteractiveButtonMessageDto.Header.builder()
                    .type("text")
                    .text("Payment Request: " + formattedAmount)
                    .build())
                .body(InteractiveButtonMessageDto.Body.builder()
                    .text("Hello " + (order.getCustomerName() != null ? order.getCustomerName() : "there") +
                          ", your order #" + order.getReferenceId() + " is ready for payment. Total amount: " + formattedAmount +
                          ". Click below to pay securely.")
                    .build())
                .footer(InteractiveButtonMessageDto.Footer.builder()
                    .text("Secure Checkout")
                    .build())
                .action(InteractiveButtonMessageDto.Action.builder()
                    .name("cta_url")
                    .parameters(InteractiveButtonMessageDto.Parameters.builder()
                        .displayText("Pay Now (" + formattedAmount + ")")
                        .url(checkoutUrl)
                        .build())
                    .build())
                .build())
            .build();
    }

    public OrderStatusMessageDto buildOrderStatusUpdate(WhatsAppOrder order, String newStatusDescription) {
        return OrderStatusMessageDto.builder()
            .to(order.getCustomerWaId())
            .interactive(OrderStatusMessageDto.Interactive.builder()
                .type("order_status")
                .body(OrderStatusMessageDto.Body.builder()
                    .text("Order #" + order.getReferenceId() + " update: " + newStatusDescription)
                    .build())
                .action(OrderStatusMessageDto.Action.builder()
                    .name("review_order")
                    .parameters(OrderStatusMessageDto.Parameters.builder()
                        .referenceId(order.getReferenceId())
                        .order(OrderStatusMessageDto.Order.builder()
                            .status(order.getOrderStatus().name().toLowerCase())
                            .description(newStatusDescription)
                            .build())
                        .build())
                    .build())
                .build())
            .build();
    }

    public String buildReceiptTextMessage(WhatsAppOrder order, PaymentTransaction tx) {
        double totalRupees = order.getTotalMinor() / 100.0;
        StringBuilder sb = new StringBuilder();
        sb.append("🎉 *Payment Received - Thank You!*\n\n");
        sb.append("📋 *Order Ref:* ").append(order.getReferenceId()).append("\n");
        if (order.getCustomerName() != null) {
            sb.append("👤 *Customer:* ").append(order.getCustomerName()).append("\n");
        }
        sb.append("💰 *Amount Paid:* ₹").append(String.format("%.2f", totalRupees)).append("\n");
        sb.append("💳 *Payment Mode:* ").append(tx != null ? tx.getPaymentMode().name() : "Online").append("\n");
        if (tx != null && tx.getProviderPaymentId() != null) {
            sb.append("🧾 *Txn ID:* ").append(tx.getProviderPaymentId()).append("\n");
        }
        sb.append("⏱ *Date:* ").append(order.getPaidAt() != null ? order.getPaidAt().toString() : "Just now").append("\n\n");
        sb.append("Your order is confirmed and will be processed shortly.");
        return sb.toString();
    }

    public java.util.Map<String, Object> buildPaymentTemplateMessage(
        WhatsAppOrder order,
        PaymentTransaction transaction,
        String templateDefinitionKey,
        java.util.Map<String, String> customParams
    ) {
        String key = (templateDefinitionKey != null && !templateDefinitionKey.trim().isEmpty())
            ? templateDefinitionKey
            : "payment_order_invoice_v1";

        double totalRupees = order.getTotalMinor() / 100.0;
        String formattedAmount = String.format("%.2f", totalRupees);
        String customerName = order.getCustomerName() != null ? order.getCustomerName() : "Valued Customer";
        String firstItem = (!order.getItems().isEmpty()) ? order.getItems().get(0).getName() : "Services / Order";
        String checkoutUrl = (transaction != null && transaction.getCheckoutUrl() != null) ? transaction.getCheckoutUrl() : "";

        List<java.util.Map<String, String>> bodyParams = new ArrayList<>();

        if ("pending_bill_reminder_v1".equals(key)) {
            // {{1}}=customer_name, {{2}}=order_ref, {{3}}=amount, {{4}}=due_date
            String dueDate = customParams != null && customParams.containsKey("due_date") ? customParams.get("due_date") : "Due in 24 hours";
            bodyParams.add(java.util.Map.of("type", "text", "text", customerName));
            bodyParams.add(java.util.Map.of("type", "text", "text", order.getReferenceId()));
            bodyParams.add(java.util.Map.of("type", "text", "text", formattedAmount));
            bodyParams.add(java.util.Map.of("type", "text", "text", dueDate));
        } else if ("service_booking_deposit_v1".equals(key)) {
            // {{1}}=customer_name, {{2}}=service_name, {{3}}=booking_date, {{4}}=deposit_amount
            String serviceName = customParams != null && customParams.containsKey("service_name") ? customParams.get("service_name") : firstItem;
            String bookingDate = customParams != null && customParams.containsKey("booking_date") ? customParams.get("booking_date") : "Upcoming";
            bodyParams.add(java.util.Map.of("type", "text", "text", customerName));
            bodyParams.add(java.util.Map.of("type", "text", "text", serviceName));
            bodyParams.add(java.util.Map.of("type", "text", "text", bookingDate));
            bodyParams.add(java.util.Map.of("type", "text", "text", formattedAmount));
        } else if ("subscription_renewal_notice_v1".equals(key)) {
            // {{1}}=customer_name, {{2}}=plan_name, {{3}}=renewal_date, {{4}}=renewal_amount
            String planName = customParams != null && customParams.containsKey("plan_name") ? customParams.get("plan_name") : firstItem;
            String renewalDate = customParams != null && customParams.containsKey("renewal_date") ? customParams.get("renewal_date") : "End of cycle";
            bodyParams.add(java.util.Map.of("type", "text", "text", customerName));
            bodyParams.add(java.util.Map.of("type", "text", "text", planName));
            bodyParams.add(java.util.Map.of("type", "text", "text", renewalDate));
            bodyParams.add(java.util.Map.of("type", "text", "text", formattedAmount));
        } else if ("quotation_payment_approval_v1".equals(key)) {
            // {{1}}=customer_name, {{2}}=quote_number, {{3}}=service_name, {{4}}=amount, {{5}}=valid_until
            String quoteNum = customParams != null && customParams.containsKey("quotation_number") ? customParams.get("quotation_number") : order.getReferenceId();
            String serviceName = customParams != null && customParams.containsKey("service_name") ? customParams.get("service_name") : firstItem;
            String validUntil = customParams != null && customParams.containsKey("valid_until") ? customParams.get("valid_until") : "7 days";
            bodyParams.add(java.util.Map.of("type", "text", "text", customerName));
            bodyParams.add(java.util.Map.of("type", "text", "text", quoteNum));
            bodyParams.add(java.util.Map.of("type", "text", "text", serviceName));
            bodyParams.add(java.util.Map.of("type", "text", "text", formattedAmount));
            bodyParams.add(java.util.Map.of("type", "text", "text", validUntil));
        } else {
            // Check if customParams with numbered or named parameters were passed for custom/synced WABA template
            if (customParams != null && !customParams.isEmpty()) {
                // Filter out meta properties like language_code
                List<String> sortedKeys = new ArrayList<>(customParams.keySet());
                sortedKeys.remove("language_code");
                // Sort numerically if numbered keys ("1", "2", ...) or alphabetically
                sortedKeys.sort((a, b) -> {
                    try {
                        return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
                    } catch (Exception e) {
                        return a.compareTo(b);
                    }
                });

                for (String paramKey : sortedKeys) {
                    String val = customParams.get(paramKey);
                    if (val != null) {
                        bodyParams.add(java.util.Map.of("type", "text", "text", val));
                    }
                }
            }

            // If no custom params were parsed, provide standard default parameters
            if (bodyParams.isEmpty()) {
                bodyParams.add(java.util.Map.of("type", "text", "text", customerName));
                bodyParams.add(java.util.Map.of("type", "text", "text", order.getReferenceId()));
                bodyParams.add(java.util.Map.of("type", "text", "text", firstItem));
                bodyParams.add(java.util.Map.of("type", "text", "text", formattedAmount));
                bodyParams.add(java.util.Map.of("type", "text", "text", order.getCurrency() != null ? order.getCurrency() : "INR"));
            }
        }

        String languageCode = (customParams != null && customParams.containsKey("language_code") && !customParams.get("language_code").isBlank())
            ? customParams.get("language_code")
            : "en_US";

        List<java.util.Map<String, Object>> components = new ArrayList<>();
        components.add(java.util.Map.of(
            "type", "body",
            "parameters", bodyParams
        ));

        if (!checkoutUrl.isEmpty()) {
            components.add(java.util.Map.of(
                "type", "button",
                "sub_type", "url",
                "index", "0",
                "parameters", List.of(java.util.Map.of("type", "text", "text", checkoutUrl))
            ));
        }

        return java.util.Map.of(
            "messaging_product", "whatsapp",
            "recipient_type", "individual",
            "to", order.getCustomerWaId(),
            "type", "template",
            "template", java.util.Map.of(
                "name", key,
                "language", java.util.Map.of("code", languageCode),
                "components", components
            )
        );
    }
}
