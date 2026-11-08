package com.nebdev.devopsagent.saas.shared;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * Pose le contexte de sécurité au niveau des lignes (RLS, ADR-049) pour la transaction courante :
 * {@code set_config(..., true)} est local à la transaction et disparaît au commit / rollback.
 *
 * <p>Sans appel à {@link #enterOrg}, les tables d'organisation renvoient zéro ligne (fail-closed).
 * {@link #enterPlatform} n'est utilisé que par du code plateforme de confiance (worker, facturation,
 * administration) — jamais à partir d'un identifiant fourni par le client sans contrôle préalable.
 */
@Component
public class RlsContext {

    @PersistenceContext
    private EntityManager em;

    public void enterOrg(UUID orgId) {
        requireTransaction();
        em.createNativeQuery("select set_config('app.bypass_rls', '', true), set_config('app.org_id', :org, true)")
                .setParameter("org", orgId.toString())
                .getSingleResult();
    }

    public void enterPlatform() {
        requireTransaction();
        em.createNativeQuery("select set_config('app.org_id', '', true), set_config('app.bypass_rls', 'on', true)")
                .getSingleResult();
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Contexte RLS hors transaction : il ne s'appliquerait à rien.");
        }
    }
}
