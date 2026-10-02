package com.kundan.seat_reservation.security.demo;

import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@Profile("demo")
@ConditionalOnProperty(
        prefix = "app.security",
        name = "demo-token-enabled",
        havingValue = "true"
)
public class DemoTokenController {

    private final DemoTokenService demoTokenService;
    private final DemoTokenKeyValidator keyValidator;

    public DemoTokenController(
            DemoTokenService demoTokenService,
            DemoTokenKeyValidator keyValidator) {
        this.demoTokenService = demoTokenService;
        this.keyValidator = keyValidator;
    }

    @PostMapping("/demo-token")
    public ResponseEntity<DemoTokenResponse> issueToken(
            @RequestHeader(value = "X-Demo-Key", required = false) String demoKey,
            @Valid @RequestBody DemoTokenRequest request) {

        if (!keyValidator.isValid(demoKey)) {
            throw new DemoTokenAccessDeniedException("Demo token access denied");
        }

        return ResponseEntity.ok(
                demoTokenService.issueUserToken(request.user_id())
        );
    }
}
