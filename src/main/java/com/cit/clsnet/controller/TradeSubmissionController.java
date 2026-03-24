package com.cit.clsnet.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.BlockingQueue;

@RestController
@RequestMapping("/api/trades")
public class TradeSubmissionController {

    private static final Logger log = LoggerFactory.getLogger(TradeSubmissionController.class);

    private final BlockingQueue<String> ingestionQueue;

    public TradeSubmissionController(@Qualifier("ingestionQueue") BlockingQueue<String> ingestionQueue) {
        this.ingestionQueue = ingestionQueue;
    }

    @PostMapping(consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    public ResponseEntity<Map<String, String>> submitTrade(@RequestBody String xmlPayload) {
        log.info("Received trade submission ({} bytes)", xmlPayload.length());

        try {
            ingestionQueue.put(xmlPayload);
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(Map.of("status", "accepted", "message", "Trade submitted for processing"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("status", "error", "message", "Service interrupted"));
        }
    }
}
