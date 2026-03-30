package com.cit.mocknet.ingestion;

import com.cit.mocknet.config.MocknetProperties;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

@Service
public class CurrencyValidationService {

    private final Set<String> supportedCurrencies;

    public CurrencyValidationService(MocknetProperties properties) {
        this.supportedCurrencies = new HashSet<>(properties.getCurrencies().getSupported());
    }

    public boolean isSupported(String currencyCode) {
        return currencyCode != null && supportedCurrencies.contains(currencyCode.toUpperCase());
    }

    public Set<String> getSupportedCurrencies() {
        return Set.copyOf(supportedCurrencies);
    }
}
