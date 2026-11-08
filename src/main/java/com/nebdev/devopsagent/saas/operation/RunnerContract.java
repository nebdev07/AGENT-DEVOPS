package com.nebdev.devopsagent.saas.operation;

import java.util.Map;
import java.util.UUID;

/**
 * Contrat de l'API interne entre le control plane et le runner séparé (ADR-055), {@code /api/v2/internal/runner}.
 * Les secrets voyagent <b>scellés</b> pour la clé publique du runner : le control plane ne sait pas les rouvrir.
 */
public final class RunnerContract {

    private RunnerContract() {}

    /** Valeur scellée (base64) : clé SSH ou phrase de sauvegarde. */
    public record SealedValue(UUID credentialId, String sealed, String publicKey) {
        @Override
        public String toString() {
            return "SealedValue[" + credentialId + "]";
        }
    }

    /**
     * Exécution réservée pour un runner. {@code approvedPlanHash} : le runner recalcule le plan sur l'état frais du
     * serveur et n'agit que si {@link PlanHash#of} redonne exactement ce hash.
     */
    /** Staging scellé (dump / archive / secret) pour le runner. */
    public record SealedStaging(UUID id, String kind, String originalName, String sealed, String contentSha256) {
        @Override
        public String toString() {
            return "SealedStaging[" + id + "," + kind + "]";
        }
    }

    public record Job(UUID executionId, UUID organizationId, UUID operationId, OperationType type,
                      EnginePort.Target target, PlanHash.HashTarget hashTarget, Map<String, Object> parameters,
                      String approvedPlanHash, SealedValue active, SealedValue pending, String backupPassphraseSealed,
                      Map<String, SealedValue> gitKeys, Map<String, SealedStaging> staging) {
        public Job {
            gitKeys = gitKeys == null ? Map.of() : Map.copyOf(gitKeys);
            staging = staging == null ? Map.of() : Map.copyOf(staging);
        }

        /** Compat tests / appelants sans staging. */
        public Job(UUID executionId, UUID organizationId, UUID operationId, OperationType type,
                   EnginePort.Target target, PlanHash.HashTarget hashTarget, Map<String, Object> parameters,
                   String approvedPlanHash, SealedValue active, SealedValue pending, String backupPassphraseSealed,
                   Map<String, SealedValue> gitKeys) {
            this(executionId, organizationId, operationId, type, target, hashTarget, parameters, approvedPlanHash,
                    active, pending, backupPassphraseSealed, gitKeys, Map.of());
        }

        @Override
        public String toString() {
            return "Job[" + executionId + ", " + type + "]";
        }
    }

    /** Compte rendu du runner (la sortie est tronquée et hachée par le control plane). */
    public record JobResult(boolean success, Integer exitCode, String output, String error,
                            Map<String, Object> verification, EnginePort.Observation observation,
                            boolean credentialRotated, String engineVersion) {

        public EnginePort.Result toResult() {
            return new EnginePort.Result(success, exitCode, output == null ? "" : output, error,
                    verification == null ? Map.of() : verification, observation, credentialRotated);
        }
    }

    public static final String RUNNER_ID_HEADER = "X-Runner-Id";
}
