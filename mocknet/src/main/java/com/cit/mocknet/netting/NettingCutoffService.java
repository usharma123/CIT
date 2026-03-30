package com.cit.mocknet.netting;

import com.cit.mocknet.config.MocknetProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.Map;

@Service
public class NettingCutoffService {

    private static final Logger log = LoggerFactory.getLogger(NettingCutoffService.class);

    private final Map<String, String> cutoffs;
    private final String defaultCutoff;

    public NettingCutoffService(MocknetProperties properties) {
        this.cutoffs = properties.getNettingCutoffs();
        this.defaultCutoff = cutoffs.getOrDefault("default", "16:00");
    }

    public LocalTime getCutoffTime(String currency) {
        String time = cutoffs.getOrDefault(currency, defaultCutoff);
        return LocalTime.parse(time);
    }

    public boolean isPastCutoff(String currency) {
        LocalTime cutoff = getCutoffTime(currency);
        boolean past = LocalTime.now().isAfter(cutoff);
        if (past) {
            log.debug("Currency {} is past netting cut-off ({})", currency, cutoff);
        }
        return past;
    }
}
