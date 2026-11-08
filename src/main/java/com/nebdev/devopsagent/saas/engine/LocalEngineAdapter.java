package com.nebdev.devopsagent.saas.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebdev.devopsagent.audit.AuditReport;
import com.nebdev.devopsagent.audit.AuditRules;
import com.nebdev.devopsagent.discovery.DiscoveryScript;
import com.nebdev.devopsagent.discovery.DiscoveryService;
import com.nebdev.devopsagent.discovery.model.ServerSnapshot;
import com.nebdev.devopsagent.saas.operation.EnginePort;
import com.nebdev.devopsagent.saas.operation.OperationType;
import com.nebdev.devopsagent.saas.shared.Hashing;
import com.nebdev.devopsagent.ssh.ExecutionScope;
import com.nebdev.devopsagent.ssh.HostKeyService;
import com.nebdev.devopsagent.ssh.SshConnector;
import com.nebdev.devopsagent.ssh.SshTarget;
import com.nebdev.devopsagent.stack.AppDeployPlan;
import com.nebdev.devopsagent.stack.AppDeployPlanService;
import com.nebdev.devopsagent.stack.AppDeployService;
import com.nebdev.devopsagent.stack.AppSpec;
import com.nebdev.devopsagent.stack.BackupService;
import com.nebdev.devopsagent.stack.GitDeployKeyStore;
import com.nebdev.devopsagent.stack.GitUrls;
import com.nebdev.devopsagent.stack.InstallPlan;
import com.nebdev.devopsagent.stack.InstallService;
import com.nebdev.devopsagent.stack.InstallPlanBuilder;
import com.nebdev.devopsagent.stack.PlatformDeployPlanService;
import com.nebdev.devopsagent.stack.PlatformDeployService;
import com.nebdev.devopsagent.stack.PlatformServiceSpec;
import com.nebdev.devopsagent.stack.PlatformSpec;
import com.nebdev.devopsagent.stack.PlatformRestoreService;
import com.nebdev.devopsagent.stack.PlatformSecretService;
import com.nebdev.devopsagent.stack.PlatformSqlService;
import com.nebdev.devopsagent.stack.RoleScript;
import com.nebdev.devopsagent.stack.ServerProfile;
import com.nebdev.devopsagent.stack.SqlConsolePolicy;
import com.nebdev.devopsagent.stack.StackIntentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.info.BuildProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Adaptateur in-process vers le moteur existant (discovery, rôles, sauvegardes). Toute connexion SSH passe par
 * {@link ExecutionScope} : clé du serveur, empreinte attendue, phrase de sauvegarde de l'organisation.
 *
 * <p>Chaque exécution CHANGE : analyse fraîche → plan recalculé → comparaison au plan approuvé → action → analyse
 * de vérification. Aucune commande n'est construite à partir d'un texte libre.
 */
@Component
public class LocalEngineAdapter implements EnginePort {

    private static final Logger log = LoggerFactory.getLogger(LocalEngineAdapter.class);
    static final Pattern OPENSSH_PUBLIC = Pattern.compile("^(ssh-rsa|ssh-ed25519|ecdsa-sha2-nistp(256|384|521)) [A-Za-z0-9+/=]{20,4096}( [A-Za-z0-9@._-]{0,100})?$");

    private final DiscoveryService discovery;
    private final InstallService install;
    private final BackupService backups;
    private final PlatformDeployService platformDeploy;
    private final PlatformDeployPlanService platformPlans;
    private final AppDeployService appDeploy;
    private final AppDeployPlanService appPlans;
    private final PlatformSqlService platformSql;
    private final PlatformRestoreService platformRestore;
    private final PlatformSecretService platformSecrets;
    private final SshConnector ssh;
    private final HostKeyService hostKeys;
    private final ObjectMapper mapper;
    private final String version;

    public LocalEngineAdapter(DiscoveryService discovery, InstallService install, BackupService backups,
                              PlatformDeployService platformDeploy, PlatformDeployPlanService platformPlans,
                              AppDeployService appDeploy, AppDeployPlanService appPlans, PlatformSqlService platformSql,
                              PlatformRestoreService platformRestore, PlatformSecretService platformSecrets,
                              SshConnector ssh, HostKeyService hostKeys, ObjectMapper mapper,
                              ObjectProvider<BuildProperties> build) {
        this.discovery = discovery;
        this.install = install;
        this.backups = backups;
        this.platformDeploy = platformDeploy;
        this.platformPlans = platformPlans;
        this.appDeploy = appDeploy;
        this.appPlans = appPlans;
        this.platformSql = platformSql;
        this.platformRestore = platformRestore;
        this.platformSecrets = platformSecrets;
        this.ssh = ssh;
        this.hostKeys = hostKeys;
        this.mapper = mapper;
        BuildProperties b = build.getIfAvailable();
        this.version = b == null ? "dev" : b.getVersion();
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public String scanHostKey(Target target) throws IOException {
        return hostKeys.scanSha256(target.host(), target.port());
    }

    // ───────────────────────────── Plans (déterministes) ─────────────────────────────

    @Override
    public Plan plan(PlanRequest req) {
        OperationType type = req.type();
        return switch (type) {
            case DISCOVER -> new Plan(type.name(), type.label(),
                    List.of("Connexion SSH avec la clé du serveur, empreinte d'hôte vérifiée",
                            "Script de lecture discovery.sh envoyé sur l'entrée standard (aucune écriture distante)",
                            "Snapshot + rapport d'audit enregistrés"),
                    List.of("Empreinte d'hôte approuvée", "Clé SSH active"),
                    List.of("Aucun changement sur le serveur"), "Sans objet (lecture seule)",
                    Hashing.sha256Hex(DiscoveryScript.load()), Map.of());
            case RUN_BACKUP -> backupPlan(req);
            case ROTATE_CREDENTIAL -> rotatePlan(req);
            case DEPLOY_PLATFORM -> deployPlatformPlan(req, toSnapshot(req.latestSnapshot()));
            case DEPLOY_APP -> deployAppPlan(req, toSnapshot(req.latestSnapshot()));
            case PLATFORM_SQL_READ, PLATFORM_SQL_WRITE -> sqlPlan(req);
            case RESTORE_PLATFORM -> restorePlan(req);
            case SET_PLATFORM_SECRET, UNSET_PLATFORM_SECRET -> secretPlan(req);
            case INSTALL_SECURITY, INSTALL_DOCKER, INSTALL_TRAEFIK, INSTALL_MARIADB, INSTALL_REDIS,
                 INSTALL_DEPLOY_USER, INSTALL_BACKUPS -> installPlan(req, toSnapshot(req.latestSnapshot()));
        };
    }

    private Plan installPlan(PlanRequest req, ServerSnapshot snap) {
        OperationType type = req.type();
        try {
            StackIntentStore.Intent intent = install.instanceIntent();
            ServerProfile profile = install.profileOf(req.target().name());
            InstallPlan.InstallAction action = InstallService.planComponent(type.component(), snap,
                    new LinkedHashSet<>(intent.enabled()), intent.details(), profile);
            switch (action.status()) {
                case SKIP -> throw new PlanRefused("already_present", action.reason());
                case REFUSED -> throw new PlanRefused("plan_refused", action.reason());
                case PLANNED -> throw new PlanRefused("prerequisite_missing", action.reason());
                default -> { }
            }
            String script = InstallService.scriptFor(type.component(), snap, intent.details(), profile);
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("component", type.component());
            facts.put("phase", action.phase());
            facts.put("profile", profile.toString());
            return new Plan(type.name(), action.label(), action.steps(),
                    List.of("Analyse récente du serveur", "Composant absent ou incomplet"),
                    List.of(action.reason() == null ? type.label() : action.reason()),
                    rollbackFor(type), Hashing.sha256Hex(script), facts);
        } catch (IOException e) {
            throw new PlanRefused("engine_unavailable", e.getMessage());
        }
    }

    private Plan backupPlan(PlanRequest req) {
        ServerSnapshot snap = toSnapshot(req.latestSnapshot());
        if (snap.backups() == null || !snap.backups().present()) {
            throw new PlanRefused("prerequisite_missing", "Le rôle de sauvegarde n'est pas installé sur ce serveur.");
        }
        return new Plan(OperationType.RUN_BACKUP.name(), OperationType.RUN_BACKUP.label(),
                List.of("Lancement de la sauvegarde chiffrée sur le serveur (tâche détachée)",
                        "Copie de l'archive vers le stockage de l'agent, vérification complète"),
                List.of("Rôle backups installé"), List.of("Nouvelle archive chiffrée, rotation des plus anciennes"),
                "Sans objet (n'écrase aucune donnée)", Hashing.sha256Hex("run-backup-v1"), Map.of());
    }

    private Plan rotatePlan(PlanRequest req) {
        String pub = req.pendingPublicKey();
        if (pub == null || !OPENSSH_PUBLIC.matcher(pub.strip()).matches()) {
            throw new PlanRefused("invalid_pending_key", "Clé publique en attente absente ou invalide.");
        }
        return new Plan(OperationType.ROTATE_CREDENTIAL.name(), OperationType.ROTATE_CREDENTIAL.label(),
                List.of("Ajout de la nouvelle clé publique dans ~/.ssh/authorized_keys (clé actuelle)",
                        "Connexion de contrôle avec la nouvelle clé",
                        "Retrait de l'ancienne clé (avec la nouvelle clé), nouvelle connexion de contrôle"),
                List.of("Clé active fonctionnelle", "Nouvelle clé générée (PENDING)"),
                List.of("Seule la nouvelle clé ouvre le compte de l'agent"),
                "Si la connexion de contrôle échoue, l'ancienne clé n'est pas retirée",
                Hashing.sha256Hex(rotateScript("KEY", "add")), Map.of("newKeySha256", Hashing.sha256Hex(pub.strip())));
    }

    private static String rollbackFor(OperationType type) {
        return switch (type) {
            case INSTALL_SECURITY -> "Drop-in SSH validé par sshd -t avant activation ; sinon rien n'est activé";
            case INSTALL_BACKUPS, RUN_BACKUP, DISCOVER -> "Sans objet (n'écrase aucune donnée)";
            case DEPLOY_PLATFORM, DEPLOY_APP ->
                    "Aucun rollback automatique vérifié : images/compose précédents peuvent rester ; restore-platform hors de cette opération";
            case PLATFORM_SQL_READ -> "Sans objet (lecture seule)";
            case PLATFORM_SQL_WRITE -> "Sauvegarde SQL préalable sur le VPS si le rôle l'écrit ; pas de rollback automatique garanti";
            case RESTORE_PLATFORM -> "Sauvegarde préalable de la base/fichiers sur le VPS avant remplacement ; rollback manuel possible";
            case SET_PLATFORM_SECRET, UNSET_PLATFORM_SECRET -> "Retirer ou reposer la variable ; redémarrage du service si demandé";
            case ROTATE_CREDENTIAL -> "Si la connexion de contrôle échoue, l'ancienne clé n'est pas retirée";
            case INSTALL_DOCKER, INSTALL_TRAEFIK, INSTALL_MARIADB, INSTALL_REDIS, INSTALL_DEPLOY_USER ->
                    "Aucun retour arrière automatique vérifié : rôle idempotent, réinstallation possible";
        };
    }

    private Plan restorePlan(PlanRequest req) {
        PlatformSpec platform = requirePlatformSpec(req.parameters());
        boolean sql = stringParam(req.parameters(), "sqlStagingId") != null;
        boolean uploads = stringParam(req.parameters(), "uploadsStagingId") != null;
        if (!sql && !uploads) {
            throw new PlanRefused("restore_staging_required", "Aucun fichier de restauration fourni.");
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("platformId", platform.id());
        facts.put("hasSql", sql);
        facts.put("hasUploads", uploads);
        if (sql) {
            facts.put("sqlSha256", stringParam(req.parameters(), "sqlSha256"));
            facts.put("sqlOriginalName", stringParam(req.parameters(), "sqlOriginalName"));
        }
        if (uploads) {
            facts.put("uploadsSha256", stringParam(req.parameters(), "uploadsSha256"));
            facts.put("uploadsOriginalName", stringParam(req.parameters(), "uploadsOriginalName"));
        }
        Object fk = req.parameters() == null ? null : req.parameters().get("foreignKeyChecks");
        facts.put("foreignKeyChecks", fk == null || Boolean.parseBoolean(String.valueOf(fk)));
        return new Plan(OperationType.RESTORE_PLATFORM.name(),
                OperationType.RESTORE_PLATFORM.label() + " « " + platform.id() + " »",
                List.of("Contrôle des empreintes des fichiers staging",
                        "Sauvegarde préalable sur le VPS",
                        "Import SQL et/ou extraction archive"),
                List.of("Plateforme déployée", "Fichiers staging valides"),
                List.of("Données remplacées selon les fichiers approuvés"),
                rollbackFor(OperationType.RESTORE_PLATFORM),
                Hashing.sha256Hex(String.valueOf(facts)), facts);
    }

    private Plan secretPlan(PlanRequest req) {
        PlatformSpec platform = requirePlatformSpec(req.parameters());
        String serviceId = stringParam(req.parameters(), "serviceId");
        String secretName = stringParam(req.parameters(), "secretName");
        if (serviceId == null || secretName == null) {
            throw new PlanRefused("secret_target_required", "Service et nom de variable requis.");
        }
        boolean set = req.type() == OperationType.SET_PLATFORM_SECRET;
        if (set && stringParam(req.parameters(), "secretStagingId") == null) {
            throw new PlanRefused("secret_staging_required", "Staging secret manquant.");
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("platformId", platform.id());
        facts.put("serviceId", serviceId);
        facts.put("secretName", secretName);
        facts.put("mode", set ? "SET" : "UNSET");
        if (set) {
            facts.put("secretSha256", stringParam(req.parameters(), "secretSha256"));
        }
        Object restart = req.parameters() == null ? null : req.parameters().get("restart");
        facts.put("restart", restart == null || Boolean.parseBoolean(String.valueOf(restart)));
        return new Plan(req.type().name(), req.type().label() + " « " + secretName + " »",
                List.of(set ? "Écriture dans svc-<service>.local.env sur le VPS" : "Retrait de la variable sur le VPS",
                        Boolean.TRUE.equals(facts.get("restart")) ? "Redémarrage du service" : "Sans redémarrage"),
                List.of("Plateforme déployée", "Service connu dans la fiche"),
                List.of("Valeur jamais renvoyée à l'API"),
                rollbackFor(req.type()), Hashing.sha256Hex(String.valueOf(facts)), facts);
    }

    private Plan sqlPlan(PlanRequest req) {
        PlatformSpec platform = requirePlatformSpec(req.parameters());
        String sql = stringParam(req.parameters(), "sql");
        if (sql == null) {
            throw new PlanRefused("sql_required", "Requête SQL absente.");
        }
        SqlConsolePolicy.Analysis analysis = SqlConsolePolicy.analyze(sql);
        if (req.type() == OperationType.PLATFORM_SQL_READ && analysis.kind() != SqlConsolePolicy.Kind.READ) {
            throw new PlanRefused("sql_not_read_only", "Cette opération n'accepte que du SQL en lecture.");
        }
        if (req.type() == OperationType.PLATFORM_SQL_WRITE && analysis.kind() != SqlConsolePolicy.Kind.WRITE) {
            throw new PlanRefused("sql_not_write", "Cette opération exige du SQL d'écriture.");
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("platformId", platform.id());
        facts.put("sqlKind", analysis.kind().name());
        facts.put("sqlSha256", Hashing.sha256Hex(sql));
        facts.put("statementCount", analysis.statements().size());
        return new Plan(req.type().name(), req.type().label() + " « " + platform.id() + " »",
                List.of("Contrôle SqlConsolePolicy (" + analysis.kind() + ")",
                        "Exécution dans le conteneur MariaDB de la plateforme (droits limités)",
                        analysis.kind() == SqlConsolePolicy.Kind.WRITE
                                ? "Sauvegarde préalable de la base si le rôle l'active" : "Aucune écriture"),
                List.of("Plateforme déployée avec MariaDB"),
                List.of(analysis.kind() == SqlConsolePolicy.Kind.WRITE ? "Données modifiées selon le SQL approuvé"
                        : "Résultat de lecture seulement"),
                rollbackFor(req.type()), Hashing.sha256Hex(sql), facts);
    }

    private Plan deployPlatformPlan(PlanRequest req, ServerSnapshot snap) {
        PlatformSpec platform = requirePlatformSpec(req.parameters());
        String serviceId = stringParam(req.parameters(), "serviceId");
        try {
            ServerProfile profile = install.profileOf(req.target().name());
            assertDeployPrerequisites(snap, profile, platform, serviceId);
            AppDeployPlan plan = platformPlans.plan(platform, serviceId, false);
            if (!plan.canProceed()) {
                throw new PlanRefused("plan_refused", plan.blocker());
            }
            String script = RoleScript.loadPlatform(platform, Map.of(), profile, serviceId);
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("slug", platform.id());
            facts.put("serviceId", serviceId);
            facts.put("specSha256", Hashing.canonicalHash(platform));
            return new Plan(OperationType.DEPLOY_PLATFORM.name(), OperationType.DEPLOY_PLATFORM.label() + " « "
                    + platform.id() + " »",
                    plan.actions(), List.of("Analyse récente", "Pile Docker/Traefik/deploy présente"),
                    List.of("Services publiés via Traefik"), rollbackFor(OperationType.DEPLOY_PLATFORM),
                    Hashing.sha256Hex(script), facts);
        } catch (PlanRefused e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new PlanRefused("engine_unavailable", e.getMessage());
        }
    }

    private Plan deployAppPlan(PlanRequest req, ServerSnapshot snap) {
        AppSpec app = requireAppSpec(req.parameters());
        assertDeployStack(snap);
        try {
            AppDeployPlan plan = appPlans.planStandalone(app);
            if (!plan.canProceed()) {
                throw new PlanRefused("plan_refused", plan.blocker());
            }
            String script = RoleScript.loadApp(app, null, null);
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("slug", app.id());
            facts.put("hostname", app.hostname());
            facts.put("specSha256", Hashing.canonicalHash(app));
            return new Plan(OperationType.DEPLOY_APP.name(),
                    OperationType.DEPLOY_APP.label() + " « " + app.id() + " »",
                    plan.actions(), List.of("Analyse récente", "Pile Docker/Traefik/deploy présente"),
                    List.of("Application publiée sur https://" + app.hostname()),
                    rollbackFor(OperationType.DEPLOY_APP), Hashing.sha256Hex(script), facts);
        } catch (PlanRefused e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new PlanRefused("engine_unavailable", e.getMessage());
        }
    }

    // ───────────────────────────── Exécution ─────────────────────────────

    @Override
    public Result execute(ExecutionRequest req) {
        Credentials c = req.credentials();
        SshTarget target = new SshTarget(req.target().host(), req.target().port(), req.target().user());
        ExecutionScope.Credentials scope = new ExecutionScope.Credentials(c.privatePem(), c.hostFingerprint(),
                c.backupPassphrase());
        try {
            return ExecutionScope.run(scope, () -> switch (req.type()) {
                case DISCOVER -> discover(target, null);
                case RUN_BACKUP -> runBackup(req, target);
                case ROTATE_CREDENTIAL -> rotate(req, target);
                case DEPLOY_PLATFORM -> runDeployPlatform(req, target);
                case DEPLOY_APP -> runDeployApp(req, target);
                case PLATFORM_SQL_READ, PLATFORM_SQL_WRITE -> runSql(req, target);
                case RESTORE_PLATFORM -> runRestore(req, target);
                case SET_PLATFORM_SECRET -> runSetSecret(req, target);
                case UNSET_PLATFORM_SECRET -> runUnsetSecret(req, target);
                case INSTALL_SECURITY, INSTALL_DOCKER, INSTALL_TRAEFIK, INSTALL_MARIADB, INSTALL_REDIS,
                     INSTALL_DEPLOY_USER, INSTALL_BACKUPS -> runInstall(req, target);
            });
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.info("Exécution {} sur {} échouée : {}", req.type(), target.host(), msg);
            return new Result(false, exitCodeOf(msg), msg, classify(msg), Map.of(), null, false);
        }
    }

    private Result discover(SshTarget target, String output) throws IOException {
        DiscoveryService.DiscoveryResult r = discovery.discoverRemote(target);
        Observation obs = observe(r.snapshot());
        Map<String, Object> verification = new LinkedHashMap<>();
        verification.put("snapshotCapturedAt", String.valueOf(r.snapshot().capturedAt()));
        verification.put("auditGrade", obs.auditGrade());
        verification.put("auditScore", obs.auditScore());
        return new Result(true, 0, output == null ? r.report() : output, null, verification, obs, false);
    }

    private Result runInstall(ExecutionRequest req, SshTarget target) throws IOException {
        // 1. État frais, 2. plan recalculé à l'identique, 3. comparaison au plan approuvé.
        ServerSnapshot fresh = discovery.discoverRemote(target).snapshot();
        Plan replanned;
        try {
            replanned = installPlan(new PlanRequest(req.type(), req.target(), req.parameters(),
                    mapper.convertValue(fresh, new TypeReference<Map<String, Object>>() {}), null), fresh);
        } catch (PlanRefused e) {
            return new Result(false, null, e.getMessage(), "plan_changed: " + e.code(), Map.of(), observe(fresh), false);
        }
        if (!req.planStillApproved().test(replanned)) {
            return new Result(false, null, "", "plan_changed", Map.of(), observe(fresh), false);
        }
        StackIntentStore.Intent intent = install.instanceIntent();
        LinkedHashSet<String> wanted = new LinkedHashSet<>(intent.enabled());
        wanted.add(req.type().component());
        InstallService.ApplyResult applied = install.applyResolved(req.type().component(), target, fresh, wanted,
                intent.details(), install.profileOf(req.target().name()));
        ServerSnapshot after = applied.discovery().snapshot();
        Map<String, Object> verification = new LinkedHashMap<>(verify(req.type(), after));
        applied.extras().forEach((k, v) -> verification.put("extra." + k, String.valueOf(v)));
        boolean ok = Boolean.TRUE.equals(verification.get("componentPresent"));
        return new Result(ok, 0, applied.discovery().report(), ok ? null : "verification_failed", verification,
                observe(after), false);
    }

    private Result runSql(ExecutionRequest req, SshTarget target) throws IOException {
        Plan replanned = sqlPlan(new PlanRequest(req.type(), req.target(), req.parameters(), null, null));
        if (!req.planStillApproved().test(replanned)) {
            return new Result(false, null, "", "plan_changed", Map.of(), null, false);
        }
        PlatformSpec platform = requirePlatformSpec(req.parameters());
        String sql = stringParam(req.parameters(), "sql");
        PlatformSqlService.SqlResult r = platformSql.runResolved(platform, target, sql);
        Map<String, Object> verification = new LinkedHashMap<>();
        verification.put("platformId", r.platformId());
        verification.put("database", r.database());
        verification.put("backup", r.backup());
        verification.put("risk", r.risk());
        verification.put("statementCount", r.statements() == null ? 0 : r.statements().size());
        verification.put("outputTruncated", r.outputTruncated());
        boolean ok = r.error() == null || r.error().isBlank();
        return new Result(ok, ok ? 0 : 1, ok ? "SQL_DONE" : r.error(), ok ? null : "sql_failed", verification, null, false);
    }

    private Result runRestore(ExecutionRequest req, SshTarget target) throws IOException {
        Plan replanned = restorePlan(new PlanRequest(req.type(), req.target(), req.parameters(), null, null));
        if (!req.planStillApproved().test(replanned)) {
            return new Result(false, null, "", "plan_changed", Map.of(), null, false);
        }
        PlatformSpec platform = requirePlatformSpec(req.parameters());
        PlatformRestoreService.RestoreFile sql = materializeRestore(req, "sqlStagingId");
        PlatformRestoreService.RestoreFile uploads = materializeRestore(req, "uploadsStagingId");
        Object fk = req.parameters() == null ? null : req.parameters().get("foreignKeyChecks");
        boolean foreignKeyChecks = fk == null || Boolean.parseBoolean(String.valueOf(fk));
        String subdir = stringParam(req.parameters(), "uploadsSubdir");
        try {
            PlatformRestoreService.RestoreResult r = platformRestore.restoreResolved(platform, target, sql, uploads,
                    subdir, foreignKeyChecks);
            Map<String, Object> verification = new LinkedHashMap<>();
            verification.put("platformId", r.platformId());
            verification.put("report", r.report());
            return new Result(true, 0, "PLATFORM_RESTORED", null, verification, null, false);
        } finally {
            deleteQuietly(sql);
            deleteQuietly(uploads);
        }
    }

    private Result runSetSecret(ExecutionRequest req, SshTarget target) throws IOException {
        Plan replanned = secretPlan(new PlanRequest(req.type(), req.target(), req.parameters(), null, null));
        if (!req.planStillApproved().test(replanned)) {
            return new Result(false, null, "", "plan_changed", Map.of(), null, false);
        }
        PlatformSpec platform = requirePlatformSpec(req.parameters());
        StagingPayload payload = req.stagingPayloads().get("secretStagingId");
        if (payload == null || payload.content() == null) {
            return new Result(false, null, "", "secret_staging_missing", Map.of(), null, false);
        }
        String value = new String(payload.content(), StandardCharsets.UTF_8);
        String serviceId = stringParam(req.parameters(), "serviceId");
        String name = stringParam(req.parameters(), "secretName");
        boolean restart = boolParam(req.parameters(), "restart", true);
        PlatformSecretService.Outcome o = platformSecrets.setResolved(platform, serviceId, name, value, restart, target);
        Map<String, Object> verification = new LinkedHashMap<>();
        verification.put("platformId", o.platformId());
        verification.put("service", o.service());
        verification.put("name", o.name());
        verification.put("action", o.action());
        verification.put("restart", o.restart());
        return new Result(true, 0, "SECRET_DONE", null, verification, null, false);
    }

    private Result runUnsetSecret(ExecutionRequest req, SshTarget target) throws IOException {
        Plan replanned = secretPlan(new PlanRequest(req.type(), req.target(), req.parameters(), null, null));
        if (!req.planStillApproved().test(replanned)) {
            return new Result(false, null, "", "plan_changed", Map.of(), null, false);
        }
        PlatformSpec platform = requirePlatformSpec(req.parameters());
        String serviceId = stringParam(req.parameters(), "serviceId");
        String name = stringParam(req.parameters(), "secretName");
        boolean restart = boolParam(req.parameters(), "restart", true);
        PlatformSecretService.Outcome o = platformSecrets.unsetResolved(platform, serviceId, name, restart, target);
        Map<String, Object> verification = new LinkedHashMap<>();
        verification.put("platformId", o.platformId());
        verification.put("service", o.service());
        verification.put("name", o.name());
        verification.put("action", o.action());
        verification.put("restart", o.restart());
        return new Result(true, 0, "SECRET_DONE", null, verification, null, false);
    }

    private PlatformRestoreService.RestoreFile materializeRestore(ExecutionRequest req, String key) throws IOException {
        StagingPayload payload = req.stagingPayloads().get(key);
        if (payload == null || payload.content() == null) {
            return null;
        }
        Path tmp = Files.createTempFile("autopilot-saas-restore-", ".bin");
        Files.write(tmp, payload.content());
        return new PlatformRestoreService.RestoreFile(tmp, payload.originalName());
    }

    private static void deleteQuietly(PlatformRestoreService.RestoreFile file) {
        if (file == null || file.path() == null) {
            return;
        }
        try {
            Files.deleteIfExists(file.path());
        } catch (IOException ignored) {
            // best-effort
        }
    }

    private static boolean boolParam(Map<String, Object> parameters, String key, boolean defaultValue) {
        if (parameters == null || parameters.get(key) == null) {
            return defaultValue;
        }
        Object v = parameters.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(v));
    }

    private Result runDeployPlatform(ExecutionRequest req, SshTarget target) throws IOException {
        ServerSnapshot fresh = discovery.discoverRemote(target).snapshot();
        Plan replanned;
        try {
            replanned = deployPlatformPlan(new PlanRequest(req.type(), req.target(), req.parameters(),
                    mapper.convertValue(fresh, new TypeReference<Map<String, Object>>() {}), null), fresh);
        } catch (PlanRefused e) {
            return new Result(false, null, e.getMessage(), "plan_changed: " + e.code(), Map.of(), observe(fresh), false);
        }
        if (!req.planStillApproved().test(replanned)) {
            return new Result(false, null, "", "plan_changed", Map.of(), observe(fresh), false);
        }
        PlatformSpec platform = requirePlatformSpec(req.parameters());
        String serviceId = stringParam(req.parameters(), "serviceId");
        ServerProfile profile = install.profileOf(req.target().name());
        PlatformDeployService.DeployOutcome outcome = platformDeploy.deployResolved(platform, fresh, target, profile,
                serviceId, req.gitDeployKeysBase64(), progress -> {});
        ServerSnapshot after = outcome.discovery().snapshot();
        Map<String, Object> verification = new LinkedHashMap<>();
        verification.put("platformId", platform.id());
        verification.put("serviceId", serviceId);
        verification.put("deployed", true);
        verification.put("snapshotCapturedAt", String.valueOf(after.capturedAt()));
        return new Result(true, 0, outcome.rawLog(), null, verification, observe(after), false);
    }

    private Result runDeployApp(ExecutionRequest req, SshTarget target) throws IOException {
        ServerSnapshot fresh = discovery.discoverRemote(target).snapshot();
        Plan replanned;
        try {
            replanned = deployAppPlan(new PlanRequest(req.type(), req.target(), req.parameters(),
                    mapper.convertValue(fresh, new TypeReference<Map<String, Object>>() {}), null), fresh);
        } catch (PlanRefused e) {
            return new Result(false, null, e.getMessage(), "plan_changed: " + e.code(), Map.of(), observe(fresh), false);
        }
        if (!req.planStillApproved().test(replanned)) {
            return new Result(false, null, "", "plan_changed", Map.of(), observe(fresh), false);
        }
        AppSpec app = requireAppSpec(req.parameters());
        AppDeployPlan plan = appPlans.planStandalone(app);
        String gitKey = null;
        if (GitUrls.ssh(app.gitUrl())) {
            gitKey = req.gitDeployKeysBase64().get(GitDeployKeyStore.keyId(app.gitUrl()));
            if (gitKey == null) {
                return new Result(false, null, "", "git_credential_missing", Map.of(), observe(fresh), false);
            }
        }
        var discoveryResult = appDeploy.deployResolved(app, fresh, target, plan, true, gitKey);
        ServerSnapshot after = discoveryResult.snapshot();
        Map<String, Object> verification = new LinkedHashMap<>();
        verification.put("appId", app.id());
        verification.put("hostname", app.hostname());
        verification.put("deployed", true);
        verification.put("snapshotCapturedAt", String.valueOf(after.capturedAt()));
        return new Result(true, 0, discoveryResult.report(), null, verification, observe(after), false);
    }

    private Result runBackup(ExecutionRequest req, SshTarget target) throws IOException {
        ServerSnapshot fresh = discovery.discoverRemote(target).snapshot();
        Plan replanned;
        try {
            replanned = backupPlan(new PlanRequest(req.type(), req.target(), req.parameters(),
                    mapper.convertValue(fresh, new TypeReference<Map<String, Object>>() {}), null));
        } catch (PlanRefused e) {
            return new Result(false, null, e.getMessage(), "plan_changed: " + e.code(), Map.of(), observe(fresh), false);
        }
        if (!req.planStillApproved().test(replanned)) {
            return new Result(false, null, "", "plan_changed", Map.of(), observe(fresh), false);
        }
        BackupService.LocalBackup b = backups.runNow(target, true);
        Map<String, Object> verification = new LinkedHashMap<>();
        verification.put("file", b.file());
        verification.put("sha256", b.sha256());
        boolean ok = b.check() != null && b.check().ok();
        verification.put("verified", ok);
        if (b.check() != null && b.check().problem() != null) {
            verification.put("problem", b.check().problem());
        }
        return new Result(ok, 0, "Sauvegarde " + b.file(), ok ? null : "backup_verification_failed", verification,
                null, false);
    }

    private Result rotate(ExecutionRequest req, SshTarget target) throws Exception {
        Credentials c = req.credentials();
        String newPub = c.pendingPublicKey().strip();
        String oldBlob = c.activePublicKey().strip().split("\\s+")[1];
        Plan replanned = rotatePlan(new PlanRequest(req.type(), req.target(), req.parameters(), null, newPub));
        if (!req.planStillApproved().test(replanned)) {
            return new Result(false, null, "", "plan_changed", Map.of(), null, false);
        }
        StringBuilder out = new StringBuilder();
        // 1. Ajout avec la clé actuelle.
        out.append(ssh.runStdinScript(rotateScript(newPub, "add"), target, 60));
        if (!out.toString().contains("ROTATE_ADDED")) {
            return new Result(false, null, out.toString(), "rotate_add_failed", Map.of(), null, false);
        }
        // 2. Contrôle et retrait de l'ancienne, avec la nouvelle clé uniquement.
        ExecutionScope.Credentials withNew = new ExecutionScope.Credentials(c.pendingPrivatePem(), c.hostFingerprint(),
                c.backupPassphrase());
        String removed = ExecutionScope.run(withNew, () -> ssh.runStdinScript(rotateScript(oldBlob, "remove"), target, 60));
        out.append(removed);
        if (!removed.contains("ROTATE_REMOVED")) {
            return new Result(false, null, out.toString(), "rotate_remove_failed", Map.of(), null, false);
        }
        String check = ExecutionScope.run(withNew, () -> ssh.runStdinScript("echo ROTATE_OK\n", target, 30));
        boolean ok = check.contains("ROTATE_OK");
        return new Result(ok, 0, out.append(check).toString(), ok ? null : "rotate_check_failed",
                Map.of("newKeyLogin", ok, "oldKeyRemoved", true), null, ok);
    }

    /** Script figé : la valeur n'est jamais interprétée par le shell (heredoc entre apostrophes, motif fixe). */
    static String rotateScript(String value, String mode) {
        if (!value.matches("[A-Za-z0-9+/=@._ -]{1,8192}")) {
            throw new IllegalArgumentException("Valeur de clé refusée.");
        }
        String body = """
                set -eu
                umask 077
                mkdir -p "$HOME/.ssh"
                touch "$HOME/.ssh/authorized_keys"
                chmod 700 "$HOME/.ssh"
                chmod 600 "$HOME/.ssh/authorized_keys"
                VALUE=$(cat <<'AUTOPILOT_KEY'
                %s
                AUTOPILOT_KEY
                )
                """.formatted(value);
        if ("add".equals(mode)) {
            return body + """
                    if ! grep -qxF "$VALUE" "$HOME/.ssh/authorized_keys"; then printf '%s\\n' "$VALUE" >> "$HOME/.ssh/authorized_keys"; fi
                    echo ROTATE_ADDED
                    """;
        }
        return body + """
                TMP=$(mktemp "$HOME/.ssh/ak.XXXXXX")
                grep -vF "$VALUE" "$HOME/.ssh/authorized_keys" > "$TMP" || true
                if [ ! -s "$TMP" ]; then rm -f "$TMP"; echo ROTATE_WOULD_EMPTY; exit 3; fi
                chmod 600 "$TMP"
                mv "$TMP" "$HOME/.ssh/authorized_keys"
                echo ROTATE_REMOVED
                """;
    }

    @Override
    public Summary summarize(Map<String, Object> payload) {
        ServerSnapshot snap = toSnapshot(payload);
        AuditReport report = AuditRules.evaluate(snap);
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("osFamily", snap.osFamily());
        facts.put("osVersion", snap.osVersion());
        facts.put("auditGrade", report.grade());
        facts.put("auditScore", report.score());
        facts.put("listeningPortCount", snap.listeningPorts() == null ? 0 : snap.listeningPorts().size());
        List<Map<String, String>> findings = new java.util.ArrayList<>();
        for (AuditReport.Finding f : report.findings()) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("severity", f.severity().name());
            m.put("category", f.category());
            m.put("title", f.title());
            m.put("recommendation", f.recommendation());
            findings.add(m);
        }
        return new Summary(facts, findings);
    }

    // ───────────────────────────── Outils ─────────────────────────────

    private Map<String, Object> verify(OperationType type, ServerSnapshot after) {
        Map<String, Object> v = new LinkedHashMap<>();
        boolean present = switch (type) {
            case INSTALL_SECURITY -> InstallService_securityPresent(after);
            case INSTALL_DOCKER -> after.docker() != null && after.docker().present();
            case INSTALL_TRAEFIK -> after.traefik() != null && after.traefik().present();
            case INSTALL_MARIADB -> after.mariadb() != null && after.mariadb().present();
            case INSTALL_REDIS -> after.redis() != null && after.redis().present();
            case INSTALL_DEPLOY_USER -> after.deploy() != null && after.deploy().present();
            case INSTALL_BACKUPS -> after.backups() != null && after.backups().present();
            case DISCOVER, RUN_BACKUP, ROTATE_CREDENTIAL, DEPLOY_PLATFORM, DEPLOY_APP,
                 PLATFORM_SQL_READ, PLATFORM_SQL_WRITE, RESTORE_PLATFORM,
                 SET_PLATFORM_SECRET, UNSET_PLATFORM_SECRET -> true;
        };
        v.put("component", type.component());
        v.put("componentPresent", present);
        v.put("snapshotCapturedAt", String.valueOf(after.capturedAt()));
        return v;
    }

    private PlatformSpec requirePlatformSpec(Map<String, Object> parameters) {
        Object raw = parameters == null ? null : parameters.get("spec");
        if (!(raw instanceof Map<?, ?>)) {
            throw new PlanRefused("deployment_spec_required", "Spécification de plateforme absente de l'opération.");
        }
        return mapper.convertValue(raw, PlatformSpec.class);
    }

    private AppSpec requireAppSpec(Map<String, Object> parameters) {
        Object raw = parameters == null ? null : parameters.get("spec");
        if (!(raw instanceof Map<?, ?>)) {
            throw new PlanRefused("deployment_spec_required", "Spécification d'application absente de l'opération.");
        }
        return mapper.convertValue(raw, AppSpec.class);
    }

    private static String stringParam(Map<String, Object> parameters, String key) {
        if (parameters == null || parameters.get(key) == null) {
            return null;
        }
        String v = String.valueOf(parameters.get(key)).trim();
        return v.isEmpty() ? null : v;
    }

    private void assertDeployStack(ServerSnapshot snap) {
        if (!InstallService_securityPresent(snap)) {
            throw new PlanRefused("prerequisite_missing", "Baseline sécurité incomplet.");
        }
        if (snap.docker() == null || !snap.docker().present()) {
            throw new PlanRefused("prerequisite_missing", "Docker absent.");
        }
        if (snap.traefik() == null || !snap.traefik().present()) {
            throw new PlanRefused("prerequisite_missing", "Traefik absent.");
        }
        if (snap.deploy() == null || !snap.deploy().present()) {
            throw new PlanRefused("prerequisite_missing", "Compte deploy absent.");
        }
    }

    private void assertDeployPrerequisites(ServerSnapshot snap, ServerProfile profile, PlatformSpec platform,
                                           String serviceId) {
        assertDeployStack(snap);
        PlatformServiceSpec only = PlatformDeployPlanService.resolveOnly(platform, serviceId);
        boolean needRedis = only != null
                ? only.needs().contains("redis")
                : platform.services().stream().anyMatch(s -> s.needs().contains("redis"));
        boolean needDb = only != null
                ? only.needs().contains("mariadb")
                : platform.services().stream().anyMatch(s -> s.needs().contains("mariadb"));
        if (needRedis && (snap.redis() == null || !snap.redis().present())) {
            throw new PlanRefused("prerequisite_missing", "Redis exigé mais absent.");
        }
        if (needDb && (snap.mariadb() == null || !snap.mariadb().present())) {
            throw new PlanRefused("prerequisite_missing", "MariaDB exigée mais absente.");
        }
        if (!InstallPlanBuilder.securityPresent(snap, List.of(), profile)) {
            throw new PlanRefused("prerequisite_missing", "Baseline sécurité incomplet pour ce profil.");
        }
    }

    private static boolean InstallService_securityPresent(ServerSnapshot s) {
        return com.nebdev.devopsagent.stack.InstallPlanBuilder.securityPresent(s, List.of(), ServerProfile.DEFAULT);
    }

    private Observation observe(ServerSnapshot snap) {
        AuditReport report = AuditRules.evaluate(snap);
        Map<String, Object> payload = mapper.convertValue(snap, new TypeReference<>() {});
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("docker", snap.docker() != null && snap.docker().present());
        caps.put("traefik", snap.traefik() != null && snap.traefik().present());
        caps.put("mariadb", snap.mariadb() != null && snap.mariadb().present());
        caps.put("redis", snap.redis() != null && snap.redis().present());
        caps.put("deploy", snap.deploy() != null && snap.deploy().present());
        caps.put("backups", snap.backups() != null && snap.backups().present());
        caps.put("security", InstallService_securityPresent(snap));
        return new Observation(payload, report.score(), report.grade(), snap.osFamily(), snap.osVersion(), caps);
    }

    private ServerSnapshot toSnapshot(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            throw new PlanRefused("discovery_required", "Analyse le serveur avant de planifier cette opération.");
        }
        return mapper.convertValue(payload, ServerSnapshot.class);
    }

    static Integer exitCodeOf(String msg) {
        var m = Pattern.compile("exit=(\\d+)").matcher(msg);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    static String classify(String msg) {
        String m = msg.toLowerCase();
        if (m.contains("empreinte") || m.contains("host_key") || m.contains("could not verify")) {
            return "host_key_mismatch";
        }
        if (m.contains("authentification refusée") || m.contains("auth fail")) {
            return "auth_failed";
        }
        if (m.contains("timed out") || m.contains("connect") || m.contains("unreachable")) {
            return "unreachable";
        }
        if (m.contains("exit=")) {
            return "remote_failure";
        }
        return "execution_failed";
    }
}
