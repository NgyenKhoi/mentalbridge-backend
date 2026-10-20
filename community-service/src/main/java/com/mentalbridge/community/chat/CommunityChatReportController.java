package com.mentalbridge.community.chat;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
class CommunityChatReportController {
    private final CommunityChatReportService reports;
    CommunityChatReportController(CommunityChatReportService reports) { this.reports = reports; }
    @PostMapping("/api/v1/community/chat/reports")
    ResponseEntity<ChatReportModels.Result> report(@AuthenticationPrincipal Jwt jwt, @RequestBody ChatReportModels.Input input,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestHeader(value = "X-Correlation-Id", required = false) String correlation) {
        return ResponseEntity.status(201).body(reports.report(CommunityChatController.subject(jwt), ChatModels.uuid(key), input, CommunityChatController.correlation(correlation)));
    }
}
