package com.nebdev.devopsagent.saas.operation;

import com.nebdev.devopsagent.saas.server.Server;
import com.nebdev.devopsagent.saas.shared.Hashing;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Le hash approuvé couvre le plan du moteur <b>et</b> la cible exacte (hôte, port, utilisateur, empreinte d'hôte,
 * paramètres). Changer l'un d'eux après approbation invalide l'approbation (directive §8). Le même calcul tourne
 * dans le control plane (approbation) et dans le runner séparé (contrôle juste avant d'agir).
 */
public final class PlanHash {

    private PlanHash() {}

    /** Cible couverte par le hash, indépendante de l'entité (le runner n'a pas de base). */
    public record HashTarget(UUID serverId, String host, int port, String user, String hostFingerprint) {
        public static HashTarget of(Server s) {
            return new HashTarget(s.getId(), s.getHost(), s.getSshPort(), s.getSshUser(), s.getHostFingerprint());
        }
    }

    public static Map<String, Object> document(EnginePort.Plan plan, HashTarget t, Map<String, Object> parameters) {
        Map<String, Object> doc = new LinkedHashMap<>(plan.toMap());
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("serverId", t.serverId().toString());
        target.put("host", t.host());
        target.put("port", t.port());
        target.put("user", t.user());
        target.put("hostFingerprint", t.hostFingerprint() == null ? "" : t.hostFingerprint());
        doc.put("target", target);
        doc.put("parameters", parameters == null ? Map.of() : parameters);
        return doc;
    }

    public static Map<String, Object> document(EnginePort.Plan plan, Server server, Map<String, Object> parameters) {
        return document(plan, HashTarget.of(server), parameters);
    }

    public static String of(EnginePort.Plan plan, HashTarget target, Map<String, Object> parameters) {
        return Hashing.canonicalHash(document(plan, target, parameters));
    }

    public static String of(EnginePort.Plan plan, Server server, Map<String, Object> parameters) {
        return of(plan, HashTarget.of(server), parameters);
    }
}
