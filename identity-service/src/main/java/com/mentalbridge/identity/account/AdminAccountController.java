package com.mentalbridge.identity.account;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/accounts")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class AdminAccountController {

    private final AdminAccountService service;

    public AdminAccountController(AdminAccountService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<AdminAccountService.AccountPage> searchAccounts(
            @RequestParam(required = false) AccountStatus status,
            @RequestParam(required = false) RoleCode role,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        AdminAccountService.AccountPage page = service.searchAccounts(status, role, email, cursor, limit);
        return ResponseEntity.ok(page);
    }

    @GetMapping("/{accountId}")
    public ResponseEntity<AccountController.AccountResponse> getAccountDetail(
            @PathVariable UUID accountId) {
        AccountController.AccountResponse detail = service.getAccountDetail(accountId);
        return ResponseEntity.ok()
                .eTag("\"" + detail.version() + "\"")
                .body(detail);
    }

    @PutMapping("/{accountId}/state")
    public ResponseEntity<AccountController.AccountResponse> changeAccountState(
            @PathVariable UUID accountId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("X-Correlation-Id") UUID correlationId,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AccountStateChangeRequest request) {
        String cleanVersion = ifMatch.replace("\"", "").trim();
        long expectedVersion = Long.parseLong(cleanVersion);

        UUID actorId = null;
        if (jwt != null && jwt.getSubject() != null) {
            try {
                actorId = UUID.fromString(jwt.getSubject());
            } catch (IllegalArgumentException ignored) {
            }
        }

        AccountController.AccountResponse updated = service.changeAccountState(
                actorId, accountId, request, expectedVersion, correlationId);

        return ResponseEntity.ok()
                .eTag("\"" + updated.version() + "\"")
                .body(updated);
    }
}
