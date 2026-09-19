package com.chatcrmlite.backend.services.payments;

import com.chatcrmlite.backend.models.enums.PaymentIntegrationType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class PaymentProviderFactory {

    private final Map<PaymentIntegrationType, PaymentProvider> providers = new EnumMap<>(PaymentIntegrationType.class);

    public PaymentProviderFactory(List<PaymentProvider> providerList) {
        for (PaymentProvider provider : providerList) {
            providers.put(provider.getProviderType(), provider);
        }
    }

    public PaymentProvider getProvider(PaymentIntegrationType type) {
        PaymentProvider provider = providers.get(type);
        if (provider == null) {
            throw new IllegalArgumentException("Unsupported payment provider: " + type);
        }
        return provider;
    }
}
