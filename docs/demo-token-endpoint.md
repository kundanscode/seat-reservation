# Demo Token Endpoint — Implementation Guide

This guide adds a **development/demo-only endpoint** that lets the Paytm evaluator obtain short-lived JWTs for synthetic user accounts, without requiring us to build a complete login system.

> **Security boundary:** the endpoint must never issue admin tokens. It is enabled only when the `demo` Spring profile is active and an explicit configuration flag is true. Protect it with a separate high-entropy `DEMO_TOKEN_KEY`, keep that key out of Git, and rate-limit the endpoint at the hosting platform or an existing rate limiter. Do not use this mechanism as production authentication.

## 1. Behaviour and API contract

### Endpoint

`POST /auth/demo-token`

Headers:

```http
Content-Type: application/json
X-Demo-Key: <private-demo-access-key>
```

Request body:

```json
{
  "user_id": "loadtest-001"
}
```

The endpoint accepts only synthetic user IDs matching the `loadtest-` prefix and a restricted character set. It does **not** accept a role, scope, subject, issuer, or expiry from the caller.

Successful response (`200 OK`):

```json
{
  "access_token": "<signed-jwt>",
  "token_type": "Bearer",
  "expires_in": 900
}
```

The JWT should contain:

- `iss`: the configured `app.security.jwt-issuer` value (`seat-reservation` in the current local configuration).
- `sub`: the requested synthetic user ID.
- `scope`: `user` only.
- `iat` and `exp`: issued-at and short expiry (15 minutes by default).

Invalid/missing demo key: return `403 Forbidden` with the project's stable error JSON. Invalid user ID: return `400 Bad Request`. Never include the provided key in logs or error responses.

## 2. Configuration: keep secrets out of `application.yml`

The main configuration should resolve the signing secret from the environment rather than hardcoding it:

```yaml
app:
  security:
    jwt-issuer: ${JWT_ISSUER:seat-reservation}
    jwt-secret: ${JWT_SECRET}
```

Create `src/main/resources/application-demo.yml`:

```yaml
app:
  security:
    demo-token-enabled: ${DEMO_TOKEN_ENABLED:false}
    demo-token-key: ${DEMO_TOKEN_KEY:}
    demo-token-ttl: 15m
```

The endpoint is enabled only when **both** conditions hold:

1. `SPRING_PROFILES_ACTIVE=demo`
2. `DEMO_TOKEN_ENABLED=true`

The `demo-token-key` is a separate secret from `JWT_SECRET`. The evaluator must never receive `JWT_SECRET`.

### Local environment

Set a stable `JWT_SECRET` and a separate `DEMO_TOKEN_KEY` in your local shell or ignored local environment file. For example, when starting from a shell, you can create values once:

```bash
export JWT_SECRET="$(openssl rand -base64 32)"
export DEMO_TOKEN_KEY="$(openssl rand -base64 32)"
export DEMO_TOKEN_ENABLED=true
export SPRING_PROFILES_ACTIVE=demo
```

Keep these values unchanged while testing tokens issued by this running instance. Generating a new `JWT_SECRET` invalidates previously issued tokens. Do not commit these values or paste them into the README.

For deployment, configure the variables in the hosting platform's secret/environment settings. Do not rely on a local `.env.local` file existing in the deployed container.

## 3. Reuse the application's JWT key and algorithm

Do not write a second, separate JWT-signing implementation. The token issuer must use the same signing key, algorithm, and issuer configuration as the resource server's existing `JwtDecoder`.

If the application already has a `JwtEncoder` bean, reuse it. If it has a `SecretKey` bean used by the decoder but no encoder, add an encoder built from that **same** key. For an HS256 setup, the relevant pattern is:

```java
@Bean
JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
    return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
}
```

Imports for this pattern:

```java
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
```

Do not create a second `SecretKey` using a different encoding rule. If the decoder uses UTF-8 bytes from `JWT_SECRET`, the encoder must use the exact same key bytes. If the existing decoder Base64-decodes the secret, reuse its decoded `SecretKey` bean instead. Do not replace or weaken the existing decoder's issuer/expiry validation to make demo tokens work.

The project must already have Spring Security's OAuth2 JOSE/resource-server dependencies for its current JWT support. Do not add another JWT library just for this endpoint.

## 4. Request and response DTOs

Create `src/main/java/com/kundan/seat_reservation/security/demo/DemoTokenRequest.java`:

```java
package com.kundan.seat_reservation.security.demo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record DemoTokenRequest(
        @NotBlank(message = "user_id must not be blank")
        @Pattern(
                regexp = "^loadtest-[A-Za-z0-9_-]{1,48}$",
                message = "user_id must start with loadtest- and contain only letters, numbers, _ or -"
        )
        String user_id
) {
}
```

Create `DemoTokenResponse.java` in the same package:

```java
package com.kundan.seat_reservation.security.demo;

public record DemoTokenResponse(
        String access_token,
        String token_type,
        long expires_in
) {
}
```

These DTOs deliberately do not include a scope/role field. Use exactly the same field naming convention as the rest of the API if the project standard differs; keep the JSON contract documented above.

## 5. Demo token service

Create `DemoTokenService.java` in the same package. Reuse the configured `JwtEncoder` rather than manually assembling JWT strings.

```java
package com.kundan.seat_reservation.security.demo;

import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class DemoTokenService {

    private final JwtEncoder jwtEncoder;
    private final String issuer;
    private final Duration tokenTtl;

    public DemoTokenService(
            JwtEncoder jwtEncoder,
            @Value("${app.security.jwt-issuer:seat-reservation}") String issuer,
            @Value("${app.security.demo-token-ttl:15m}") Duration tokenTtl) {
        this.jwtEncoder = jwtEncoder;
        this.issuer = issuer;
        this.tokenTtl = tokenTtl;
    }

    public DemoTokenResponse issueUserToken(String userId) {
        Instant now = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(userId)
                .issuedAt(now)
                .expiresAt(now.plus(tokenTtl))
                .claim("scope", "user")
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(
                JwtEncoderParameters.from(header, claims)
        ).getTokenValue();

        return new DemoTokenResponse(token, "Bearer", tokenTtl.toSeconds());
    }
}
```

**Important:** this example assumes the current resource server uses HS256. If the existing decoder is configured for a different algorithm, make the header and encoder consistent with that configuration rather than blindly copying `HS256`.

## 6. Demo-key validation

Create a small component (for example, `DemoTokenKeyValidator`) that reads `app.security.demo-token-key` and verifies the `X-Demo-Key` header.

Use a constant-time comparison and reject a missing or incorrect key. For example:

```java
package com.kundan.seat_reservation.security.demo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DemoTokenKeyValidator {

    private final byte[] expectedKey;

    public DemoTokenKeyValidator(
            @Value("${app.security.demo-token-key:}") String configuredKey) {
        this.expectedKey = configuredKey.getBytes(StandardCharsets.UTF_8);
    }

    public boolean isValid(String suppliedKey) {
        if (suppliedKey == null || suppliedKey.isBlank() || expectedKey.length == 0) {
            return false;
        }

        return MessageDigest.isEqual(
                expectedKey,
                suppliedKey.getBytes(StandardCharsets.UTF_8)
        );
    }
}
```

Do not log `suppliedKey` or `expectedKey`. Configure a strong, random key; do not use a memorable phrase.

## 7. Demo-only controller

Create `DemoTokenController.java` in the same package:

```java
package com.kundan.seat_reservation.security.demo;

import jakarta.validation.Valid;

import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kundan.seat_reservation.common.ApiError;
import org.springframework.web.server.ResponseStatusException;

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
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Demo token access denied"
            );
        }

        return ResponseEntity.ok(
                demoTokenService.issueUserToken(request.user_id())
        );
    }
}
```

The example uses Spring's `ResponseStatusException` for clarity. To keep the assignment's stable error format, preferably add a specific `DemoTokenAccessDeniedException` and map it in the existing `ApiExceptionHandler` to `403` with a stable code such as `DEMO_TOKEN_FORBIDDEN`. Do not change the global exception handler to expose exception details or secrets.

The `DemoTokenService` may be registered only with the demo controller if desired. If it is a regular `@Service`, it can exist when the endpoint is disabled, but it must not expose a route. If you want the whole feature absent outside the demo profile, put the controller and its supporting beans under the same profile/condition.

## 8. Security configuration

The demo route needs to reach the controller without a bearer token because it authenticates through `X-Demo-Key`. Add this matcher **before** the catch-all authenticated matcher:

```java
.requestMatchers(HttpMethod.POST, "/auth/demo-token").permitAll()
```

This does not make token issuance unauthenticated: the controller still validates `X-Demo-Key`. Keep all existing matchers for `POST /shows` (admin scope) and reservation routes (user scope) intact. Never add an option that lets the caller choose `scope=admin`.

Because the controller is restricted by `@Profile("demo")` and `@ConditionalOnProperty`, the route should not exist in the ordinary/default profile. Do not activate the `demo` profile in production unless the evaluator's demo access is intentionally being enabled and protected by the separate demo key.

## 9. Token endpoint tests

Add tests using MockMvc and the project's existing Testcontainers/security test setup. At minimum, verify:

1. With the demo profile and feature flag enabled, a correct `X-Demo-Key` gets `200` and an `access_token`.
2. The returned token can be decoded by the application's configured `JwtDecoder`.
3. The token has the expected `iss`, a `sub` beginning with `loadtest-`, `scope=user`, and an expiry in the future (approximately 15 minutes).
4. Missing and incorrect demo keys are rejected.
5. `user_id` values outside the allowed pattern are rejected.
6. Extra caller fields such as `scope: "admin"` cannot produce an admin token. Ideally the API rejects unknown fields; at minimum, the issued JWT must always have `scope=user`.
7. With the demo profile/flag disabled, the endpoint is not available.

Avoid putting real signing keys or demo access keys into test source files. Supply test-only values through the test configuration.

## 10. Local verification with curl and Postman

Start the application with the demo profile and explicit flag enabled. Keep the same `JWT_SECRET` and `DEMO_TOKEN_KEY` while testing:

```bash
export JWT_SECRET="<your-stable-local-jwt-secret>"
export DEMO_TOKEN_KEY="<your-separate-demo-access-key>"
export DEMO_TOKEN_ENABLED=true
export SPRING_PROFILES_ACTIVE=demo
./mvnw spring-boot:run
```

Request a token (replace the placeholder with the actual demo key):

```bash
curl -i -X POST http://localhost:8080/auth/demo-token \
  -H "Content-Type: application/json" \
  -H "X-Demo-Key: <your-demo-access-key>" \
  -d '{"user_id":"loadtest-001"}'
```

Copy `access_token` from the response into Postman's **Authorization → Bearer Token** field. Use it for reservation and cancellation tests. Request a different synthetic identity such as `loadtest-002` when a distinct user is needed.

Try the same request with an incorrect `X-Demo-Key` and verify it is rejected. Also try a request body containing `"scope":"admin"`; the endpoint must never issue an admin-scoped token.

## 11. Admin token handling

This endpoint intentionally cannot issue admin tokens. Generate/provision the evaluator's admin JWT separately using the application's existing signing configuration and share it through a private channel if required for `POST /shows`. Never put an admin JWT, `JWT_SECRET`, or demo access key in the public README or repository. Arrange a way to provide a fresh admin token if the token is short-lived.

Do not create a full user/password database or login system for this assignment unless a later requirement justifies it.

## 12. README and deployment checklist

Document in `README.md`:

- `POST /auth/demo-token` is demo-only and issues only `user` scoped tokens.
- The `X-Demo-Key` header is required.
- The accepted `user_id` format is `loadtest-...`.
- Tokens expire after 15 minutes by default.
- Where the evaluator obtains the demo key and the admin token (share secrets privately, not in Git).
- The environment variables needed to enable the feature.

Before committing, verify:

- [ ] Endpoint is absent unless `demo` profile and feature flag are both enabled.
- [ ] Demo key is separate from `JWT_SECRET`.
- [ ] JWT encoder reuses the same key and algorithm as the decoder.
- [ ] Only synthetic user IDs are accepted.
- [ ] Endpoint always issues `scope=user` and never admin.
- [ ] Wrong/missing key is rejected and secrets are not logged.
- [ ] Rate limiting is configured at the platform or existing limiter.
- [ ] Tests pass: `./mvnw clean test`.
- [ ] No secrets were committed.

Suggested commit after implementation and tests:

```bash
git add src/main/java src/main/resources src/test/java README.md
git status
git commit -m "feat: add demo-only test token issuance"
```

Review `git status` before committing so only intended files are included. Do not commit local secret files.
