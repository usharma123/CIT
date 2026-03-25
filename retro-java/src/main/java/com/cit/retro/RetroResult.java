package com.cit.retro;

/**
 * Holds the result of processing a single trade row.
 */
public class RetroResult {
    private final String participant;
    private final String response;
    private final String result;

    public RetroResult(String participant, String response, String result) {
        this.participant = participant;
        this.response = response;
        this.result = result;
    }

    public String getParticipant() { return participant; }
    public String getResponse() { return response; }
    public String getResult() { return result; }

    public boolean isPassed() { return "PASS".equals(result); }
    public boolean isFailed() { return "FAIL".equals(result); }
    public boolean isSkipped() { return "SKIP".equals(result); }
}
