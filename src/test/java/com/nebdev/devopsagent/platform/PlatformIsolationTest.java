package com.nebdev.devopsagent.platform;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.file.Path;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Correctifs d’audit S1–S7 (2026-11-07) : un opérateur ne voit que les serveurs qui lui sont assignés,
 * les routes sans serveur sont refusées (fail-closed), les liens ne fuient pas, l’auth est limitée.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("platform")
class PlatformIsolationTest {

    @TempDir
    static Path stateDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("devops.state-dir", () -> stateDir.toString());
        r.add("devops.platform.founder-email", () -> "founder@test.local");
        r.add("devops.platform.default-password", () -> "Founder!Initial1");
        r.add("devops.platform.mail-dev-capture", () -> "false");
    }

    @Autowired MockMvc mvc;
    @Autowired AccountStore accounts;
    @Autowired GrantStore grants;
    @Autowired SessionStore sessions;

    private String operatorSession;

    @BeforeEach
    void operatorWithOneServer() throws Exception {
        var op = accounts.findByEmail("op@test.local").orElse(null);
        if (op == null) {
            op = accounts.create("op@test.local", "Op", RoleStore.OPERATOR, "Operator!Pass1", false);
            grants.assign(op.id(), "10.0.0.1", 22, "ubuntu", "A");
        }
        operatorSession = sessions.create(op.id(), java.time.Instant.now().plusSeconds(3600)).id();
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b) {
        return b.header("X-Platform-Session", operatorSession);
    }

    @Test
    void latestStateOfAllServersIsAdminOnly() throws Exception {
        mvc.perform(as(get("/api/v1/state"))).andExpect(status().isForbidden());
    }

    @Test
    void journalRequiresAnAssignedHostAndHidesOtherJournals() throws Exception {
        mvc.perform(as(get("/api/v1/journal"))).andExpect(status().isForbidden());
        mvc.perform(as(get("/api/v1/journal").param("host", "10.0.0.2"))).andExpect(status().isForbidden());
        mvc.perform(as(get("/api/v1/journal").param("host", "10.0.0.1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.journals").value(empty()));
    }

    @Test
    void lastSnapshotOfAnotherServerIsRefused() throws Exception {
        mvc.perform(as(get("/api/v1/servers/last").param("host", "10.0.0.2"))).andExpect(status().isForbidden());
    }

    @Test
    void globalIntentsAreAdminOnly() throws Exception {
        mvc.perform(as(put("/api/v1/platforms").contentType(MediaType.APPLICATION_JSON).content("{}")))
                .andExpect(status().isForbidden());
        mvc.perform(as(put("/api/v1/apps").contentType(MediaType.APPLICATION_JSON).content("{}")))
                .andExpect(status().isForbidden());
        mvc.perform(as(put("/api/v1/stack").contentType(MediaType.APPLICATION_JSON).content("{}")))
                .andExpect(status().isForbidden());
    }

    @Test
    void changeOnAnotherServerIsRefused() throws Exception {
        mvc.perform(as(post("/api/v1/install").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"component\":\"docker\",\"host\":\"10.0.0.2\",\"user\":\"ubuntu\",\"confirm\":true}")))
                .andExpect(status().isForbidden());
        mvc.perform(as(post("/api/v1/install").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"component\":\"docker\",\"confirm\":true}")))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownJobIsNotReadableByOperator() throws Exception {
        mvc.perform(as(get("/api/v1/platforms/jobs/does-not-exist"))).andExpect(status().isForbidden());
    }

    @Test
    void resetLinkIsNeverReturnedInResponse() throws Exception {
        mvc.perform(post("/api/v1/platform/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Forwarded-For", "198.51.100.7")
                        .content("{\"email\":\"founder@test.local\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.devLink").doesNotExist())
                .andExpect(jsonPath("$.devToken").doesNotExist());
    }

    @Test
    void factoryResetIsDisabledInProduction() throws Exception {
        mvc.perform(post("/api/v1/platform/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Forwarded-For", "198.51.100.8")
                        .content("{\"token\":\"x\",\"useDefault\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void loginIsRateLimited() throws Exception {
        for (int i = 0; i < AuthRateLimiter.MAX_PER_KEY; i++) {
            mvc.perform(post("/api/v1/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .header("X-Forwarded-For", "203.0.113.50")
                            .content("{\"email\":\"founder@test.local\",\"password\":\"wrong-password\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/v1/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Forwarded-For", "203.0.113.50")
                        .content("{\"email\":\"founder@test.local\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error", not(empty())));
    }
}
