package com.cit.retro;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Submits XML trade payloads to mocknet via HTTP POST.
 */
public class TradeSubmitter {

    private final String baseUrl;
    private final HttpClient client;
    private final ObjectMapper objectMapper;

    public TradeSubmitter(String baseUrl) {
        this.baseUrl = baseUrl;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * POST XML payload to /api/trades.
     * Returns [statusCode, responseBody]. statusCode is -1 on connection error.
     */
    public String[] submit(String xmlPayload) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/trades"))
                    .header("Content-Type", "application/xml")
                    .POST(HttpRequest.BodyPublishers.ofString(xmlPayload))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new String[]{String.valueOf(response.statusCode()), response.body()};
        } catch (IOException e) {
            return new String[]{"-1", "Connection refused - is mocknet running?"};
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new String[]{"-1", "Request interrupted"};
        }
    }

    public String fetchTradeStatus(String tradeId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/trades"))
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }

            List<Map<String, Object>> trades = objectMapper.readValue(
                    response.body(),
                    new TypeReference<List<Map<String, Object>>>() {}
            );
            for (Map<String, Object> trade : trades) {
                if (tradeId.equals(trade.get("tradeId"))) {
                    Object status = trade.get("status");
                    return status == null ? null : status.toString();
                }
            }
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return null;
    }
}
