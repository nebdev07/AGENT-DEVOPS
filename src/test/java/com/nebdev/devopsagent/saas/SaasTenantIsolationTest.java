package com.nebdev.devopsagent.saas;

import com.fasterxml.jackson.databind.JsonNode;
import com.nebdev.devopsagent.saas.server.ServerService;
import com.nebdev.devopsagent.saas.shared.RlsContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Directive §5 / §17 : aucune organisation ne peut lire, modifier ni administrer les ressources d'une autre —
 * ni par l'API (identifiants devinés), ni même par une requête oubliée sans filtre (RLS en base).
 */
class SaasTenantIsolationTest extends SaasTestSupport {

    @Autowired ServerService.Servers serverRepo;
    @Autowired RlsContext rls;
    @Autowired PlatformTransactionManager txManager;
    @PersistenceContext EntityManager em;

    @Test
    void anotherOrganizationIsInvisibleThroughTheApi() throws Exception {
        Session a = register("tenant-a");
        Session b = register("tenant-b");
        UUID serverA = readyServer(a, "10.1.0.1");
        JsonNode op = propose(a, serverA, "DISCOVER");
        String opA = op.get("id").asText();
        String orgA = "/api/v2/orgs/" + a.orgId();

        // B devine tous les identifiants de A : rien n'existe pour lui (404, pas 403 : pas de fuite d'existence).
        for (String url : List.of(orgA, orgA + "/servers", orgA + "/servers/" + serverA, orgA + "/operations",
                orgA + "/operations/" + opA, orgA + "/members", orgA + "/audit-events", orgA + "/billing",
                orgA + "/servers/" + serverA + "/credentials", orgA + "/managed/support-access")) {
            assertThat(getAs(b.token(), url).getResponse().getStatus()).as(url).isEqualTo(404);
        }
        assertThat(postAs(b.token(), orgA + "/operations", Map.of("serverId", serverA.toString(), "type", "DISCOVER"))
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(postAs(b.token(), orgA + "/operations/" + opA + "/approve", Map.of("planHash", "x"))
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(deleteAs(b.token(), orgA + "/servers/" + serverA).getResponse().getStatus()).isEqualTo(404);

        // B utilise SON organisation mais l'identifiant de serveur de A : introuvable.
        String orgB = "/api/v2/orgs/" + b.orgId();
        assertThat(getAs(b.token(), orgB + "/servers/" + serverA).getResponse().getStatus()).isEqualTo(404);
        assertThat(postAs(b.token(), orgB + "/operations", Map.of("serverId", serverA.toString(), "type", "DISCOVER"))
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(getAs(b.token(), orgB + "/operations/" + opA).getResponse().getStatus()).isEqualTo(404);

        // La tentative est tracée dans l'audit de A (traçabilité des accès).
        JsonNode events = json(getAs(a.token(), orgA + "/audit-events?size=200"), 200);
        assertThat(events.get("items").toString()).contains("access.denied");
    }

    @Test
    void rowLevelSecurityBlocksAQueryThatForgetsTheTenantFilter() throws Exception {
        Session a = register("rls-a");
        Session b = register("rls-b");
        UUID serverA = readyServer(a, "10.2.0.1");
        TransactionTemplate tx = new TransactionTemplate(txManager);

        // Sans contexte : zéro ligne (fail-closed), même avec findAll() sans aucun filtre.
        Long none = tx.execute(s -> (long) serverRepo.findAll().size());
        assertThat(none).isZero();
        // Contexte de B : le serveur de A n'existe pas, même par identifiant direct.
        Boolean seenByB = tx.execute(s -> {
            rls.enterOrg(b.orgId());
            return serverRepo.findById(serverA).isPresent();
        });
        assertThat(seenByB).isFalse();
        // Contexte de A : visible.
        Boolean seenByA = tx.execute(s -> {
            rls.enterOrg(a.orgId());
            return serverRepo.findById(serverA).isPresent();
        });
        assertThat(seenByA).isTrue();
        // Écriture dans l'organisation d'un autre refusée par la base (WITH CHECK).
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            rls.enterOrg(b.orgId());
            em.createNativeQuery("insert into server (id, organization_id, name, host, ssh_user) values (:id, :org, 'x', 'h', 'u')")
                    .setParameter("id", UUID.randomUUID()).setParameter("org", a.orgId()).executeUpdate();
        })).satisfies(SaasTenantIsolationTest::insufficientPrivilege);
        // Mise à jour massive sans filtre depuis B : n'atteint pas A.
        Integer touched = tx.execute(s -> {
            rls.enterOrg(b.orgId());
            return em.createNativeQuery("update server set name = 'pwned'").executeUpdate();
        });
        assertThat(touched).isZero();
    }

    @Test
    void credentialsAreEncryptedAtRestAndNeverReturned() throws Exception {
        Session a = register("vault");
        UUID server = readyServer(a, "10.3.0.1");
        JsonNode creds = json(getAs(a.token(), "/api/v2/orgs/" + a.orgId() + "/servers/" + server + "/credentials"), 200);
        assertThat(creds.toString()).contains("ssh-rsa ").doesNotContain("PRIVATE KEY").doesNotContain("privateKey");
        TransactionTemplate tx = new TransactionTemplate(txManager);
        byte[] blob = tx.execute(s -> {
            rls.enterOrg(a.orgId());
            return (byte[]) em.createNativeQuery("select private_key_enc from server_credential where server_id = :s")
                    .setParameter("s", server).getSingleResult();
        });
        assertThat(new String(blob, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("PRIVATE KEY");
        assertThat(blob[0]).isEqualTo((byte) 1);
    }

    @Test
    void auditLogIsAppendOnlyEvenForTheApplication() throws Exception {
        Session a = register("append");
        TransactionTemplate tx = new TransactionTemplate(txManager);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            rls.enterOrg(a.orgId());
            em.createNativeQuery("update audit_event set action = 'forged'").executeUpdate();
        })).satisfies(SaasTenantIsolationTest::insufficientPrivilege);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            rls.enterOrg(a.orgId());
            em.createNativeQuery("delete from audit_event").executeUpdate();
        })).satisfies(SaasTenantIsolationTest::insufficientPrivilege);
        JsonNode verify = json(getAs(a.token(), "/api/v2/orgs/" + a.orgId() + "/audit-events/verify"), 200);
        assertThat(verify.get("valid").asBoolean()).isTrue();
        assertThat(verify.get("events").asLong()).isPositive();
    }

    /** SQLSTATE 42501 (insufficient_privilege) : RLS ou droit retiré, quel que soit la langue du serveur. */
    static void insufficientPrivilege(Throwable t) {
        Throwable c = t;
        while (c != null && !(c instanceof java.sql.SQLException)) {
            c = c.getCause();
        }
        assertThat(c).isNotNull();
        assertThat(((java.sql.SQLException) c).getSQLState()).isEqualTo("42501");
    }
}
