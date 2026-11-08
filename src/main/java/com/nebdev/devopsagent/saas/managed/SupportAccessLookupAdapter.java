package com.nebdev.devopsagent.saas.managed;

import com.nebdev.devopsagent.saas.organization.OrgAccess;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Port d'OrgAccess : niveau d'accès support INOVNE actif (OPERATE l'emporte sur READ). */
@Component
class SupportAccessLookupAdapter implements OrgAccess.SupportAccessLookup {

    private final ManagedService.Grants grants;

    SupportAccessLookupAdapter(ManagedService.Grants grants) {
        this.grants = grants;
    }

    @Override
    public Optional<String> activeLevel(UUID organizationId, UUID staffUserId, Instant now) {
        return grants.active(organizationId, staffUserId, now).stream()
                .map(ManagedEntities.SupportGrant::getAccessLevel)
                .reduce((a, b) -> "OPERATE".equals(a) || "OPERATE".equals(b) ? "OPERATE" : "READ");
    }
}
