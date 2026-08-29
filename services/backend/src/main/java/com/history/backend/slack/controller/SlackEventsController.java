package com.history.backend.slack.controller;

import java.util.Map;

import com.history.backend.slack.service.SlackEventAck;
import com.history.backend.slack.service.SlackEventsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/slack")
public class SlackEventsController {

    private final SlackEventsService slackEventsService;

    @PostMapping("/events")
    public Map<String, Object> handleEvents(
            @RequestHeader(value = "X-Slack-Request-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Slack-Signature", required = false) String signature,
            @RequestBody String body
    ) {
        SlackEventAck ack = slackEventsService.handle(timestamp, signature, body);
        if (ack.challenge() != null) {
            return Map.of("challenge", ack.challenge());
        }
        return Map.of("ok", true);
    }
}
