package com.nebdev.devopsagent.saas.billing;

import java.util.Map;
import java.util.Optional;

/**
 * Prestataire de paiement (directive §10) : le domaine de facturation n'en connaît que ce contrat.
 * Implémentations : {@link ManualPaymentProvider} (virement / mobile money rapproché à la main, défaut) ;
 * Stripe, CinetPay, etc. s'ajoutent comme adaptateurs sans toucher au domaine.
 */
public interface PaymentProvider {

    String id();

    record Checkout(String reference, String paymentUrl, String instructions) {}

    /** Prépare le paiement d'une facture ouverte. */
    Checkout checkout(BillingEntities.Invoice invoice, String customerEmail);

    record WebhookEvent(String eventId, String type, String invoiceNumber, String providerRef, Map<String, Object> raw) {}

    /**
     * Vérifie la signature et décode l'événement. Vide si la signature est absente ou invalide :
     * l'événement est alors ignoré (et tracé), jamais appliqué.
     */
    Optional<WebhookEvent> verify(Map<String, String> headers, byte[] body);
}
