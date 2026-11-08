package com.nebdev.devopsagent.saas.organization;

import java.util.Collection;
import java.util.Set;

/** Catalogue stable des permissions d'organisation (codes = contrat ; les rôles en portent un sous-ensemble). */
public final class OrgPermissions {

    public static final String ORG_READ = "org.read";
    public static final String ORG_MANAGE = "org.manage";
    public static final String MEMBER_READ = "member.read";
    public static final String MEMBER_MANAGE = "member.manage";
    public static final String SERVER_READ = "server.read";
    public static final String SERVER_MANAGE = "server.manage";
    public static final String SERVER_ANALYZE = "server.analyze";
    public static final String CREDENTIAL_MANAGE = "credential.manage";
    public static final String OPERATION_READ = "operation.read";
    public static final String OPERATION_PROPOSE = "operation.propose";
    public static final String OPERATION_APPROVE = "operation.approve";
    public static final String AUDIT_READ = "audit.read";
    public static final String APITOKEN_MANAGE = "apitoken.manage";
    public static final String BILLING_READ = "billing.read";
    public static final String BILLING_MANAGE = "billing.manage";
    public static final String MANAGED_READ = "managed.read";
    public static final String MANAGED_REQUEST = "managed.request";
    public static final String SUPPORT_GRANT = "support.grant";
    public static final String AI_USE = "ai.use";

    public static final Set<String> ALL = Set.of(ORG_READ, ORG_MANAGE, MEMBER_READ, MEMBER_MANAGE, SERVER_READ,
            SERVER_MANAGE, SERVER_ANALYZE, CREDENTIAL_MANAGE, OPERATION_READ, OPERATION_PROPOSE, OPERATION_APPROVE,
            AUDIT_READ, APITOKEN_MANAGE, BILLING_READ, BILLING_MANAGE, MANAGED_READ, MANAGED_REQUEST, SUPPORT_GRANT,
            AI_USE);

    /** Accès support INOVNE en lecture (ADR-052). */
    public static final Set<String> SUPPORT_READ = Set.of(ORG_READ, MEMBER_READ, SERVER_READ, OPERATION_READ,
            AUDIT_READ, MANAGED_READ);

    /** Accès support INOVNE en intervention : lecture + analyse + proposer / approuver des opérations. */
    public static final Set<String> SUPPORT_OPERATE = Set.of(ORG_READ, MEMBER_READ, SERVER_READ, SERVER_ANALYZE,
            OPERATION_READ, OPERATION_PROPOSE, OPERATION_APPROVE, AUDIT_READ, MANAGED_READ, AI_USE);

    /** Scopes qu'un jeton d'API peut porter : jamais la gestion des membres, des jetons ni de la facturation. */
    public static final Set<String> TOKEN_SCOPES = Set.of(ORG_READ, SERVER_READ, SERVER_MANAGE, SERVER_ANALYZE,
            OPERATION_READ, OPERATION_PROPOSE, OPERATION_APPROVE, AUDIT_READ, MANAGED_READ, AI_USE);

    public static final String OWNER = "ORGANIZATION_OWNER";

    private OrgPermissions() {}

    public static void requireKnown(Collection<String> perms) {
        for (String p : perms) {
            if (!ALL.contains(p)) {
                throw new IllegalArgumentException("Permission inconnue : " + p);
            }
        }
    }
}
