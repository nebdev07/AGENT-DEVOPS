package com.nebdev.devopsagent.saas.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nebdev.devopsagent.saas.credential.CredentialService;
import com.nebdev.devopsagent.saas.identity.AuthService;
import com.nebdev.devopsagent.saas.identity.IdentityRepositories;
import com.nebdev.devopsagent.saas.identity.UserAccount;
import com.nebdev.devopsagent.saas.organization.OrgPermissions;
import com.nebdev.devopsagent.saas.organization.Organization;
import com.nebdev.devopsagent.saas.organization.OrganizationRepositories;
import com.nebdev.devopsagent.saas.organization.OrganizationService;
import com.nebdev.devopsagent.saas.server.Server;
import com.nebdev.devopsagent.saas.server.ServerService;
import com.nebdev.devopsagent.saas.shared.RlsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Migration des données du profil {@code platform} (stores JSON) vers le SaaS (ADR-057). Une seule organisation
 * reçoit les comptes et les serveurs de l'ancienne instance.
 *
 * <ul>
 *   <li>Comptes : hachage BCrypt repris tel quel (mot de passe inchangé, jamais connu). Inactif ⇒ DISABLED.</li>
 *   <li>Rôles : {@code platform.admin}/{@code account.manage} ⇒ propriétaire ; droits {@code server.change.*} ⇒
 *       responsable technique ; sinon lecteur.</li>
 *   <li><b>Fail-closed</b> : un compte non administrateur limité à <i>certains</i> serveurs n'est pas repris (les rôles
 *       SaaS couvrent toute l'organisation : l'importer élargirait ses droits). Il figure dans le rapport.</li>
 *   <li>Serveurs : depuis les rattachements ; empreinte d'hôte <b>non</b> reprise (MD5 dans l'ancien format) : à
 *       confirmer à nouveau. Clé SSH de l'instance reprise si {@code ssh-private-key-file} est fourni.</li>
 * </ul>
 *
 * <p>Par défaut : <b>essai à blanc</b> — tout est écrit puis annulé (contraintes de la base réellement vérifiées) et un
 * rapport sans secret est produit. {@code AUTOPILOT_MIGRATION_APPLY=true} pour valider. Idempotent : relancer ne
 * duplique rien.
 */
@Component
@Order(100)
public class PlatformImporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlatformImporter.class);

    public record Options(String stateDir, String organizationName, String currency, String locale, boolean apply,
                          String sshPrivateKeyFile, String reportFile) {}

    public record Report(boolean applied, String organization, UUID organizationId, List<Map<String, Object>> users,
                         List<Map<String, Object>> servers, List<Map<String, Object>> skipped, List<String> notes) {}

    private final Options options;
    private final AuthService auth;
    private final IdentityRepositories.Users users;
    private final OrganizationService organizations;
    private final OrganizationRepositories.Organizations orgs;
    private final ServerService servers;
    private final CredentialService credentials;
    private final RlsContext rls;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;

    public PlatformImporter(@Value("${autopilot.migration.platform-state-dir:}") String stateDir,
                            @Value("${autopilot.migration.organization-name:INOVNE}") String organizationName,
                            @Value("${autopilot.migration.currency:XOF}") String currency,
                            @Value("${autopilot.migration.locale:fr}") String locale,
                            @Value("${autopilot.migration.apply:false}") boolean apply,
                            @Value("${autopilot.migration.ssh-private-key-file:}") String sshPrivateKeyFile,
                            @Value("${autopilot.migration.report-file:saas-import-report.json}") String reportFile,
                            AuthService auth, IdentityRepositories.Users users, OrganizationService organizations,
                            OrganizationRepositories.Organizations orgs, ServerService servers,
                            CredentialService credentials, RlsContext rls, PlatformTransactionManager txManager,
                            ObjectMapper mapper) {
        this.options = new Options(stateDir, organizationName, currency, locale, apply, sshPrivateKeyFile, reportFile);
        this.auth = auth;
        this.users = users;
        this.organizations = organizations;
        this.orgs = orgs;
        this.servers = servers;
        this.credentials = credentials;
        this.rls = rls;
        this.tx = new TransactionTemplate(txManager);
        this.mapper = mapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (options.stateDir() == null || options.stateDir().isBlank()) {
            return;
        }
        Report report = importFrom(options);
        Files.writeString(Path.of(options.reportFile()), mapper.writeValueAsString(report));
        log.warn("Migration platform → SaaS {} : {} compte(s), {} serveur(s), {} écarté(s). Rapport : {}",
                report.applied() ? "APPLIQUÉE" : "ESSAI À BLANC (rien n'est écrit)", report.users().size(),
                report.servers().size(), report.skipped().size(), Path.of(options.reportFile()).toAbsolutePath());
    }

    public Report importFrom(Options o) throws IOException {
        Path dir = Path.of(o.stateDir());
        JsonNode accounts = read(dir.resolve("accounts.json"));
        JsonNode roles = read(dir.resolve("roles.json"));
        JsonNode grants = Files.exists(dir.resolve("server-grants.json")) ? read(dir.resolve("server-grants.json"))
                : mapper.createArrayNode();
        String sshKey = o.sshPrivateKeyFile() == null || o.sshPrivateKeyFile().isBlank() ? null
                : Files.readString(Path.of(o.sshPrivateKeyFile()));

        Map<String, Set<String>> rolePermissions = new LinkedHashMap<>();
        roles.forEach(r -> {
            Set<String> p = new LinkedHashSet<>();
            r.path("permissions").forEach(x -> p.add(x.asText()));
            rolePermissions.put(r.path("id").asText(), p);
        });
        record Target(String host, int port, String user, String label) {
            String key() { return user + "@" + host + ":" + port; }
        }
        Map<String, Target> allServers = new LinkedHashMap<>();
        Map<String, Set<String>> grantsByAccount = new LinkedHashMap<>();
        grants.forEach(g -> {
            Target t = new Target(g.path("host").asText(), g.path("port").asInt(22), g.path("user").asText(),
                    g.path("label").asText(""));
            allServers.putIfAbsent(t.key(), t);
            grantsByAccount.computeIfAbsent(g.path("accountId").asText(), k -> new LinkedHashSet<>()).add(t.key());
        });

        List<JsonNode> ordered = new ArrayList<>();
        accounts.forEach(ordered::add);
        ordered.sort(Comparator.comparing(a -> a.path("createdAt").asText("")));

        List<Map<String, Object>> userRows = new ArrayList<>();
        List<Map<String, Object>> serverRows = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        UUID[] orgId = new UUID[1];

        tx.executeWithoutResult(status -> {
            rls.enterPlatform();
            // 1. Rôle cible de chaque compte (fail-closed).
            Map<JsonNode, String> target = new LinkedHashMap<>();
            for (JsonNode a : ordered) {
                String email = AuthService.normalizeEmail(a.path("email").asText());
                Set<String> perms = rolePermissions.getOrDefault(a.path("roleId").asText(), Set.of());
                String role = perms.contains("platform.admin") || perms.contains("account.manage") ? OrgPermissions.OWNER
                        : perms.stream().anyMatch(p -> p.startsWith("server.change.")) ? "TECHNICAL_MANAGER" : "VIEWER";
                Set<String> granted = grantsByAccount.getOrDefault(a.path("id").asText(), Set.of());
                if (!OrgPermissions.OWNER.equals(role) && !granted.containsAll(allServers.keySet())) {
                    skipped.add(row("email", email, "reason", "restricted_to_some_servers",
                            "detail", "Limité à " + granted.size() + "/" + allServers.size()
                                    + " serveur(s) : le rôle SaaS couvrirait toute l'organisation. À inviter à la main."));
                    continue;
                }
                if (a.path("passwordHash").asText("").isBlank()) {
                    skipped.add(row("email", email, "reason", "no_password", "detail", "Compte sans mot de passe."));
                    continue;
                }
                target.put(a, role);
            }
            Optional<JsonNode> firstOwner = target.entrySet().stream()
                    .filter(e -> OrgPermissions.OWNER.equals(e.getValue()) && e.getKey().path("active").asBoolean(true))
                    .map(Map.Entry::getKey).findFirst();
            if (firstOwner.isEmpty()) {
                throw new IllegalStateException("Aucun compte administrateur actif à reprendre : migration impossible.");
            }

            // 2. Comptes (repris ou existants par e-mail).
            Map<JsonNode, UserAccount> accountsByNode = new LinkedHashMap<>();
            for (Map.Entry<JsonNode, String> e : target.entrySet()) {
                JsonNode a = e.getKey();
                String email = AuthService.normalizeEmail(a.path("email").asText());
                Optional<UserAccount> existing = users.findByEmail(email);
                UserAccount u = existing.orElseGet(() -> auth.importUser(email, a.path("passwordHash").asText(),
                        a.path("displayName").asText(""), o.locale(), a.path("active").asBoolean(true)));
                accountsByNode.put(a, u);
                userRows.add(row("email", email, "role", e.getValue(), "status", existing.isPresent() ? "existing" : "imported",
                        "active", a.path("active").asBoolean(true)));
            }

            // 3. Organisation (reprise si déjà créée par une migration précédente).
            UserAccount owner = accountsByNode.get(firstOwner.get());
            Organization org = orgs.forUser(owner.getId()).stream().filter(x -> x.getName().equals(o.organizationName()))
                    .findFirst().orElseGet(() -> organizations.createForImport(owner, o.organizationName(), o.locale(), "UTC",
                            o.currency()));
            orgId[0] = org.getId();
            for (Map.Entry<JsonNode, String> e : target.entrySet()) {
                organizations.addMemberForImport(org.getId(), accountsByNode.get(e.getKey()).getId(), e.getValue());
            }

            // 4. Serveurs et clé.
            rls.enterOrg(org.getId());
            for (Target t : allServers.values()) {
                try {
                    Server s = servers.createForImport(org.getId(), t.label().isBlank() ? t.host() : t.label(), t.host(),
                            t.port(), t.user());
                    String key = null;
                    if (sshKey != null) {
                        key = credentials.importKeyForMigration(org.getId(), s, sshKey).getFingerprint();
                    }
                    serverRows.add(row("server", t.key(), "id", s.getId().toString(),
                            "credential", key == null ? "none (à générer ou importer)" : key,
                            "hostKey", "à confirmer (lire l'empreinte puis approuver)"));
                } catch (RuntimeException ex) {
                    skipped.add(row("server", t.key(), "reason", "server_refused", "detail", ex.getMessage()));
                }
            }
            if (!o.apply()) {
                status.setRollbackOnly();
            }
        });
        notes.add("Les sessions et liens magiques de l'ancienne plateforme ne sont pas repris : chacun se reconnecte.");
        notes.add("La MFA n'existait pas sur la plateforme : le personnel INOVNE doit l'activer (ADR-052).");
        notes.add("Journaux (state/journal) non repris dans l'audit chaîné : à archiver tels quels.");
        notes.add("Généré le " + Instant.now() + " ; aucun secret dans ce rapport.");
        return new Report(o.apply(), o.organizationName(), o.apply() ? orgId[0] : null, userRows, serverRows, skipped, notes);
    }

    private JsonNode read(Path p) throws IOException {
        if (!Files.exists(p)) {
            throw new IOException("Fichier attendu absent : " + p);
        }
        JsonNode n = mapper.readTree(Files.readString(p));
        if (!n.isArray()) {
            throw new IOException("Tableau JSON attendu : " + p);
        }
        return n;
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
