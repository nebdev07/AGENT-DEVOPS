package com.nebdev.devopsagent.saas;

import com.fasterxml.jackson.databind.JsonNode;
import com.nebdev.devopsagent.saas.billing.ManualPaymentProvider;
import com.nebdev.devopsagent.saas.identity.IdentityRepositories;
import com.nebdev.devopsagent.saas.identity.PlatformStaff;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Jetons d'API, accès support INOVNE (Managed), facturation, IA et back-office. */
class SaasPlatformTest extends SaasTestSupport {

    @Autowired IdentityRepositories.Staff staffRepo;

    // ───────────── Jetons d'API (§12) ─────────────

    @Test
    void apiTokenIsScopedToItsOrganizationAndScopes() throws Exception {
        Session a = register("token-a");
        Session b = register("token-b");
        UUID server = readyServer(a, "10.30.0.1");
        JsonNode created = json(postAs(a.token(), "/api/v2/orgs/" + a.orgId() + "/api-tokens",
                Map.of("name", "ci", "scopes", Set.of("server.read", "operation.read"), "days", 30)), 201);
        String secret = created.get("secret").asText();
        assertThat(secret).startsWith("apt_");
        JsonNode list = json(getAs(a.token(), "/api/v2/orgs/" + a.orgId() + "/api-tokens"), 200);
        assertThat(list.toString()).doesNotContain(secret);

        assertThat(getAs(secret, "/api/v2/orgs/" + a.orgId() + "/servers").getResponse().getStatus()).isEqualTo(200);
        assertThat(postAs(secret, "/api/v2/orgs/" + a.orgId() + "/operations",
                Map.of("serverId", server.toString(), "type", "DISCOVER")).getResponse().getStatus()).isEqualTo(403);
        assertThat(getAs(secret, "/api/v2/orgs/" + b.orgId() + "/servers").getResponse().getStatus()).isEqualTo(404);
        assertThat(postAs(secret, "/api/v2/orgs/" + a.orgId() + "/api-tokens",
                Map.of("name", "x", "scopes", Set.of("server.read"), "days", 1)).getResponse().getStatus()).isEqualTo(403);
        assertThat(getAs(secret, "/api/v2/me").getResponse().getStatus()).isEqualTo(403);
        assertThat(json(postAs(a.token(), "/api/v2/orgs/" + a.orgId() + "/api-tokens",
                Map.of("name", "evil", "scopes", Set.of("member.manage"), "days", 1)), 400).get("code").asText())
                .isEqualTo("invalid_scope");

        json(deleteAs(a.token(), "/api/v2/orgs/" + a.orgId() + "/api-tokens/" + created.get("id").asText()), 204);
        assertThat(getAs(secret, "/api/v2/orgs/" + a.orgId() + "/servers").getResponse().getStatus()).isEqualTo(401);
    }

    // ───────────── Managed : accès support (§6, §11) ─────────────

    @Test
    void inovneStaffHasNoAccessWithoutAGrantAndOnlyWhatIsGranted() throws Exception {
        Session client = register("managed-client");
        UUID server = readyServer(client, "10.31.0.1");
        Session tech = register("inovne-tech");
        staffRepo.save(new PlatformStaff(tech.userId(), "PLATFORM_OPERATOR"));
        Session techAgain = withMfa(tech);
        String org = "/api/v2/orgs/" + client.orgId();

        assertThat(getAs(techAgain.token(), org + "/servers").getResponse().getStatus()).as("aucun accès implicite").isEqualTo(404);

        JsonNode grant = json(postAs(client.token(), org + "/managed/support-access",
                Map.of("staffEmail", tech.email(), "level", "READ", "hours", 2, "reason", "Ticket #42")), 201);
        assertThat(getAs(techAgain.token(), org + "/servers").getResponse().getStatus()).isEqualTo(200);
        assertThat(postAs(techAgain.token(), org + "/operations", Map.of("serverId", server.toString(), "type", "DISCOVER"))
                .getResponse().getStatus()).as("READ : pas d'action").isEqualTo(403);
        JsonNode mine = json(getAs(techAgain.token(), "/api/v2/admin/support-accesses"), 200);
        assertThat(mine.toString()).contains(client.orgId().toString());

        json(deleteAs(client.token(), org + "/managed/support-access/" + grant.get("id").asText()), 204);
        assertThat(getAs(techAgain.token(), org + "/servers").getResponse().getStatus()).as("révoqué").isEqualTo(404);

        json(postAs(client.token(), org + "/managed/support-access",
                Map.of("staffEmail", tech.email(), "level", "OPERATE", "hours", 1, "reason", "Maintenance")), 201);
        JsonNode op = json(postAs(techAgain.token(), org + "/operations",
                Map.of("serverId", server.toString(), "type", "DISCOVER")), 201);
        assertThat(op.get("origin").asText()).isEqualTo("MANAGED");
        // Le client voit l'intervention du technicien dans SON audit.
        JsonNode audit = json(getAs(client.token(), org + "/audit-events?size=200"), 200);
        assertThat(audit.toString()).contains("support.granted").contains("support.revoked").contains("STAFF");

        JsonNode req = json(postAs(client.token(), org + "/managed/interventions",
                Map.of("title", "Mettre à jour Docker", "priority", "HIGH", "serverId", server.toString())), 201);
        JsonNode upd = json(patchAs(techAgain.token(), org + "/managed/interventions/" + req.get("id").asText(),
                Map.of("status", "DONE", "report", "Analyse faite", "operationIds", List.of(op.get("id").asText()))), 200);
        assertThat(upd.get("status").asText()).isEqualTo("DONE");
        JsonNode interventions = json(getAs(client.token(), org + "/managed/interventions"), 200);
        assertThat(interventions.get(0).get("operationIds").get(0).asText()).isEqualTo(op.get("id").asText());
        // Le client ne peut pas se déclarer « fait » à la place du technicien.
        assertThat(patchAs(client.token(), org + "/managed/interventions/" + req.get("id").asText(),
                Map.of("status", "IN_PROGRESS")).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void staffRoleIsInertWithoutMfa() throws Exception {
        Session admin = register("admin-no-mfa");
        staffRepo.save(new PlatformStaff(admin.userId(), "PLATFORM_ADMIN"));
        assertThat(getAs(admin.token(), "/api/v2/admin/organizations").getResponse().getStatus())
                .as("rôle de personnel sans MFA : aucun pouvoir").isEqualTo(403);
        JsonNode me = json(getAs(admin.token(), "/api/v2/me"), 200);
        assertThat(me.path("staffRole").isMissingNode() || me.get("staffRole").isNull()).isTrue();
        assertThat(me.get("staffRolePendingMfa").asText()).isEqualTo("PLATFORM_ADMIN");

        withMfa(admin);
        assertThat(getAs(admin.token(), "/api/v2/admin/organizations").getResponse().getStatus()).isEqualTo(200);
        assertThat(json(getAs(admin.token(), "/api/v2/admin/staff"), 200).toString()).contains("\"mfaEnabled\":true");
    }

    @Test
    void adminBackOfficeIsStaffOnlyAndSeesMetadataOnly() throws Exception {
        Session user = register("not-admin");
        assertThat(getAs(user.token(), "/api/v2/admin/organizations").getResponse().getStatus()).isEqualTo(403);
        Session admin = register("platform-admin");
        staffRepo.save(new PlatformStaff(admin.userId(), "PLATFORM_ADMIN"));
        Session adminAgain = withMfa(admin);
        JsonNode orgs = json(getAs(adminAgain.token(), "/api/v2/admin/organizations"), 200);
        assertThat(orgs.toString()).contains(user.orgId().toString());
        assertThat(getAs(adminAgain.token(), "/api/v2/orgs/" + user.orgId() + "/servers").getResponse().getStatus())
                .as("même l'admin n'entre pas chez un client sans accès support").isEqualTo(404);
        JsonNode contract = json(postAs(adminAgain.token(), "/api/v2/admin/organizations/" + user.orgId() + "/managed-contracts",
                Map.of("serviceLevel", "BUSINESS", "responseTimeMinutes", 240)), 201);
        assertThat(contract.get("status").asText()).isEqualTo("ACTIVE");
        JsonNode org = json(getAs(user.token(), "/api/v2/orgs/" + user.orgId()), 200);
        assertThat(org.get("customerType").asText()).isEqualTo("MANAGED");
    }

    // ───────────── Facturation (§10) ─────────────

    @Test
    void quotaPaymentWebhookAndIdempotency() throws Exception {
        Session s = register("billing");
        String org = "/api/v2/orgs/" + s.orgId();
        json(postAs(s.token(), org + "/servers", Map.of("name", "1", "host", "10.40.0.1", "user", "ubuntu")), 201);
        json(postAs(s.token(), org + "/servers", Map.of("name", "2", "host", "10.40.0.2", "user", "ubuntu")), 201);
        JsonNode quota = json(postAs(s.token(), org + "/servers", Map.of("name", "3", "host", "10.40.0.3", "user", "ubuntu")), 402);
        assertThat(quota.get("code").asText()).isEqualTo("server_quota_reached");

        JsonNode invoice = json(postAs(s.token(), org + "/billing/change-plan", Map.of("plan", "TEAM", "period", "MONTH")), 200);
        assertThat(invoice.get("status").asText()).isEqualTo("OPEN");
        assertThat(invoice.get("currency").asText()).isEqualTo("EUR");
        assertThat(invoice.get("amountMinor").asLong()).isEqualTo(7500);

        String event = mapper.writeValueAsString(Map.of("id", "evt-" + UUID.randomUUID(), "type", "invoice.paid",
                "invoiceNumber", invoice.get("number").asText(), "providerRef", "bank-123"));
        String url = "/api/v2/public/payments/manual/webhook";
        assertThat(mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(event)
                .header("X-Autopilot-Signature", "t=1,v1=00")).andReturn().getResponse().getStatus()).isEqualTo(401);
        String t = String.valueOf(Instant.now().getEpochSecond());
        String sig = "t=" + t + ",v1=" + ManualPaymentProvider.sign(WEBHOOK_SECRET, t, event.getBytes(StandardCharsets.UTF_8));
        JsonNode first = json(mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(event)
                .header("X-Autopilot-Signature", sig)).andReturn(), 200);
        assertThat(first.get("result").asText()).isEqualTo("processed");
        JsonNode replay = json(mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(event)
                .header("X-Autopilot-Signature", sig)).andReturn(), 200);
        assertThat(replay.get("result").asText()).isEqualTo("duplicate");

        JsonNode billing = json(getAs(s.token(), org + "/billing"), 200);
        assertThat(billing.get("subscription").get("plan").asText()).isEqualTo("TEAM");
        assertThat(billing.get("subscription").get("status").asText()).isEqualTo("ACTIVE");
        json(postAs(s.token(), org + "/servers", Map.of("name", "3", "host", "10.40.0.3", "user", "ubuntu")), 201);
    }

    @Test
    void billingManagerCannotTouchServers() throws Exception {
        Session owner = register("billing-roles");
        String email = "billing-" + UUID.randomUUID().toString().substring(0, 6) + "@example.test";
        json(postAs(owner.token(), "/api/v2/orgs/" + owner.orgId() + "/invitations", Map.of("email", email, "role", "BILLING_MANAGER")), 201);
        String invitation = tokenFromMail(email, "inv");
        JsonNode r = json(postAs(null, "/api/v2/public/auth/invitations/accept",
                Map.of("token", invitation, "password", "Str0ng-Passphrase!")), 200);
        String token = r.get("token").asText();
        assertThat(getAs(token, "/api/v2/orgs/" + owner.orgId() + "/billing").getResponse().getStatus()).isEqualTo(200);
        assertThat(getAs(token, "/api/v2/orgs/" + owner.orgId() + "/servers").getResponse().getStatus()).isEqualTo(403);
        // Invitation à usage unique.
        assertThat(postAs(null, "/api/v2/public/auth/invitations/accept",
                Map.of("token", invitation, "password", "Str0ng-Passphrase!")).getResponse().getStatus()).isEqualTo(401);
    }

    // ───────────── IA (§9) ─────────────

    @Test
    void recommendationsAreValidatedAndOnlyBecomeDrafts() throws Exception {
        Session s = register("ai");
        UUID server = readyServer(s, "10.50.0.1");
        String org = "/api/v2/orgs/" + s.orgId();
        assertThat(json(postAs(s.token(), org + "/servers/" + server + "/recommendations", null), 422)
                .get("code").asText()).isEqualTo("discovery_required");
        discovered(s, server);
        JsonNode rec = json(postAs(s.token(), org + "/servers/" + server + "/recommendations?locale=fr", null), 200);
        assertThat(rec.get("provider").asText()).isEqualTo("rules");
        JsonNode items = rec.get("recommendations");
        assertThat(items.size()).isPositive();
        int actionable = -1;
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).get("operationType").isNull()) {
                actionable = i;
                break;
            }
        }
        assertThat(actionable).isGreaterThanOrEqualTo(0);
        JsonNode draft = json(postAs(s.token(), org + "/ai/interactions/" + rec.get("interactionId").asText()
                + "/recommendations/" + actionable + "/draft", null), 201);
        assertThat(draft.get("status").asText()).isEqualTo("DRAFT");
        assertThat(draft.get("origin").asText()).isEqualTo("AI_SUGGESTION");
        drain();
        assertThat(operation(s, draft.get("id").asText()).get("status").asText()).as("un brouillon ne s'exécute jamais").isEqualTo("DRAFT");
        assertThat(postAs(s.token(), org + "/operations/" + draft.get("id").asText() + "/approve",
                Map.of("planHash", draft.get("planHash").asText())).getResponse().getStatus()).isEqualTo(409);
        JsonNode proposed = json(postAs(s.token(), org + "/operations/" + draft.get("id").asText() + "/submit", null), 201);
        assertThat(proposed.get("status").asText()).isEqualTo("PROPOSED");
        assertThat(proposed.get("origin").asText()).isEqualTo("USER");
    }
}
