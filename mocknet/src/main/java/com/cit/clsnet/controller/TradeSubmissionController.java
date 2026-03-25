package com.cit.clsnet.controller;

import com.cit.clsnet.model.QueueName;
import com.cit.clsnet.service.QueueBroker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/trades")
public class TradeSubmissionController {

    private static final Logger log = LoggerFactory.getLogger(TradeSubmissionController.class);

    private final QueueBroker queueBroker;

    public TradeSubmissionController(QueueBroker queueBroker) {
        this.queueBroker = queueBroker;
    }

    @PostMapping(consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    public ResponseEntity<Map<String, String>> submitTrade(@RequestBody String xmlPayload) {
        log.info("Received trade submission ({} bytes)", xmlPayload.length());

        try {
            queueBroker.publish(QueueName.INGESTION, xmlPayload);
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(Map.of("status", "accepted", "message", "Trade submitted for processing"));
        } catch (Exception e) {
            log.error("Failed to enqueue trade submission", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("status", "error", "message", "Unable to enqueue trade"));
        }
    }
}
