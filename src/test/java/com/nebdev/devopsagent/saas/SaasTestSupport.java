package com.nebdev.devopsagent.saas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebdev.devopsagent.saas.operation.EnginePort;
import com.nebdev.devopsagent.saas.operation.ExecutionWorker;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Socle des tests SaaS : vrai PostgreSQL 16 embarqué (binaires, sans Docker), migrations Flyway jouées par le
 * superutilisateur, application connectée avec le rôle non privilégié {@code autopilot_app} — la RLS s'applique
 * donc réellement. Le moteur SSH est remplacé par {@link FakeEngine}. Le worker est déclenché à la main.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("saas")
public abstract class SaasTestSupport {

    static final EmbeddedPostgres PG;
    public static final String MASTER_KEY = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32});
    public static final String RUNNER_TOKEN = "runner-token-for-tests-0123456789abcdef";
    public static final String WEBHOOK_SECRET = "webhook-secret-for-tests-0123456789abcdef";
    static final Path STATE;

    static {
        try {
            PG = EmbeddedPostgres.builder().start();
            STATE = Files.createTempDirectory("autopilot-saas-test");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void saasProperties(DynamicPropertyRegistry r) {
        String url = "jdbc:postgresql://localhost:" + PG.getPort() + "/postgres";
        r.add("spring.datasource.url", () -> url);
        r.add("spring.datasource.username", () -> "autopilot_app");
        r.add("spring.datasource.password", () -> "");
        r.add("spring.flyway.url", () -> url);
        r.add("spring.flyway.user", () -> "postgres");
        r.add("spring.flyway.password", () -> "");
        r.add("autopilot.crypto.master-key", () -> MASTER_KEY);
        r.add("autopilot.runner.scheduling", () -> "false");
        // Les tests visent des adresses de documentation et privées (10.x) : lab autorisé, sauf test dédié.
        r.add("autopilot.servers.allow-private-targets", () -> "true");
        r.add("autopilot.runner.token", () -> RUNNER_TOKEN);
        r.add("autopilot.billing.webhook-secret", () -> WEBHOOK_SECRET);
        r.add("autopilot.public-url", () -> "http://localhost:4200");
        r.add("devops.state-dir", STATE::toString);
        r.add("AUTOPILOT_BOOTSTRAP_ADMIN_EMAIL", () -> "");
    }

    @TestConfiguration
    public static class FakeEngineConfig {
        @Bean
        @Primary
        FakeEngine fakeEngine() {
            return new FakeEngine();
        }
    }

    /** Moteur déterministe : plans fixes, exécutions enregistrées, résultat pilotable. */
    public static class FakeEngine implements EnginePort {
        public static final String FINGERPRINT = "SHA256:" + "A".repeat(43);
        public final List<ExecutionRequest> executed = new ArrayList<>();
        public final AtomicReference<String> planVariant = new AtomicReference<>("v1");
        public volatile boolean fail;

        @Override
        public Plan plan(PlanRequest req) {
            if (req.type().risk() != com.nebdev.devopsagent.saas.operation.OperationType.Risk.READ
                    && req.type() != com.nebdev.devopsagent.saas.operation.OperationType.ROTATE_CREDENTIAL
                    && req.latestSnapshot() == null) {
                throw new PlanRefused("discovery_required", "analyse requise");
            }
            return new Plan(req.type().name(), req.type().label(), List.of("step"), List.of(), List.of(), "none",
                    "sha-" + planVariant.get(), Map.of());
        }

        @Override
        public synchronized Result execute(ExecutionRequest req) {
            executed.add(req);
            Plan current = plan(new PlanRequest(req.type(), req.target(), req.parameters(), Map.of("x", 1), null));
            if (!req.planStillApproved().test(current)) {
                return new Result(false, null, "", "plan_changed", Map.of(), null, false);
            }
            if (fail) {
                return new Result(false, 2, "boom", "remote_failure", Map.of(), null, false);
            }
            Observation obs = new Observation(Map.of("host", req.target().host(), "osFamily", "debian"), 91, "A",
                    "debian", "12", Map.of("docker", true));
            return new Result(true, 0, "ok " + req.type(), null, Map.of("componentPresent", true), obs,
                    req.type() == com.nebdev.devopsagent.saas.operation.OperationType.ROTATE_CREDENTIAL);
        }

        @Override
        public String scanHostKey(Target target) {
            return FINGERPRINT;
        }

        @Override
        public String version() {
            return "test";
        }

        @Override
        public Summary summarize(Map<String, Object> snapshot) {
            return new Summary(Map.of("osFamily", "debian", "auditGrade", "C"), List.of(Map.of("severity", "ELEVE",
                    "category", "Sauvegardes", "title", "No backup", "recommendation", "Install backups")));
        }
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper mapper;
    @Autowired protected FakeEngine engine;
    @Autowired protected ExecutionWorker worker;
    @Autowired protected com.nebdev.devopsagent.saas.notification.NotificationService.Notifications notifications;
    @Autowired protected com.nebdev.devopsagent.saas.shared.RlsContext rlsContext;
    @Autowired protected org.springframework.transaction.PlatformTransactionManager transactions;

    /** Le jeton brut n'est jamais stocké : on le relit dans le dernier e-mail envoyé (comme l'utilisateur). */
    protected String tokenFromMail(String email, String prefix) {
        String body = new org.springframework.transaction.support.TransactionTemplate(transactions).execute(s -> {
            rlsContext.enterPlatform();
            return notifications.forRecipient(email).get(0).getBody();
        });
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("token=(" + prefix + "_[A-Za-z0-9_-]+)").matcher(body);
        if (!m.find()) {
            throw new AssertionError("Aucun jeton dans : " + body);
        }
        return m.group(1);
    }

    // ───────────── Outils HTTP ─────────────

    protected record Session(String token, UUID userId, UUID orgId, String email) {}

    protected Session register(String prefix) throws Exception {
        String email = prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        JsonNode r = json(mvc.perform(post("/api/v2/public/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email", email, "password", "Str0ng-Passphrase!",
                        "displayName", prefix, "organizationName", prefix + " Org", "currency", "EUR"))))
                .andReturn(), 201);
        return new Session(r.get("token").asText(), UUID.fromString(r.get("user").get("id").asText()),
                UUID.fromString(r.get("organizationId").asText()), email);
    }

    protected Session login(String email, UUID orgId) throws Exception {
        JsonNode r = json(mvc.perform(post("/api/v2/public/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email", email, "password", "Str0ng-Passphrase!")))).andReturn(), 200);
        return new Session(r.get("token").asText(), UUID.fromString(r.get("user").get("id").asText()), orgId, email);
    }

    protected MvcResult call(String token, MockHttpServletRequestBuilder b) throws Exception {
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return mvc.perform(b).andReturn();
    }

    protected MvcResult getAs(String token, String url) throws Exception {
        return call(token, get(url));
    }

    protected MvcResult postAs(String token, String url, Object body) throws Exception {
        return call(token, post(url).contentType(MediaType.APPLICATION_JSON).content(body == null ? "{}" : mapper.writeValueAsString(body)));
    }

    protected MvcResult patchAs(String token, String url, Object body) throws Exception {
        return call(token, patch(url).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)));
    }

    protected MvcResult putAs(String token, String url, Object body) throws Exception {
        return call(token, put(url).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)));
    }

    protected MvcResult deleteAs(String token, String url) throws Exception {
        return call(token, delete(url));
    }

    protected JsonNode json(MvcResult r, int expectedStatus) throws Exception {
        String body = r.getResponse().getContentAsString();
        if (r.getResponse().getStatus() != expectedStatus) {
            throw new AssertionError("HTTP " + r.getResponse().getStatus() + " (attendu " + expectedStatus + ") : " + body);
        }
        return body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body);
    }

    /** Active la MFA du compte (la session en cours reste valide) : prérequis du rôle de personnel INOVNE. */
    protected Session withMfa(Session s) throws Exception {
        JsonNode setup = json(postAs(s.token(), "/api/v2/me/mfa/setup", null), 200);
        byte[] secret = SaasAuthTest.base32Decode(setup.get("secret").asText());
        String code = com.nebdev.devopsagent.saas.identity.Totp.generate(secret, java.time.Instant.now().getEpochSecond() / 30);
        json(postAs(s.token(), "/api/v2/me/mfa/enable", Map.of("code", code)), 200);
        return s;
    }

    /** Serveur prêt : créé, empreinte approuvée, clé active. */
    protected UUID readyServer(Session s, String host) throws Exception {
        JsonNode srv = json(postAs(s.token(), "/api/v2/orgs/" + s.orgId() + "/servers",
                Map.of("name", "srv " + host, "host", host, "port", 22, "user", "ubuntu")), 201);
        UUID id = UUID.fromString(srv.get("id").asText());
        String base = "/api/v2/orgs/" + s.orgId() + "/servers/" + id;
        JsonNode scan = json(postAs(s.token(), base + "/host-key/scan", null), 200);
        json(postAs(s.token(), base + "/host-key/trust", Map.of("fingerprint", scan.get("fingerprint").asText())), 200);
        json(postAs(s.token(), base + "/credentials/generate", null), 201);
        return id;
    }

    protected JsonNode propose(Session s, UUID serverId, String type) throws Exception {
        return json(postAs(s.token(), "/api/v2/orgs/" + s.orgId() + "/operations",
                Map.of("serverId", serverId.toString(), "type", type, "reason", "test")), 201);
    }

    protected JsonNode operation(Session s, String opId) throws Exception {
        return json(getAs(s.token(), "/api/v2/orgs/" + s.orgId() + "/operations/" + opId), 200);
    }

    /** Analyse puis exécution : le serveur a un snapshot (prérequis des CHANGE). */
    protected void discovered(Session s, UUID serverId) throws Exception {
        JsonNode op = propose(s, serverId, "DISCOVER");
        drain();
        if (!"SUCCEEDED".equals(operation(s, op.get("id").asText()).get("status").asText())) {
            throw new AssertionError("Analyse non réussie");
        }
    }

    protected void drain() {
        for (int i = 0; i < 20; i++) {
            worker.dispatchApproved();
            worker.runClaimedNow();
        }
    }
}
