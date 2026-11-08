package com.nebdev.devopsagent.saas;

import com.fasterxml.jackson.databind.JsonNode;
import com.nebdev.devopsagent.saas.credential.CredentialService;
import com.nebdev.devopsagent.saas.credential.SshKeys;
import com.nebdev.devopsagent.saas.runner.RunnerAgent;
import com.nebdev.devopsagent.saas.runner.RunnerAgentProperties;
import com.nebdev.devopsagent.saas.shared.SaasProperties;
import com.nebdev.devopsagent.saas.shared.SealedBox;
import com.nebdev.devopsagent.saas.shared.TargetPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Runner séparé (ADR-055), de bout en bout sur HTTP réel : le control plane (mode remote) scelle les clés SSH pour
 * la clé publique du runner et ne sait plus les rouvrir ; le runner réserve, ouvre, contrôle le hash du plan et rend
 * compte. Le moteur du runner est un {@link FakeEngine} distinct de celui du control plane.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SaasRemoteRunnerTest extends SaasTestSupport {

    static final KeyPair RUNNER_KEYS = SealedBox.generateKeyPair();

    @DynamicPropertySource
    static void remote(DynamicPropertyRegistry r) {
        r.add("autopilot.runner.mode", () -> "remote");
        r.add("autopilot.runner.public-key", () -> SealedBox.toPem(RUNNER_KEYS.getPublic()));
        r.add("autopilot.servers.denied-targets", () -> "203.0.113.66,forbidden.example.test");
    }

    @LocalServerPort int port;
    @Autowired CredentialService vault;
    @Autowired DataSource dataSource;
    @Autowired CredentialService.DataKeys dataKeys;
    @Autowired CredentialService.Credentials credentialRepo;
    @Autowired CredentialService.Secrets secretRepo;
    @Autowired com.nebdev.devopsagent.saas.shared.CryptoService crypto;
    @Autowired com.nebdev.devopsagent.saas.organization.OrgAccess access;
    @Autowired com.nebdev.devopsagent.saas.server.ServerService serverService;
    @Autowired com.nebdev.devopsagent.saas.audit.AuditService auditService;

    FakeEngine runnerEngine;
    RunnerAgent runner;

    @BeforeEach
    void runner() {
        runnerEngine = new FakeEngine();
        runner = agent("runner-a");
    }

    RunnerAgent agent(String id) {
        RunnerAgentProperties p = new RunnerAgentProperties();
        p.setControlPlaneUrl("http://localhost:" + port);
        p.setToken(RUNNER_TOKEN);
        p.setPrivateKey(SealedBox.toPem(RUNNER_KEYS.getPrivate()));
        p.setId(id);
        return new RunnerAgent(p, runnerEngine, mapper);
    }

    private <T> T inPlatform(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(transactions).execute(s -> {
            rlsContext.enterPlatform();
            return work.get();
        });
    }

    private byte[] storedKey(UUID serverId) {
        return inPlatform(() -> new JdbcTemplate(dataSource).queryForObject(
                "select private_key_enc from server_credential where server_id = ? and status = 'ACTIVE'", byte[].class, serverId));
    }

    @Test
    void controlPlaneSealsKeysItCannotOpenAndRunnerExecutes() throws Exception {
        Session s = register("remote");
        UUID serverId = readyServer(s, "198.51.100.71");

        byte[] blob = storedKey(serverId);
        assertThat(SealedBox.isSealed(blob)).as("clé scellée pour le runner (format 2)").isTrue();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(t -> {
            rlsContext.enterOrg(s.orgId());
            vault.unlock(s.orgId(), serverId, "ACTIVE");
        })).hasMessageContaining("sealed_for_remote_runner");
        assertThatThrownBy(worker::runClaimedNow).isInstanceOf(IllegalStateException.class);

        JsonNode op = propose(s, serverId, "DISCOVER");
        worker.dispatchApproved();
        assertThat(runner.pollOnce()).isTrue();
        assertThat(runner.pollOnce()).as("file vide").isFalse();

        JsonNode done = operation(s, op.get("id").asText());
        assertThat(done.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(done.get("executions").get(0).get("output").asText()).isEqualTo("ok DISCOVER");
        assertThat(runnerEngine.executed).hasSize(1);
        assertThat(engine.executed).as("le control plane n'exécute rien").isEmpty();

        // Le runner a reçu la vraie clé : elle correspond à la clé publique affichée par l'API.
        String pem = runnerEngine.executed.get(0).credentials().privatePem();
        JsonNode creds = json(getAs(s.token(), "/api/v2/orgs/" + s.orgId() + "/servers/" + serverId + "/credentials"), 200);
        assertThat(SshKeys.parse(pem, "x").fingerprint()).isEqualTo(creds.get(0).get("fingerprint").asText());
        assertThat(runnerEngine.executed.get(0).credentials().hostFingerprint()).isEqualTo(FakeEngine.FINGERPRINT);

        JsonNode snap = json(getAs(s.token(), "/api/v2/orgs/" + s.orgId() + "/servers/" + serverId + "/snapshot"), 200);
        assertThat(snap.get("auditGrade").asText()).isEqualTo("A");
    }

    @Test
    void runnerRefusesWhenPlanChangedSinceApproval() throws Exception {
        Session s = register("remote-plan");
        UUID serverId = readyServer(s, "198.51.100.72");
        discoveredRemote(s, serverId);

        JsonNode op = propose(s, serverId, "INSTALL_DOCKER");
        json(postAs(s.token(), "/api/v2/orgs/" + s.orgId() + "/operations/" + op.get("id").asText() + "/approve",
                Map.of("planHash", op.get("planHash").asText())), 200);
        runnerEngine.planVariant.set("v2"); // l'état réel du serveur a changé depuis l'approbation
        worker.dispatchApproved();
        assertThat(runner.pollOnce()).isTrue();

        JsonNode done = operation(s, op.get("id").asText());
        assertThat(done.get("status").asText()).isEqualTo("FAILED");
        assertThat(done.get("executions").get(0).get("error").asText()).isEqualTo("plan_changed");
    }

    @Test
    void internalApiIsReservedToTheRunnerThatClaimed() throws Exception {
        Session s = register("remote-auth");
        assertThat(call(null, post("/api/v2/internal/runner/claim").header("X-Runner-Id", "x")).getResponse().getStatus())
                .isIn(401, 403);
        assertThat(call(s.token(), post("/api/v2/internal/runner/claim").header("X-Runner-Id", "x")).getResponse().getStatus())
                .isEqualTo(403);

        UUID serverId = readyServer(s, "198.51.100.73");
        propose(s, serverId, "DISCOVER");
        worker.dispatchApproved();
        JsonNode job = json(call(RUNNER_TOKEN, post("/api/v2/internal/runner/claim").header("X-Runner-Id", "runner-b")), 200);
        assertThat(job.toString()).as("aucune clé en clair dans le travail").doesNotContain("PRIVATE KEY");
        String exec = job.get("executionId").asText();
        String result = mapper.writeValueAsString(Map.of("success", true, "output", "forged", "credentialRotated", false));

        assertThat(call(RUNNER_TOKEN, post("/api/v2/internal/runner/executions/" + exec + "/result")
                .header("X-Runner-Id", "intrus").contentType(MediaType.APPLICATION_JSON).content(result))
                .getResponse().getStatus()).as("autre runner").isEqualTo(409);
        assertThat(call(RUNNER_TOKEN, post("/api/v2/internal/runner/executions/" + exec + "/heartbeat")
                .header("X-Runner-Id", "runner-b")).getResponse().getStatus()).isEqualTo(204);
        assertThat(call(RUNNER_TOKEN, post("/api/v2/internal/runner/executions/" + exec + "/result")
                .header("X-Runner-Id", "runner-b").contentType(MediaType.APPLICATION_JSON).content(result))
                .getResponse().getStatus()).isEqualTo(204);
        assertThat(call(RUNNER_TOKEN, post("/api/v2/internal/runner/executions/" + exec + "/result")
                .header("X-Runner-Id", "runner-b").contentType(MediaType.APPLICATION_JSON).content(result))
                .getResponse().getStatus()).as("un seul compte rendu fait foi").isEqualTo(409);
    }

    @Test
    void legacyEnvelopeValuesAreResealedAndBackupPassphraseReachesRunner() throws Exception {
        Session s = register("remote-reseal");
        // Valeur écrite par un control plane encore en embedded (enveloppe KEK/DEK, format 1).
        SaasProperties embedded = new SaasProperties();
        CredentialService legacy = new CredentialService(dataKeys, credentialRepo, secretRepo, crypto, access,
                serverService, auditService, rlsContext, embedded);
        String passphrase = new TransactionTemplate(transactions).execute(t -> {
            rlsContext.enterOrg(s.orgId());
            return legacy.backupPassphrase(s.orgId());
        });
        assertThat(vault.resealAll()).isGreaterThanOrEqualTo(1);
        String sealed = new TransactionTemplate(transactions).execute(t -> {
            rlsContext.enterOrg(s.orgId());
            return vault.sealedBackupPassphrase(s.orgId());
        });
        byte[] opened = SealedBox.open(RUNNER_KEYS.getPrivate(), SealedBox.secretAad(s.orgId(), CredentialService.BACKUP_SECRET),
                java.util.Base64.getDecoder().decode(sealed));
        assertThat(new String(opened, StandardCharsets.UTF_8)).isEqualTo(passphrase);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(t -> {
            rlsContext.enterOrg(s.orgId());
            return legacy.backupPassphrase(s.orgId());
        })).as("la clé maîtresse seule ne rouvre plus rien").hasMessageContaining("sealed_for_remote_runner");
    }

    @Test
    void sealedBoxBindsContextAndKey() {
        UUID org = UUID.randomUUID();
        UUID cred = UUID.randomUUID();
        byte[] blob = SealedBox.seal(RUNNER_KEYS.getPublic(), SealedBox.credentialAad(org, cred), "secret".getBytes());
        assertThat(new String(SealedBox.open(RUNNER_KEYS.getPrivate(), SealedBox.credentialAad(org, cred), blob))).isEqualTo("secret");
        assertThatThrownBy(() -> SealedBox.open(RUNNER_KEYS.getPrivate(), SealedBox.credentialAad(UUID.randomUUID(), cred), blob))
                .as("copiée vers une autre organisation").isInstanceOf(IllegalStateException.class);
        KeyPair other = SealedBox.generateKeyPair();
        assertThatThrownBy(() -> SealedBox.open(other.getPrivate(), SealedBox.credentialAad(org, cred), blob))
                .as("autre runner").isInstanceOf(IllegalStateException.class);
        assertThat(SealedBox.keyId(SealedBox.publicOf(RUNNER_KEYS.getPrivate()))).isEqualTo(SealedBox.keyId(RUNNER_KEYS.getPublic()));
        assertThatThrownBy(() -> SealedBox.parsePublicKey("-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void internalTargetsAreRefusedByPlatformAndRunner() throws Exception {
        Session s = register("ssrf");
        String servers = "/api/v2/orgs/" + s.orgId() + "/servers";
        JsonNode denied = json(postAs(s.token(), servers, Map.of("name", "x", "host", "203.0.113.66", "port", 22, "user", "u")), 422);
        assertThat(denied.get("code").asText()).isEqualTo("target_not_allowed");

        // Le control plane de test autorise le lab (réseaux privés) ; le runner, lui, applique sa politique par défaut.
        UUID loopback = readyServer(s, "127.0.0.1");
        JsonNode op = propose(s, loopback, "DISCOVER");
        worker.dispatchApproved();
        assertThat(runner.pollOnce()).isTrue();
        JsonNode done = operation(s, op.get("id").asText());
        assertThat(done.get("status").asText()).isEqualTo("FAILED");
        assertThat(done.get("executions").get(0).get("error").asText()).isEqualTo("target_not_allowed");
        assertThat(runnerEngine.executed).as("aucune connexion tentée").isEmpty();
    }

    @Test
    void targetPolicyClassifiesAddresses() {
        for (String internal : List.of("127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.10.1", "100.64.0.1",
                "::1", "fd00::1", "fe80::1", "::ffff:10.0.0.1", "localhost")) {
            assertThatThrownBy(() -> TargetPolicy.resolveForConnection(internal, false, Set.of()))
                    .as(internal).isInstanceOf(TargetPolicy.Refused.class);
        }
        for (String never : List.of("0.0.0.0", "169.254.169.254", "224.0.0.1", "255.255.255.255")) {
            assertThatThrownBy(() -> TargetPolicy.resolveForConnection(never, true, Set.of()))
                    .as("même en lab : " + never).isInstanceOf(TargetPolicy.Refused.class);
        }
        assertThat(TargetPolicy.resolveForConnection("198.51.100.20", false, Set.of()).getHostAddress()).isEqualTo("198.51.100.20");
        assertThat(TargetPolicy.resolveForConnection("10.1.2.3", true, Set.of()).getHostAddress()).isEqualTo("10.1.2.3");
        assertThatThrownBy(() -> TargetPolicy.resolveForConnection("198.51.100.20", false, TargetPolicy.normalize(List.of("198.51.100.20"))))
                .isInstanceOf(TargetPolicy.Refused.class);
    }

    private void discoveredRemote(Session s, UUID serverId) throws Exception {
        JsonNode op = propose(s, serverId, "DISCOVER");
        worker.dispatchApproved();
        assertThat(runner.pollOnce()).isTrue();
        assertThat(operation(s, op.get("id").asText()).get("status").asText()).isEqualTo("SUCCEEDED");
    }
}
