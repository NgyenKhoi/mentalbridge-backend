package com.mentalbridge.identity.account;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
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
            @RequestParam(required = false) @Email @Size(max = 254) String email,
            @RequestParam(required = false) @Size(max = 512) String cursor,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
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
        long expectedVersion = parseIfMatch(ifMatch);
        UUID actorId = jwt == null ? null : UUID.fromString(jwt.getSubject());

        AccountController.AccountResponse updated = service.changeAccountState(
                actorId, accountId, request, expectedVersion, correlationId);

        return ResponseEntity.ok()
                .eTag("\"" + updated.version() + "\"")
                .body(updated);
    }

    private long parseIfMatch(String ifMatch) {
        if (ifMatch == null || !ifMatch.matches("^\\\"[0-9]+\\\"$")) {
            throw new InvalidAdminAccountQueryException("If-Match must be a quoted numeric ETag");
        }
        try {
            return Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
        }
        catch (NumberFormatException exception) {
            throw new InvalidAdminAccountQueryException("If-Match version is outside the supported range");
        }
    }
}
