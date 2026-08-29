package com.history.backend.slack.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.history.backend.slack.service.SlackCommandAck;
import com.history.backend.slack.service.SlackCommandsService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/slack")
public class SlackCommandsController {

    private final SlackCommandsService slackCommandsService;

    @PostMapping("/commands")
    public SlackCommandAck handleCommands(
            @RequestHeader(value = "X-Slack-Request-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Slack-Signature", required = false) String signature,
            HttpServletRequest request
    ) throws IOException {
        // @RequestBody String 은 form-urlencoded에서 값을 재인코딩해 Slack 서명 원문과 어긋난다
        String body = StreamUtils.copyToString(request.getInputStream(), StandardCharsets.UTF_8);
        return slackCommandsService.handle(timestamp, signature, body);
    }
}
