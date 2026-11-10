package com.agentbench.identity.api;

import com.agentbench.identity.api.dto.AuthDtos;
import com.agentbench.identity.security.OrgAccessService;
import com.agentbench.identity.security.UserPrincipal;
import com.agentbench.identity.service.AuthService;
import com.agentbench.identity.service.OidcAuthService;
import com.agentbench.platform.api.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping(ApiPaths.API_V1)
@Tag(name = "Auth", description = "Registration, login, OIDC, JWT refresh/logout")
public class AuthController {

    private final AuthService authService;
    private final OidcAuthService oidcAuthService;
    private final OrgAccessService orgAccessService;

    public AuthController(
            AuthService authService,
            OidcAuthService oidcAuthService,
            OrgAccessService orgAccessService) {
        this.authService = authService;
        this.oidcAuthService = oidcAuthService;
        this.orgAccessService = orgAccessService;
    }

    @GetMapping(value = "/auth/providers", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List enabled auth providers (local always; OIDC when configured)")
    public AuthDtos.ProvidersResponse providers() {
        return authService.listProviders();
    }

    @PostMapping(value = "/auth/register", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register with email/password")
    public AuthDtos.TokenResponse register(@Valid @RequestBody AuthDtos.RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping(value = "/auth/login", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Login and issue access + refresh tokens")
    public AuthDtos.TokenResponse login(@Valid @RequestBody AuthDtos.LoginRequest request) {
        return authService.login(request);
    }

    @GetMapping("/auth/oidc/{provider}/authorize")
    @Operation(summary = "Start OIDC/OAuth authorization-code flow (browser redirect)")
    public ResponseEntity<Void> oidcAuthorize(@PathVariable("provider") String provider) {
        URI location = oidcAuthService.beginAuthorization(provider);
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location.toString()).build();
    }

    @GetMapping("/auth/oidc/{provider}/callback")
    @Operation(summary = "OIDC/OAuth callback — redirects to frontend with tokens in URL fragment")
    public ResponseEntity<Void> oidcCallback(
            @PathVariable("provider") String provider,
            @RequestParam("code") String code,
            @RequestParam("state") String state) {
        URI location = oidcAuthService.completeBrowserCallback(provider, code, state);
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location.toString()).build();
    }

    @PostMapping(
            value = "/auth/oidc/{provider}/token",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Exchange OIDC authorization code + state for AgentBench JWTs (API / tests)")
    public AuthDtos.TokenResponse oidcToken(
            @PathVariable("provider") String provider,
            @Valid @RequestBody AuthDtos.OidcTokenRequest request) {
        return oidcAuthService.completeWithCode(provider, request.code(), request.state());
    }

    @PostMapping(value = "/auth/refresh", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Rotate refresh token and issue a new access token")
    public AuthDtos.TokenResponse refresh(@Valid @RequestBody AuthDtos.RefreshRequest request) {
        return authService.refresh(request);
    }

    @PostMapping(value = "/auth/logout", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Revoke refresh token (or all sessions for the user)")
    public void logout(@RequestBody(required = false) AuthDtos.LogoutRequest request) {
        UserPrincipal principal = null;
        try {
            principal = orgAccessService.requirePrincipal();
        } catch (Exception ignored) {
            // logout with refresh token alone is allowed
        }
        authService.logout(principal, request == null ? new AuthDtos.LogoutRequest(null) : request);
    }

    @GetMapping(value = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Current user and org memberships")
    public AuthDtos.MeResponse me() {
        return authService.me(orgAccessService.requirePrincipal());
    }
}
