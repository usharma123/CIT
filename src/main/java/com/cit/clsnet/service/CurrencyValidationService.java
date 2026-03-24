package com.cit.clsnet.service;

import com.cit.clsnet.config.ClsNetProperties;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

@Service
public class CurrencyValidationService {

    private final Set<String> supportedCurrencies;

    public CurrencyValidationService(ClsNetProperties properties) {
        this.supportedCurrencies = new HashSet<>(properties.getCurrencies().getSupported());
    }

    public boolean isSupported(String currencyCode) {
        return currencyCode != null && supportedCurrencies.contains(currencyCode.toUpperCase());
    }

    public Set<String> getSupportedCurrencies() {
        return Set.copyOf(supportedCurrencies);
    }
}
