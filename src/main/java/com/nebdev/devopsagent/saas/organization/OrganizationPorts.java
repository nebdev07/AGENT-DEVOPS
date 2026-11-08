package com.nebdev.devopsagent.saas.organization;

import java.util.UUID;

/** Ports sortants du module organisation (implémentés par la facturation) : aucun couplage aux plans. */
public final class OrganizationPorts {

    private OrganizationPorts() {}

    /** Appelé à la création d'une organisation (ex. démarrage de la période d'essai). */
    public interface Lifecycle {
        void onOrganizationCreated(UUID organizationId, String currency);
    }

    public interface MemberQuotaPolicy {
        void assertCanAddMember(UUID organizationId, long currentCount);
    }
}
