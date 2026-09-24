package com.chatcrmlite.backend.services.sms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class SmsProviderResolver {

    private final Map<String, SmsSenderProvider> providers = new HashMap<>();

    @Autowired
    public SmsProviderResolver(List<SmsSenderProvider> providerList) {
        for (SmsSenderProvider p : providerList) {
            providers.put(p.getProviderType().toUpperCase(), p);
            log.info("Registered SMS Sender Provider: {}", p.getProviderType());
        }
    }

    public Optional<SmsSenderProvider> resolve(String providerType) {
        if (providerType == null) return Optional.empty();
        return Optional.ofNullable(providers.get(providerType.toUpperCase()));
    }
}
