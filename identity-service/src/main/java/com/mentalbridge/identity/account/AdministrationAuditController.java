package com.mentalbridge.identity.account;

import com.mentalbridge.identity.account.AdministrationAuditService.AuditActorType;
import com.mentalbridge.identity.account.AdministrationAuditService.AuditDomain;
import com.mentalbridge.identity.account.AdministrationAuditService.AuditQuery;
import com.mentalbridge.identity.account.AdministrationAuditService.AuditResult;
import com.mentalbridge.identity.account.AdministrationAuditService.AuditSourceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/audit-events")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class AdministrationAuditController {

    private final AdministrationAuditService service;

    public AdministrationAuditController(AdministrationAuditService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<AdministrationAuditService.AuditEventPage> browse(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) AuditSourceService sourceService,
            @RequestParam(required = false) AuditDomain domain,
            @RequestParam(required = false) AuditActorType actorType,
            @RequestParam(required = false) @Size(min = 1, max = 96) @Pattern(regexp = "^[A-Z0-9_]+$") String action,
            @RequestParam(required = false) AuditResult result,
            @RequestParam(required = false) @Size(min = 9, max = 74) String targetIdentifier,
            @RequestParam(required = false) @Size(min = 1, max = 512) String cursor,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit,
            @RequestHeader(name = "X-Correlation-Id", required = false) UUID correlationId,
            HttpServletRequest request) {
        UUID effectiveCorrelationId = correlation(request, correlationId);
        var page = service.browse(query(from, to, sourceService, domain, actorType, action, result, targetIdentifier),
                cursor, limit);
        return ResponseEntity.ok().header("X-Correlation-Id", effectiveCorrelationId.toString()).body(page);
    }

    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) AuditSourceService sourceService,
            @RequestParam(required = false) AuditDomain domain,
            @RequestParam(required = false) AuditActorType actorType,
            @RequestParam(required = false) @Size(min = 1, max = 96) @Pattern(regexp = "^[A-Z0-9_]+$") String action,
            @RequestParam(required = false) AuditResult result,
            @RequestParam(required = false) @Size(min = 9, max = 74) String targetIdentifier,
            @RequestHeader(name = "X-Correlation-Id", required = false) UUID correlationId,
            HttpServletRequest request) {
        UUID effectiveCorrelationId = correlation(request, correlationId);
        var export = service.export(query(from, to, sourceService, domain, actorType, action, result, targetIdentifier));
        String timestamp = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss").withZone(ZoneOffset.UTC)
                .format(export.effectiveTo());
        return ResponseEntity.ok()
                .header("X-Correlation-Id", effectiveCorrelationId.toString())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("mentalbridge-audit-" + timestamp + ".csv").build().toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(export.bytes());
    }

    private AuditQuery query(Instant from, Instant to, AuditSourceService sourceService, AuditDomain domain,
            AuditActorType actorType, String action, AuditResult result, String targetIdentifier) {
        return new AuditQuery(from, to, sourceService, domain, actorType, action, result, targetIdentifier);
    }

    private UUID correlation(HttpServletRequest request, UUID supplied) {
        UUID effective = supplied == null ? UUID.randomUUID() : supplied;
        request.setAttribute("correlationId", effective);
        return effective;
    }
}
