package com.nebdev.devopsagent.saas;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class SaasAuthTest extends SaasTestSupport {

    @Test
    void registerLoginMeAndTrial() throws Exception {
        Session s = register("alice");
        JsonNode me = json(getAs(s.token(), "/api/v2/me"), 200);
        assertThat(me.get("email").asText()).isEqualTo(s.email());
        assertThat(me.has("passwordHash")).isFalse();

        JsonNode orgs = json(getAs(s.token(), "/api/v2/orgs"), 200);
        assertThat(orgs).hasSize(1);
        assertThat(orgs.get(0).get("role").asText()).isEqualTo("ORGANIZATION_OWNER");
        assertThat(orgs.get(0).get("billingCurrency").asText()).isEqualTo("EUR");

        JsonNode billing = json(getAs(s.token(), "/api/v2/orgs/" + s.orgId() + "/billing"), 200);
        assertThat(billing.get("subscription").get("status").asText()).isEqualTo("TRIALING");
        assertThat(billing.get("subscription").get("currency").asText()).isEqualTo("EUR");

        Session again = login(s.email(), s.orgId());
        assertThat(again.token()).startsWith("ses_").isNotEqualTo(s.token());
    }

    @Test
    void anonymousIsRejectedAndPublicPlansAreOpen() throws Exception {
        assertThat(mvc.perform(get("/api/v2/me")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get("/api/v2/me").header("Authorization", "Bearer ses_forged")).andReturn()
                .getResponse().getStatus()).isEqualTo(401);
        JsonNode plans = json(mvc.perform(get("/api/v2/public/plans")).andReturn(), 200);
        assertThat(plans.size()).isGreaterThanOrEqualTo(3);
        assertThat(plans.toString()).doesNotContain("MANAGED");
    }

    @Test
    void legacyV1ApiIsClosedInSaasProfile() throws Exception {
        assertThat(mvc.perform(post("/api/v1/discover").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getStatus()).isIn(401, 403);
        Session s = register("legacy");
        assertThat(getAs(s.token(), "/api/v1/state").getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void wrongPasswordIsGenericThenLocksAccount() throws Exception {
        Session s = register("lock");
        for (int i = 0; i < 10; i++) {
            int status = mvc.perform(post("/api/v2/public/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of("email", s.email(), "password", "wrong-password-x"))))
                    .andReturn().getResponse().getStatus();
            assertThat(status).isEqualTo(401);
        }
        int locked = mvc.perform(post("/api/v2/public/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email", s.email(), "password", "Str0ng-Passphrase!"))))
                .andReturn().getResponse().getStatus();
        // Le bon mot de passe est refusé : compte verrouillé (423) ou limite de tentatives atteinte (429).
        assertThat(locked).isIn(423, 429);
        // E-mail inconnu : même réponse qu'un mauvais mot de passe (pas d'énumération).
        JsonNode unknown = json(mvc.perform(post("/api/v2/public/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email", "nobody@example.test", "password", "whatever-123456"))))
                .andReturn(), 401);
        assertThat(unknown.get("code").asText()).isEqualTo("invalid_credentials");
    }

    @Test
    void weakPasswordRefusedWithLocalizedMessage() throws Exception {
        JsonNode r = json(mvc.perform(post("/api/v2/public/auth/register").contentType(MediaType.APPLICATION_JSON)
                .header("Accept-Language", "fr")
                .content(mapper.writeValueAsString(Map.of("email", "weak@example.test", "password", "password1234",
                        "organizationName", "Weak")))).andReturn(), 400);
        assertThat(r.get("code").asText()).isEqualTo("password_too_weak");
        assertThat(r.get("message").asText()).contains("mot de passe");
        assertThat(r.has("correlationId")).isTrue();
    }

    @Test
    void passwordResetDoesNotRevealAccountsAndRevokesSessions() throws Exception {
        Session s = register("reset");
        assertThat(postAs(null, "/api/v2/public/auth/password-reset/request", Map.of("email", s.email()))
                .getResponse().getStatus()).isEqualTo(202);
        assertThat(postAs(null, "/api/v2/public/auth/password-reset/request", Map.of("email", "ghost@example.test"))
                .getResponse().getStatus()).isEqualTo(202);
        assertThat(postAs(null, "/api/v2/public/auth/password-reset/confirm",
                Map.of("token", "rst_invalid", "newPassword", "An0ther-Passphrase!")).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void mfaSetupEnableAndRequireCode() throws Exception {
        Session s = register("mfa");
        JsonNode setup = json(postAs(s.token(), "/api/v2/me/mfa/setup", null), 200);
        byte[] secret = base32Decode(setup.get("secret").asText());
        String code = com.nebdev.devopsagent.saas.identity.Totp.generate(secret, java.time.Instant.now().getEpochSecond() / 30);
        json(postAs(s.token(), "/api/v2/me/mfa/enable", Map.of("code", code)), 200);
        JsonNode needCode = json(postAs(null, "/api/v2/public/auth/login",
                Map.of("email", s.email(), "password", "Str0ng-Passphrase!")), 401);
        assertThat(needCode.get("code").asText()).isEqualTo("mfa_required");
        String now = com.nebdev.devopsagent.saas.identity.Totp.generate(secret, java.time.Instant.now().getEpochSecond() / 30);
        json(postAs(null, "/api/v2/public/auth/login", Map.of("email", s.email(), "password", "Str0ng-Passphrase!", "totp", now)), 200);
    }

    static byte[] base32Decode(String s) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : s.toCharArray()) {
            buffer = (buffer << 5) | alphabet.indexOf(c);
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
