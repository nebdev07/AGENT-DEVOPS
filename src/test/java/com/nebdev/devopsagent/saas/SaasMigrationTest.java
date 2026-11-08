package com.nebdev.devopsagent.saas;

import com.fasterxml.jackson.databind.JsonNode;
import com.nebdev.devopsagent.saas.identity.IdentityRepositories;
import com.nebdev.devopsagent.saas.migration.PlatformImporter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Migration platform → SaaS (ADR-057) : essai à blanc, reprise réelle, fail-closed, idempotence. */
class SaasMigrationTest extends SaasTestSupport {

    @Autowired PlatformImporter importer;
    @Autowired IdentityRepositories.Users users;

    @Test
    void importsAccountsAndServersFailClosed() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String admin = "founder-" + tag + "@example.test";
        String ops = "ops-" + tag + "@example.test";
        String partial = "partial-" + tag + "@example.test";
        String hash = new BCryptPasswordEncoder(12).encode("Str0ng-Passphrase!") /* hachage de la plateforme, repris tel quel */;
        Path dir = Files.createTempDirectory("platform-state");
        Files.writeString(dir.resolve("roles.json"), mapper.writeValueAsString(List.of(
                Map.of("id", "SUPER_ADMIN", "label", "Admin", "permissions", List.of("platform.admin", "account.manage")),
                Map.of("id", "OPERATOR", "label", "Ops", "permissions", List.of("server.read", "server.change.install")))));
        Files.writeString(dir.resolve("accounts.json"), mapper.writeValueAsString(List.of(
                Map.of("id", "a1", "email", admin, "displayName", "Founder", "roleId", "SUPER_ADMIN", "active", true,
                        "createdAt", "2026-10-17T10:00:00Z", "passwordHash", hash),
                Map.of("id", "a2", "email", ops, "displayName", "Ops", "roleId", "OPERATOR", "active", true,
                        "createdAt", "2026-10-17T11:00:00Z", "passwordHash", hash),
                Map.of("id", "a3", "email", partial, "displayName", "Partial", "roleId", "OPERATOR", "active", true,
                        "createdAt", "2026-10-17T12:00:00Z", "passwordHash", hash))));
        Files.writeString(dir.resolve("server-grants.json"), mapper.writeValueAsString(List.of(
                Map.of("id", "g1", "accountId", "a2", "host", "198.51.100.91", "port", 22, "user", "ubuntu", "label", "lab"),
                Map.of("id", "g2", "accountId", "a2", "host", "198.51.100.92", "port", 22, "user", "ubuntu", "label", "arc"),
                Map.of("id", "g3", "accountId", "a3", "host", "198.51.100.92", "port", 22, "user", "ubuntu", "label", "arc"))));
        String org = "Migration " + tag;

        PlatformImporter.Report dry = importer.importFrom(new PlatformImporter.Options(dir.toString(), org, "XOF", "fr",
                false, null, "unused.json"));
        assertThat(dry.applied()).isFalse();
        assertThat(dry.users()).hasSize(2);
        assertThat(dry.servers()).hasSize(2);
        assertThat(dry.skipped()).singleElement().satisfies(s -> {
            assertThat(s.get("email")).isEqualTo(partial);
            assertThat(s.get("reason")).isEqualTo("restricted_to_some_servers");
        });
        assertThat(exists(admin)).as("essai à blanc : rien d'écrit").isFalse();

        PlatformImporter.Report applied = importer.importFrom(new PlatformImporter.Options(dir.toString(), org, "XOF", "fr",
                true, null, "unused.json"));
        assertThat(applied.applied()).isTrue();
        assertThat(exists(admin)).isTrue();
        assertThat(exists(partial)).as("fail-closed").isFalse();

        // Le mot de passe d'origine fonctionne ; le fondateur est propriétaire, l'opérateur responsable technique.
        Session founder = login(admin, applied.organizationId());
        JsonNode orgs = json(getAs(founder.token(), "/api/v2/orgs"), 200);
        assertThat(orgs.toString()).contains(org).contains("ORGANIZATION_OWNER");
        JsonNode servers = json(getAs(founder.token(), "/api/v2/orgs/" + applied.organizationId() + "/servers"), 200);
        assertThat(servers).hasSize(2);
        assertThat(servers.get(0).path("hostFingerprint").isMissingNode() || servers.get(0).get("hostFingerprint").isNull())
                .as("empreinte à confirmer à nouveau").isTrue();
        Session operator = login(ops, applied.organizationId());
        assertThat(json(getAs(operator.token(), "/api/v2/orgs"), 200).toString()).contains("TECHNICAL_MANAGER");

        PlatformImporter.Report again = importer.importFrom(new PlatformImporter.Options(dir.toString(), org, "XOF", "fr",
                true, null, "unused.json"));
        assertThat(again.organizationId()).as("idempotent").isEqualTo(applied.organizationId());
        assertThat(again.users()).allSatisfy(u -> assertThat(u.get("status")).isEqualTo("existing"));
        assertThat(json(getAs(founder.token(), "/api/v2/orgs/" + applied.organizationId() + "/servers"), 200)).hasSize(2);
    }

    private boolean exists(String email) {
        return Boolean.TRUE.equals(new TransactionTemplate(transactions).execute(s -> {
            rlsContext.enterPlatform();
            return users.findByEmail(email).isPresent();
        }));
    }
}
