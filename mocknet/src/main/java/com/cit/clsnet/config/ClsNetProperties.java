package com.cit.clsnet.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "clsnet")
public class ClsNetProperties {

    private ThreadConfig threads = new ThreadConfig();
    private CurrencyConfig currencies = new CurrencyConfig();
    private Map<String, String> nettingCutoffs = new HashMap<>();
    private BrokerConfig broker = new BrokerConfig();

    public ThreadConfig getThreads() { return threads; }
    public void setThreads(ThreadConfig threads) { this.threads = threads; }

    public CurrencyConfig getCurrencies() { return currencies; }
    public void setCurrencies(CurrencyConfig currencies) { this.currencies = currencies; }

    public Map<String, String> getNettingCutoffs() { return nettingCutoffs; }
    public void setNettingCutoffs(Map<String, String> nettingCutoffs) { this.nettingCutoffs = nettingCutoffs; }

    public BrokerConfig getBroker() { return broker; }
    public void setBroker(BrokerConfig broker) { this.broker = broker; }

    public static class ThreadConfig {
        private int ingestion = 1;
        private int matching = 1;
        private int netting = 1;
        private int settlement = 1;

        public int getIngestion() { return ingestion; }
        public void setIngestion(int ingestion) { this.ingestion = ingestion; }

        public int getMatching() { return matching; }
        public void setMatching(int matching) { this.matching = matching; }

        public int getNetting() { return netting; }
        public void setNetting(int netting) { this.netting = netting; }

        public int getSettlement() { return settlement; }
        public void setSettlement(int settlement) { this.settlement = settlement; }
    }

    public static class CurrencyConfig {
        private List<String> supported = List.of(
                "USD", "EUR", "GBP", "JPY", "CHF", "CAD", "AUD", "NZD",
                "SEK", "NOK", "DKK", "SGD", "HKD", "KRW", "ZAR", "MXN",
                "BRL", "INR", "CNY", "THB");

        public List<String> getSupported() { return supported; }
        public void setSupported(List<String> supported) { this.supported = supported; }
    }

    public static class BrokerConfig {
        private long pollIntervalMillis = 100;
        private long claimTimeoutSeconds = 30;
        private int maxAttempts = 3;
        private long retryDelayMillis = 500;

        public long getPollIntervalMillis() { return pollIntervalMillis; }
        public void setPollIntervalMillis(long pollIntervalMillis) { this.pollIntervalMillis = pollIntervalMillis; }

        public long getClaimTimeoutSeconds() { return claimTimeoutSeconds; }
        public void setClaimTimeoutSeconds(long claimTimeoutSeconds) { this.claimTimeoutSeconds = claimTimeoutSeconds; }

        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }

        public long getRetryDelayMillis() { return retryDelayMillis; }
        public void setRetryDelayMillis(long retryDelayMillis) { this.retryDelayMillis = retryDelayMillis; }
    }
}
