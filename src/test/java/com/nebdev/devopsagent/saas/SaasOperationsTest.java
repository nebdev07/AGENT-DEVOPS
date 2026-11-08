package com.nebdev.devopsagent.saas;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Directive §8 : plan explicite, approbation liée au plan exact et limitée dans le temps, pas de double exécution. */
class SaasOperationsTest extends SaasTestSupport {

    @AfterEach
    void reset() {
        engine.planVariant.set("v1");
        engine.fail = false;
    }

    @Test
    void readOperationIsAutoApprovedExecutedAndRecordsASnapshot() throws Exception {
        Session s = register("ops-read");
        UUID server = readyServer(s, "10.10.0.1");
        JsonNode op = propose(s, server, "DISCOVER");
        assertThat(op.get("status").asText()).isEqualTo("QUEUED");
        assertThat(op.get("risk").asText()).isEqualTo("READ");
        drain();
        JsonNode done = operation(s, op.get("id").asText());
        assertThat(done.get("status").asText()).isEqualTo("SUCCEEDED");
        JsonNode exec = done.get("executions").get(0);
        assertThat(exec.get("outputSha256").asText()).hasSize(64);
        assertThat(exec.get("output").asText()).contains("ok DISCOVER");
        JsonNode snap = json(getAs(s.token(), "/api/v2/orgs/" + s.orgId() + "/servers/" + server + "/snapshot"), 200);
        assertThat(snap.get("auditGrade").asText()).isEqualTo("A");
        JsonNode srv = json(getAs(s.token(), "/api/v2/orgs/" + s.orgId() + "/servers/" + server), 200);
        assertThat(srv.get("connectionState").asText()).isEqualTo("REACHABLE");
        // La clé privée a bien été transmise au moteur pour CE serveur (jamais au client).
        assertThat(engine.executed.get(engine.executed.size() - 1).credentials().privatePem()).contains("PRIVATE KEY");
        assertThat(engine.executed.get(engine.executed.size() - 1).credentials().hostFingerprint()).isEqualTo(FakeEngine.FINGERPRINT);
    }

    @Test
    void changeRequiresApprovalOfTheExactPlanHash() throws Exception {
        Session s = register("ops-change");
        UUID server = readyServer(s, "10.11.0.1");
        discovered(s, server);
        JsonNode op = propose(s, server, "INSTALL_DOCKER");
        String id = op.get("id").asText();
        assertThat(op.get("status").asText()).isEqualTo("PROPOSED");
        String hash = op.get("planHash").asText();
        assertThat(hash).hasSize(64);
        assertThat(op.get("plan").get("target").get("host").asText()).isEqualTo("10.11.0.1");

        drain();
        assertThat(operation(s, id).get("status").asText()).as("rien ne s'exécute sans approbation").isEqualTo("PROPOSED");

        String base = "/api/v2/orgs/" + s.orgId() + "/operations/" + id;
        JsonNode wrong = json(postAs(s.token(), base + "/approve", Map.of("planHash", "0".repeat(64))), 409);
        assertThat(wrong.get("code").asText()).isEqualTo("plan_hash_mismatch");
        JsonNode approved = json(postAs(s.token(), base + "/approve", Map.of("planHash", hash, "comment", "ok")), 200);
        assertThat(approved.get("status").asText()).isEqualTo("QUEUED");
        // Double approbation : refusée, une seule exécution.
        assertThat(postAs(s.token(), base + "/approve", Map.of("planHash", hash)).getResponse().getStatus()).isEqualTo(409);
        drain();
        JsonNode done = operation(s, id);
        assertThat(done.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(done.get("executions")).hasSize(1);
        assertThat(done.get("approvals").get(0).get("planHash").asText()).isEqualTo(hash);
        JsonNode verify = json(getAs(s.token(), "/api/v2/orgs/" + s.orgId() + "/audit-events/verify"), 200);
        assertThat(verify.get("valid").asBoolean()).isTrue();
    }

    @Test
    void planThatChangesAfterApprovalIsNotExecuted() throws Exception {
        Session s = register("ops-drift");
        UUID server = readyServer(s, "10.12.0.1");
        discovered(s, server);
        JsonNode op = propose(s, server, "INSTALL_TRAEFIK");
        json(postAs(s.token(), "/api/v2/orgs/" + s.orgId() + "/operations/" + op.get("id").asText() + "/approve",
                Map.of("planHash", op.get("planHash").asText())), 200);
        engine.planVariant.set("v2"); // le script du rôle a changé entre l'approbation et l'exécution
        drain();
        JsonNode done = operation(s, op.get("id").asText());
        assertThat(done.get("status").asText()).isEqualTo("FAILED");
        assertThat(done.get("executions").get(0).get("error").asText()).isEqualTo("plan_changed");
    }

    @Test
    void movingTheServerChangesThePlanHash() throws Exception {
        Session s = register("ops-move");
        UUID server = readyServer(s, "10.13.0.1");
        discovered(s, server);
        JsonNode before = propose(s, server, "INSTALL_REDIS");
        json(patchAs(s.token(), "/api/v2/orgs/" + s.orgId() + "/servers/" + server, Map.of("user", "admin")), 200);
        // L'empreinte d'hôte reste (même hôte) ; l'utilisateur fait partie de la cible hachée.
        JsonNode after = propose(s, server, "INSTALL_REDIS");
        assertThat(after.get("planHash").asText()).isNotEqualTo(before.get("planHash").asText());
        JsonNode stale = json(postAs(s.token(), "/api/v2/orgs/" + s.orgId() + "/operations/" + before.get("id").asText()
                + "/approve", Map.of("planHash", before.get("planHash").asText())), 200);
        drain();
        assertThat(operation(s, stale.get("id").asText()).get("status").asText()).isEqualTo("FAILED");
    }

    @Test
    void oneExecutionAtATimePerServer() throws Exception {
        Session s = register("ops-lock");
        UUID server = readyServer(s, "10.14.0.1");
        discovered(s, server);
        JsonNode a = propose(s, server, "INSTALL_DOCKER");
        JsonNode b = propose(s, server, "INSTALL_MARIADB");
        String base = "/api/v2/orgs/" + s.orgId() + "/operations/";
        JsonNode qa = json(postAs(s.token(), base + a.get("id").asText() + "/approve", Map.of("planHash", a.get("planHash").asText())), 200);
        JsonNode qb = json(postAs(s.token(), base + b.get("id").asText() + "/approve", Map.of("planHash", b.get("planHash").asText())), 200);
        assertThat(qa.get("status").asText()).isEqualTo("QUEUED");
        assertThat(qb.get("status").asText()).as("serveur occupé : attend son tour").isEqualTo("APPROVED");
        drain();
        assertThat(operation(s, a.get("id").asText()).get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(operation(s, b.get("id").asText()).get("status").asText()).isEqualTo("SUCCEEDED");
    }

    @Test
    void idempotencyKeyReturnsTheSameOperation() throws Exception {
        Session s = register("ops-idem");
        UUID server = readyServer(s, "10.15.0.1");
        String body = mapper.writeValueAsString(Map.of("serverId", server.toString(), "type", "DISCOVER"));
        JsonNode first = json(call(s.token(), post("/api/v2/orgs/" + s.orgId() + "/operations")
                .contentType("application/json").header("Idempotency-Key", "k-1").content(body)), 201);
        JsonNode second = json(call(s.token(), post("/api/v2/orgs/" + s.orgId() + "/operations")
                .contentType("application/json").header("Idempotency-Key", "k-1").content(body)), 201);
        assertThat(second.get("id").asText()).isEqualTo(first.get("id").asText());
        String other = mapper.writeValueAsString(Map.of("serverId", server.toString(), "type", "INSTALL_DOCKER"));
        assertThat(call(s.token(), post("/api/v2/orgs/" + s.orgId() + "/operations").contentType("application/json")
                .header("Idempotency-Key", "k-1").content(other)).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void preconditionsAreEnforcedBeforePlanning() throws Exception {
        Session s = register("ops-pre");
        JsonNode srv = json(postAs(s.token(), "/api/v2/orgs/" + s.orgId() + "/servers",
                Map.of("name", "raw", "host", "10.16.0.1", "user", "ubuntu")), 201);
        UUID id = UUID.fromString(srv.get("id").asText());
        String ops = "/api/v2/orgs/" + s.orgId() + "/operations";
        assertThat(json(postAs(s.token(), ops, Map.of("serverId", id.toString(), "type", "DISCOVER")), 422)
                .get("code").asText()).isEqualTo("host_key_not_trusted");
        assertThat(json(postAs(s.token(), ops, Map.of("serverId", id.toString(), "type", "RM_RF")), 400)
                .get("code").asText()).isEqualTo("unknown_operation_type");
        UUID ready = readyServer(s, "10.16.0.2");
        assertThat(json(postAs(s.token(), ops, Map.of("serverId", ready.toString(), "type", "INSTALL_DOCKER")), 422)
                .get("code").asText()).isEqualTo("discovery_required");
        json(patchAs(s.token(), "/api/v2/orgs/" + s.orgId() + "/servers/" + ready, Map.of("protection", "READ_ONLY")), 200);
        assertThat(json(postAs(s.token(), ops, Map.of("serverId", ready.toString(), "type", "INSTALL_DOCKER")), 422)
                .get("code").asText()).isEqualTo("server_read_only");
        propose(s, ready, "DISCOVER");
    }

    @Test
    void fourEyesRequiresAnotherApprover() throws Exception {
        Session owner = register("ops-4eyes");
        json(patchAs(owner.token(), "/api/v2/orgs/" + owner.orgId(), Map.of("requireFourEyes", true)), 200);
        UUID server = readyServer(owner, "10.17.0.1");
        discovered(owner, server);
        JsonNode op = propose(owner, server, "INSTALL_DOCKER");
        String approve = "/api/v2/orgs/" + owner.orgId() + "/operations/" + op.get("id").asText() + "/approve";
        assertThat(json(postAs(owner.token(), approve, Map.of("planHash", op.get("planHash").asText())), 403)
                .get("code").asText()).isEqualTo("four_eyes_required");
        Session tech = join(owner, "TECHNICAL_MANAGER");
        assertThat(json(postAs(tech.token(), approve, Map.of("planHash", op.get("planHash").asText())), 200)
                .get("status").asText()).isEqualTo("QUEUED");
    }

    @Test
    void rolesAreEnforced() throws Exception {
        Session owner = register("ops-roles");
        UUID server = readyServer(owner, "10.18.0.1");
        discovered(owner, server);
        Session viewer = join(owner, "VIEWER");
        Session dev = join(owner, "DEVELOPER");
        String ops = "/api/v2/orgs/" + owner.orgId() + "/operations";
        assertThat(getAs(viewer.token(), ops).getResponse().getStatus()).isEqualTo(200);
        assertThat(postAs(viewer.token(), ops, Map.of("serverId", server.toString(), "type", "DISCOVER"))
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(postAs(viewer.token(), "/api/v2/orgs/" + owner.orgId() + "/servers",
                Map.of("name", "x", "host", "10.18.0.9", "user", "ubuntu")).getResponse().getStatus()).isEqualTo(403);
        JsonNode proposed = json(postAs(dev.token(), ops, Map.of("serverId", server.toString(), "type", "INSTALL_DOCKER")), 201);
        assertThat(json(postAs(dev.token(), ops + "/" + proposed.get("id").asText() + "/approve",
                Map.of("planHash", proposed.get("planHash").asText())), 403).get("code").asText()).isEqualTo("missing_permission");
        // Un développeur ne peut pas s'auto-promouvoir ni nommer un propriétaire.
        assertThat(putAs(dev.token(), "/api/v2/orgs/" + owner.orgId() + "/members/" + dev.userId() + "/role",
                Map.of("role", "ORGANIZATION_OWNER")).getResponse().getStatus()).isEqualTo(403);
        // Le dernier propriétaire ne peut être ni rétrogradé ni retiré.
        assertThat(json(putAs(owner.token(), "/api/v2/orgs/" + owner.orgId() + "/members/" + owner.userId() + "/role",
                Map.of("role", "VIEWER")), 409).get("code").asText()).isEqualTo("last_owner");
    }

    @Test
    void credentialRotationPromotesTheNewKey() throws Exception {
        Session s = register("ops-rotate");
        UUID server = readyServer(s, "10.19.0.1");
        String creds = "/api/v2/orgs/" + s.orgId() + "/servers/" + server + "/credentials";
        JsonNode pending = json(postAs(s.token(), creds + "/generate", null), 201);
        assertThat(pending.get("status").asText()).isEqualTo("PENDING");
        JsonNode op = propose(s, server, "ROTATE_CREDENTIAL");
        json(postAs(s.token(), "/api/v2/orgs/" + s.orgId() + "/operations/" + op.get("id").asText() + "/approve",
                Map.of("planHash", op.get("planHash").asText())), 200);
        drain();
        assertThat(operation(s, op.get("id").asText()).get("status").asText()).isEqualTo("SUCCEEDED");
        JsonNode list = json(getAs(s.token(), creds), 200);
        assertThat(list.get(0).get("id").asText()).isEqualTo(pending.get("id").asText());
        assertThat(list.get(0).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(list.get(1).get("status").asText()).isEqualTo("REVOKED");
        // La rotation a transmis l'ancienne ET la nouvelle clé au moteur.
        var req = engine.executed.get(engine.executed.size() - 1);
        assertThat(req.credentials().pendingPrivatePem()).contains("PRIVATE KEY");
        assertThat(req.credentials().activePublicKey()).startsWith("ssh-rsa ");
    }

    @Test
    void failedExecutionIsRecordedAndNotRetried() throws Exception {
        Session s = register("ops-fail");
        UUID server = readyServer(s, "10.20.0.1");
        discovered(s, server);
        JsonNode op = propose(s, server, "INSTALL_DOCKER");
        json(postAs(s.token(), "/api/v2/orgs/" + s.orgId() + "/operations/" + op.get("id").asText() + "/approve",
                Map.of("planHash", op.get("planHash").asText())), 200);
        engine.fail = true;
        int before = engine.executed.size();
        drain();
        JsonNode done = operation(s, op.get("id").asText());
        assertThat(done.get("status").asText()).isEqualTo("FAILED");
        assertThat(done.get("executions").get(0).get("exitCode").asInt()).isEqualTo(2);
        assertThat(engine.executed.size() - before).as("aucune reprise automatique d'un CHANGE").isEqualTo(1);
    }

    /** Invite un membre avec le rôle donné et renvoie sa session. */
    Session join(Session owner, String role) throws Exception {
        String email = role.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 6) + "@example.test";
        json(postAs(owner.token(), "/api/v2/orgs/" + owner.orgId() + "/invitations", Map.of("email", email, "role", role)), 201);
        String token = tokenFromMail(email, "inv");
        JsonNode r = json(postAs(null, "/api/v2/public/auth/invitations/accept",
                Map.of("token", token, "password", "Str0ng-Passphrase!", "displayName", role)), 200);
        return new Session(r.get("token").asText(), UUID.fromString(r.get("user").get("id").asText()), owner.orgId(), email);
    }
}
