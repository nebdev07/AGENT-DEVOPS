package com.nebdev.devopsagent.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("cargo")
class CargoModeFilterTest {

    @Autowired
    MockMvc mvc;

    @Test
    void healthIsPublicAndMarkedCargo() throws Exception {
        mvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("cargo"));
    }

    @Test
    void changeApisAreForbidden() throws Exception {
        mvc.perform(post("/api/v1/discover"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/install"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/runtime"))
                .andExpect(status().isForbidden());
    }
}
