package com.nebdev.devopsagent.saas;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/** Fiches de déploiement SaaS + opérations DEPLOY_* (HTTPS seulement, plan figé). */
class SaasDeploymentTest extends SaasTestSupport {

    @Test
    void platformSpecCanBeStoredAndDeployProposedWithFrozenSpec() throws Exception {
        Session s = register("deploy-plat");
        UUID server = readyServer(s, "10.20.0.1");
        discovered(s, server);
        String base = "/api/v2/orgs/" + s.orgId() + "/servers/" + server + "/deployments";
        JsonNode created = json(postAs(s.token(), base + "/platforms", Map.of(
                "id", "demo",
                "baseDomain", "example.test",
                "routing", "path",
                "services", List.of(Map.of(
                        "id", "www",
                        "gitUrl", "https://github.com/example/demo.git",
                        "gitRef", "main",
                        "hostname", "demo.example.test",
                        "port", 3000,
                        "pathPrefix", "/",
                        "template", "next",
                        "needs", List.of()
                ))
        )), 201);
        assertThat(created.get("kind").asText()).isEqualTo("PLATFORM");
        assertThat(created.get("slug").asText()).isEqualTo("demo");
        String specId = created.get("id").asText();

        JsonNode missing = json(postAs(s.token(), "/api/v2/orgs/" + s.orgId() + "/operations",
                Map.of("serverId", server.toString(), "type", "DEPLOY_PLATFORM", "reason", "go")), 400);
        assertThat(missing.get("code").asText()).isEqualTo("deployment_spec_required");

        JsonNode op = json(postAs(s.token(), "/api/v2/orgs/" + s.orgId() + "/operations",
                Map.of("serverId", server.toString(), "type", "DEPLOY_PLATFORM", "reason", "go",
                        "parameters", Map.of("specId", specId))), 201);
        assertThat(op.get("status").asText()).isEqualTo("PROPOSED");
        assertThat(op.get("type").asText()).isEqualTo("DEPLOY_PLATFORM");
        assertThat(op.get("parameters").get("spec").get("id").asText()).isEqualTo("demo");
        assertThat(op.get("planHash").asText()).hasSize(64);

        json(postAs(s.token(), "/api/v2/orgs/" + s.orgId() + "/operations/" + op.get("id").asText() + "/approve",
                Map.of("planHash", op.get("planHash").asText())), 200);
        drain();
        assertThat(operation(s, op.get("id").asText()).get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(engine.executed.get(engine.executed.size() - 1).type().name()).isEqualTo("DEPLOY_PLATFORM");
    }

    @Test
    void sshGitDeployRequiresARegisteredDeployKey() throws Exception {
        Session s = register("deploy-ssh");
        UUID server = readyServer(s, "10.20.0.2");
        discovered(s, server);
        JsonNode app = json(postAs(s.token(),
                "/api/v2/orgs/" + s.orgId() + "/servers/" + server + "/deployments/apps",
                Map.of("id", "priv", "gitUrl", "git@github.com:example/priv.git", "gitRef", "main",
                        "hostname", "priv.example.test", "port", 8080)), 201);
        JsonNode missing = json(postAs(s.token(), "/api/v2/orgs/" + s.orgId() + "/operations",
                Map.of("serverId", server.toString(), "type", "DEPLOY_APP", "reason", "go",
                        "parameters", Map.of("specId", app.get("id").asText()))), 422);
        assertThat(missing.get("code").asText()).isEqualTo("git_credential_missing");

        JsonNode key = json(postAs(s.token(), "/api/v2/orgs/" + s.orgId() + "/git-credentials/generate",
                Map.of("gitUrl", "git@github.com:example/priv.git")), 201);
        assertThat(key.get("publicKey").asText()).startsWith("ssh-");
        assertThat(key.get("keyId").asText()).isEqualTo("github.com-example-priv");

        JsonNode op = json(postAs(s.token(), "/api/v2/orgs/" + s.orgId() + "/operations",
                Map.of("serverId", server.toString(), "type", "DEPLOY_APP", "reason", "go",
                        "parameters", Map.of("specId", app.get("id").asText()))), 201);
        assertThat(op.get("status").asText()).isEqualTo("PROPOSED");
        json(postAs(s.token(), "/api/v2/orgs/" + s.orgId() + "/operations/" + op.get("id").asText() + "/approve",
                Map.of("planHash", op.get("planHash").asText())), 200);
        drain();
        assertThat(operation(s, op.get("id").asText()).get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(engine.executed.get(engine.executed.size() - 1).gitDeployKeysBase64())
                .containsKey("github.com-example-priv");
    }

    @Test
    void platformSqlReadIsAutoApproved() throws Exception {
        Session s = register("deploy-sql");
        UUID server = readyServer(s, "10.20.0.3");
        discovered(s, server);
        JsonNode plat = json(postAs(s.token(),
                "/api/v2/orgs/" + s.orgId() + "/servers/" + server + "/deployments/platforms", Map.of(
                        "id", "sqlplat",
                        "baseDomain", "example.test",
                        "routing", "path",
                        "services", List.of(Map.of(
                                "id", "api",
                                "gitUrl", "https://github.com/example/api.git",
                                "gitRef", "main",
                                "hostname", "sqlplat.example.test",
                                "port", 8080,
                                "pathPrefix", "/api",
                                "template", "spring-boot-21",
                                "needs", List.of("mariadb")
                        ))
                )), 201);
        JsonNode op = json(postAs(s.token(), "/api/v2/orgs/" + s.orgId() + "/operations",
                Map.of("serverId", server.toString(), "type", "PLATFORM_SQL_READ", "reason", "select",
                        "parameters", Map.of("specId", plat.get("id").asText(), "sql", "SELECT 1"))), 201);
        assertThat(op.get("status").asText()).isEqualTo("QUEUED");
        assertThat(op.get("risk").asText()).isEqualTo("READ");
        drain();
        assertThat(operation(s, op.get("id").asText()).get("status").asText()).isEqualTo("SUCCEEDED");
    }

    @Test
    void restoreAndSecretUseOneShotStaging() throws Exception {
        Session s = register("deploy-stage");
        UUID server = readyServer(s, "10.20.0.4");
        discovered(s, server);
        JsonNode plat = json(postAs(s.token(),
                "/api/v2/orgs/" + s.orgId() + "/servers/" + server + "/deployments/platforms", Map.of(
                        "id", "rst",
                        "baseDomain", "example.test",
                        "routing", "host",
                        "services", List.of(Map.of(
                                "id", "api",
                                "gitUrl", "https://github.com/example/rst.git",
                                "gitRef", "main",
                                "hostname", "rst.example.test",
                                "port", 8080,
                                "template", "spring-boot-21",
                                "needs", List.of("mariadb")
                        ))
                )), 201);
        String base = "/api/v2/orgs/" + s.orgId() + "/servers/" + server + "/staging";

        MockMultipartFile dump = new MockMultipartFile("file", "dump.sql", "application/sql",
                "SELECT 1;".getBytes(StandardCharsets.UTF_8));
        JsonNode sqlStaged = json(call(s.token(), multipart(base + "/restore")
                .file(dump)
                .param("kind", "SQL_DUMP")
                .contentType(MediaType.MULTIPART_FORM_DATA)), 201);
        assertThat(sqlStaged.get("kind").asText()).isEqualTo("SQL_DUMP");
        assertThat(sqlStaged.get("contentSha256").asText()).hasSize(64);

        JsonNode restoreOp = json(postAs(s.token(), "/api/v2/orgs/" + s.orgId() + "/operations",
                Map.of("serverId", server.toString(), "type", "RESTORE_PLATFORM", "reason", "restore",
                        "parameters", Map.of("specId", plat.get("id").asText(),
                                "sqlStagingId", sqlStaged.get("id").asText()))), 201);
        assertThat(restoreOp.get("status").asText()).isEqualTo("PROPOSED");
        assertThat(restoreOp.get("parameters").get("sqlSha256").asText())
                .isEqualTo(sqlStaged.get("contentSha256").asText());
        assertThat(restoreOp.get("parameters").has("sql")).isFalse();
        json(postAs(s.token(), "/api/v2/orgs/" + s.orgId() + "/operations/" + restoreOp.get("id").asText() + "/approve",
                Map.of("planHash", restoreOp.get("planHash").asText())), 200);
        drain();
        assertThat(operation(s, restoreOp.get("id").asText()).get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(engine.executed.get(engine.executed.size() - 1).stagingPayloads())
                .containsKey("sqlStagingId");

        JsonNode secretStaged = json(postAs(s.token(), base + "/secrets",
                Map.of("serviceId", "api", "name", "MAIL_PASSWORD", "value", "s3cret-value", "restart", true)), 201);
        assertThat(secretStaged.get("meta").get("secretName").asText()).isEqualTo("MAIL_PASSWORD");
        assertThat(secretStaged.has("value")).isFalse();

        JsonNode setOp = json(postAs(s.token(), "/api/v2/orgs/" + s.orgId() + "/operations",
                Map.of("serverId", server.toString(), "type", "SET_PLATFORM_SECRET", "reason", "mail",
                        "parameters", Map.of("specId", plat.get("id").asText(),
                                "secretStagingId", secretStaged.get("id").asText()))), 201);
        assertThat(setOp.get("parameters").get("secretName").asText()).isEqualTo("MAIL_PASSWORD");
        assertThat(setOp.get("parameters").has("value")).isFalse();
        json(postAs(s.token(), "/api/v2/orgs/" + s.orgId() + "/operations/" + setOp.get("id").asText() + "/approve",
                Map.of("planHash", setOp.get("planHash").asText())), 200);
        drain();
        assertThat(operation(s, setOp.get("id").asText()).get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(engine.executed.get(engine.executed.size() - 1).stagingPayloads())
                .containsKey("secretStagingId");
    }
}
