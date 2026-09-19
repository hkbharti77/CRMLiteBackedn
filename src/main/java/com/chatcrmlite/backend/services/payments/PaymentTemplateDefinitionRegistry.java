package com.chatcrmlite.backend.services.payments;

import com.chatcrmlite.backend.dto.payments.AvailableTemplateDto;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class PaymentTemplateDefinitionRegistry {

    public static final String KEY_ORDER_INVOICE = "payment_order_invoice_v1";
    public static final String KEY_PENDING_REMINDER = "pending_bill_reminder_v1";
    public static final String KEY_SERVICE_DEPOSIT = "service_booking_deposit_v1";
    public static final String KEY_SUBSCRIPTION_RENEWAL = "subscription_renewal_notice_v1";
    public static final String KEY_QUOTATION_APPROVAL = "quotation_payment_approval_v1";

    private final Map<String, AvailableTemplateDto> definitions = new LinkedHashMap<>();

    public PaymentTemplateDefinitionRegistry() {
        registerDefinition(AvailableTemplateDto.builder()
            .definitionKey(KEY_ORDER_INVOICE)
            .version(1)
            .metaTemplateName("payment_order_invoice_v1")
            .language("en_US")
            .expectedCategory("UTILITY")
            .actualCategory("UTILITY")
            .status("APPROVED")
            .sendEnabled(true)
            .description("Official order bill and checkout link for finalized invoices")
            .requiredVariables(List.of("customer_name", "order_reference", "item_name", "amount", "currency", "payment_link"))
            .sampleBody("Hi {{1}}, your order #{{2}} for {{3}} of {{5}} {{4}} is ready for payment. Click below to pay securely via WhatsApp UPI / Gateway.")
            .build());

        registerDefinition(AvailableTemplateDto.builder()
            .definitionKey(KEY_PENDING_REMINDER)
            .version(1)
            .metaTemplateName("pending_bill_reminder_v1")
            .language("en_US")
            .expectedCategory("UTILITY")
            .actualCategory("UTILITY")
            .status("APPROVED")
            .sendEnabled(true)
            .description("Gentle payment reminder for outstanding or overdue bills")
            .requiredVariables(List.of("customer_name", "order_reference", "amount", "due_date", "payment_link"))
            .sampleBody("Hello {{1}}, friendly reminder: Your bill #{{2}} of ₹{{3}} is pending. Please complete your payment before {{4}} using the link below.")
            .build());

        registerDefinition(AvailableTemplateDto.builder()
            .definitionKey(KEY_SERVICE_DEPOSIT)
            .version(1)
            .metaTemplateName("service_booking_deposit_v1")
            .language("en_US")
            .expectedCategory("UTILITY")
            .actualCategory("UTILITY")
            .status("APPROVED")
            .sendEnabled(true)
            .description("Advance booking deposit request to confirm appointment slots")
            .requiredVariables(List.of("customer_name", "service_name", "booking_date", "deposit_amount", "payment_link"))
            .sampleBody("Hi {{1}}, thank you for booking {{2}} on {{3}}! Please pay the advance deposit of ₹{{4}} to confirm your appointment.")
            .build());

        registerDefinition(AvailableTemplateDto.builder()
            .definitionKey(KEY_SUBSCRIPTION_RENEWAL)
            .version(1)
            .metaTemplateName("subscription_renewal_notice_v1")
            .language("en_US")
            .expectedCategory("UTILITY")
            .actualCategory("UTILITY")
            .status("APPROVED")
            .sendEnabled(true)
            .description("Recurring subscription and membership renewal notice")
            .requiredVariables(List.of("customer_name", "plan_name", "renewal_date", "renewal_amount", "payment_link"))
            .sampleBody("Dear {{1}}, your membership for {{2}} is due for renewal on {{3}}. Renew now for ₹{{4}} to continue enjoying uninterrupted services.")
            .build());

        registerDefinition(AvailableTemplateDto.builder()
            .definitionKey(KEY_QUOTATION_APPROVAL)
            .version(1)
            .metaTemplateName("quotation_payment_approval_v1")
            .language("en_US")
            .expectedCategory("UTILITY")
            .actualCategory("UTILITY")
            .status("APPROVED")
            .sendEnabled(true)
            .description("Custom price quote, proposal estimate approval and deposit payment")
            .requiredVariables(List.of("customer_name", "quotation_number", "service_name", "quoted_amount", "valid_until", "payment_link"))
            .sampleBody("Hi {{1}}, here is your tailored estimate #{{2}} of ₹{{4}} for {{3}}. Review and make payment before {{5}} to get started.")
            .build());
    }

    private void registerDefinition(AvailableTemplateDto dto) {
        definitions.put(dto.getDefinitionKey(), dto);
    }

    public List<AvailableTemplateDto> getAllDefinitions() {
        return new ArrayList<>(definitions.values());
    }

    public Optional<AvailableTemplateDto> getDefinition(String key) {
        return Optional.ofNullable(definitions.get(key));
    }
}
