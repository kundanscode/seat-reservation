package com.kundan.seat_reservation.common;

import com.kundan.seat_reservation.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CorrelationIdFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Echoes client-provided X-Request-ID in response header")
    void testEchoesClientRequestId() throws Exception {
        String clientReqId = "req-custom-12345";
        mockMvc.perform(get("/actuator/health")
                        .header("X-Request-ID", clientReqId))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-ID", clientReqId));
    }

    @Test
    @DisplayName("Generates UUID X-Request-ID when no header is supplied")
    void testGeneratesRequestIdWhenMissing() throws Exception {
        var result = mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-ID"))
                .andReturn();

        String generatedId = result.getResponse().getHeader("X-Request-ID");
        assertThat(generatedId).isNotBlank();
        assertThat(generatedId).matches("^[0-9a-fA-F-]{36}$");
    }

    @Test
    @DisplayName("Accepts X-Correlation-ID header as fallback for request ID")
    void testFallbackToCorrelationIdHeader() throws Exception {
        String correlationId = "corr-xyz-789";
        mockMvc.perform(get("/actuator/health")
                        .header("X-Correlation-ID", correlationId))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-ID", correlationId));
    }
}
