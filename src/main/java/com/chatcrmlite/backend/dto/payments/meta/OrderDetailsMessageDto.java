package com.chatcrmlite.backend.dto.payments.meta;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderDetailsMessageDto {

    @JsonProperty("messaging_product")
    @Builder.Default
    private String messagingProduct = "whatsapp";

    @JsonProperty("recipient_type")
    @Builder.Default
    private String recipientType = "individual";

    private String to;

    @Builder.Default
    private String type = "interactive";

    private Interactive interactive;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Interactive {
        @Builder.Default
        private String type = "order_details";
        private Header header;
        private Body body;
        private Footer footer;
        private Action action;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Header {
        @Builder.Default
        private String type = "text";
        private String text;
        private Media image;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Media {
        private String link;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Body {
        private String text;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Footer {
        private String text;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Action {
        @Builder.Default
        private String name = "review_and_pay";
        private ActionParameters parameters;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActionParameters {
        @JsonProperty("reference_id")
        private String referenceId;

        @Builder.Default
        private String type = "digital-goods";

        @JsonProperty("payment_type")
        @Builder.Default
        private String paymentType = "upi";

        @JsonProperty("payment_configuration")
        private String paymentConfiguration;

        @Builder.Default
        private String currency = "INR";

        @JsonProperty("total_amount")
        private Amount totalAmount;

        private Order order;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Amount {
        private Long value;
        @Builder.Default
        private Integer offset = 100;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Order {
        @Builder.Default
        private String status = "pending";
        private List<OrderItem> items;
        private Amount subtotal;
        private Amount tax;
        private Amount discount;
        private Amount shipping;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderItem {
        @JsonProperty("retailer_id")
        private String retailerId;
        private String name;
        private Amount amount;
        private Integer quantity;
        @JsonProperty("sale_amount")
        private Amount saleAmount;
    }
}
