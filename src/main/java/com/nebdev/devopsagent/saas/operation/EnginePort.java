package com.nebdev.devopsagent.saas.operation;

import java.util.List;
import java.util.Map;

/**
 * Port vers le moteur d'exécution (ADR-047). Le control plane ne connaît ni SSH, ni scripts, ni fichiers :
 * il demande un plan déterministe, puis l'exécution d'un plan approuvé. L'adaptateur actuel appelle le moteur
 * dans le même processus ; un runner distant implémentera le même contrat.
 */
public interface EnginePort {

    /**
     * @param host adresse de connexion (IP figée après contrôle anti-SSRF, ou nom)
     * @param name nom déclaré du serveur (profils, journaux) : inchangé par la résolution
     */
    record Target(String host, int port, String user, String name) {
        public Target(String host, int port, String user) {
            this(host, port, user, host);
        }

        public Target {
            name = name == null ? host : name;
        }
    }

    /**
     * Plan déterministe : même état du serveur + même version des scripts ⇒ même plan ⇒ même hash.
     * Ne contient ni horodatage ni secret.
     */
    record Plan(String type, String summary, List<String> steps, List<String> preconditions, List<String> effects,
                String rollback, String scriptSha256, Map<String, Object> facts) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("type", type);
            m.put("summary", summary);
            m.put("steps", steps);
            m.put("preconditions", preconditions);
            m.put("effects", effects);
            m.put("rollback", rollback);
            m.put("scriptSha256", scriptSha256);
            m.put("facts", facts);
            return m;
        }
    }

    /** Le moteur refuse de planifier (prérequis absent, déjà installé, serveur protégé…). */
    class PlanRefused extends RuntimeException {
        private final String code;

        public PlanRefused(String code, String reason) {
            super(reason);
            this.code = code;
        }

        public String code() { return code; }
    }

    record Credentials(String privatePem, String hostFingerprint, String backupPassphrase,
                       String activePublicKey, String pendingPrivatePem, String pendingPublicKey) {
        @Override
        public String toString() {
            return "Credentials[***]";
        }
    }

    record PlanRequest(OperationType type, Target target, Map<String, Object> parameters,
                       Map<String, Object> latestSnapshot, String pendingPublicKey) {}

    /**
     * @param planStillApproved recalcul du plan sur l'état <b>frais</b> du serveur, juste avant d'agir :
     *                          {@code false} ⇒ le plan a changé depuis l'approbation, rien n'est exécuté.
     */
    /**
     * Contenu de staging déchiffré (restore / secret) : jamais loggé (toString opaque).
     */
    record StagingPayload(java.util.UUID id, String kind, String originalName, byte[] content,
                           Map<String, Object> meta) {
        @Override
        public String toString() {
            return "StagingPayload[" + id + "," + kind + "]";
        }
    }

    /**
     * @param gitDeployKeysBase64 clés Deploy Git (keyId → PEM privé en base64), vides hors DEPLOY_* SSH
     * @param stagingPayloads     contenus one-shot (restore / secret), vides hors RESTORE / SET_PLATFORM_SECRET
     */
    record ExecutionRequest(OperationType type, Target target, Map<String, Object> parameters,
                            Credentials credentials, java.util.function.Predicate<Plan> planStillApproved,
                            Map<String, String> gitDeployKeysBase64,
                            Map<String, StagingPayload> stagingPayloads) {
        public ExecutionRequest(OperationType type, Target target, Map<String, Object> parameters,
                                Credentials credentials, java.util.function.Predicate<Plan> planStillApproved) {
            this(type, target, parameters, credentials, planStillApproved, Map.of(), Map.of());
        }

        public ExecutionRequest(OperationType type, Target target, Map<String, Object> parameters,
                                Credentials credentials, java.util.function.Predicate<Plan> planStillApproved,
                                Map<String, String> gitDeployKeysBase64) {
            this(type, target, parameters, credentials, planStillApproved, gitDeployKeysBase64, Map.of());
        }

        public ExecutionRequest {
            gitDeployKeysBase64 = gitDeployKeysBase64 == null ? Map.of() : Map.copyOf(gitDeployKeysBase64);
            stagingPayloads = stagingPayloads == null ? Map.of() : Map.copyOf(stagingPayloads);
        }
    }

    record Observation(Map<String, Object> snapshot, Integer auditScore, String auditGrade, String osFamily,
                       String osVersion, Map<String, Object> capabilities) {}

    record Result(boolean success, Integer exitCode, String output, String error, Map<String, Object> verification,
                  Observation observation, boolean credentialRotated) {}

    Plan plan(PlanRequest request);

    /** Exécution complète, vérification incluse. Ne lève pas : un échec est un {@link Result} non réussi. */
    Result execute(ExecutionRequest request);

    /** Lecture de l'empreinte d'hôte (READ), sans l'enregistrer. */
    String scanHostKey(Target target) throws java.io.IOException;

    /** Faits techniques et constats d'audit d'un snapshot, sans adresse ni secret (contexte de l'IA, ADR-054). */
    record Summary(Map<String, Object> facts, List<Map<String, String>> findings) {}

    Summary summarize(Map<String, Object> snapshot);

    String version();
}
