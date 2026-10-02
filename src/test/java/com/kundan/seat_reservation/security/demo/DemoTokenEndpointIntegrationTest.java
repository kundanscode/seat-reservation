package com.kundan.seat_reservation.security.demo;

import com.jayway.jsonpath.JsonPath;
import com.kundan.seat_reservation.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@TestPropertySource(properties = {
        "app.security.demo-token-enabled=true",
        "app.security.demo-token-key=test-high-entropy-demo-key-12345"
})
class DemoTokenEndpointIntegrationTest {

    private static final String DEMO_KEY = "test-high-entropy-demo-key-12345";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("1. Correct demo key returns 200 and access_token")
    void testIssueDemoTokenSuccess() throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/demo-token")
                .header("X-Demo-Key", DEMO_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "user_id": "loadtest-001"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.expires_in").value(900))
                .andReturn();

        String token = JsonPath.read(result.getResponse().getContentAsString(), "$.access_token");
        assertThat(token).isNotBlank();
    }

    @Test
    @DisplayName("2 & 3. Returned token can be decoded by JwtDecoder and contains valid claims")
    void testDecodedTokenClaims() throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/demo-token")
                .header("X-Demo-Key", DEMO_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "user_id": "loadtest-worker-99"
                        }
                        """))
                .andExpect(status().isOk())
                .andReturn();

        String token = JsonPath.read(result.getResponse().getContentAsString(), "$.access_token");
        Jwt jwt = jwtDecoder.decode(token);

        assertThat(jwt.getClaimAsString("iss")).isEqualTo("seat-reservation");
        assertThat(jwt.getSubject()).isEqualTo("loadtest-worker-99");
        assertThat(jwt.getClaimAsString("scope")).isEqualTo("user");
        assertThat(jwt.getIssuedAt()).isBeforeOrEqualTo(Instant.now());
        assertThat(jwt.getExpiresAt()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("4. Missing or incorrect demo key returns 403 Forbidden with stable error JSON")
    void testRejectInvalidOrMissingDemoKey() throws Exception {
        // Missing key
        mockMvc.perform(post("/auth/demo-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "user_id": "loadtest-001"
                        }
                        """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DEMO_TOKEN_FORBIDDEN"));

        // Incorrect key
        mockMvc.perform(post("/auth/demo-token")
                .header("X-Demo-Key", "wrong-secret-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "user_id": "loadtest-001"
                        }
                        """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DEMO_TOKEN_FORBIDDEN"));
    }

    @Test
    @DisplayName("5. user_id outside allowed pattern returns 400 Bad Request")
    void testRejectInvalidUserIdPattern() throws Exception {
        // Does not start with loadtest-
        mockMvc.perform(post("/auth/demo-token")
                .header("X-Demo-Key", DEMO_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "user_id": "admin-001"
                        }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        // Blank user_id
        mockMvc.perform(post("/auth/demo-token")
                .header("X-Demo-Key", DEMO_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "user_id": "   "
                        }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        // Invalid characters (space, special characters)
        mockMvc.perform(post("/auth/demo-token")
                .header("X-Demo-Key", DEMO_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "user_id": "loadtest-user with space!"
                        }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("6. Extra fields such as scope: admin cannot produce an admin token; scope is always user")
    void testExtraFieldsCannotElevatePrivileges() throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/demo-token")
                .header("X-Demo-Key", DEMO_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "user_id": "loadtest-spoof",
                          "scope": "admin"
                        }
                        """))
                .andExpect(status().isOk())
                .andReturn();

        String token = JsonPath.read(result.getResponse().getContentAsString(), "$.access_token");
        Jwt jwt = jwtDecoder.decode(token);

        assertThat(jwt.getSubject()).isEqualTo("loadtest-spoof");
        assertThat(jwt.getClaimAsString("scope")).isEqualTo("user");
    }
}
