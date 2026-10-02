package com.kundan.seat_reservation.security.demo;

import com.kundan.seat_reservation.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class DemoTokenEndpointDisabledIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("7. With demo profile/flag disabled, the endpoint is not available (404 Not Found)")
    void testDemoTokenEndpointNotAvailableWhenDisabled() throws Exception {
        mockMvc.perform(post("/auth/demo-token")
                .header("X-Demo-Key", "any-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "user_id": "loadtest-001"
                        }
                        """))
                .andExpect(status().isNotFound());
    }
}
