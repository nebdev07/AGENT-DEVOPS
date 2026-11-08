package com.nebdev.devopsagent.saas.billing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebdev.devopsagent.saas.shared.Hashing;
import com.nebdev.devopsagent.saas.shared.SaasProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Prestataire « manuel » : la facture est réglée hors ligne (virement, mobile money) puis rapprochée par un
 * administrateur INOVNE, ou par un webhook signé HMAC-SHA256 émis par un outil de rapprochement.
 *
 * <p>Signature : en-tête {@code X-Autopilot-Signature: t=<epoch>,v1=<hex(hmac(secret, t + "." + body))>},
 * tolérance de 5 minutes (anti-rejeu, en plus de l'idempotence par identifiant d'événement).
 */
@Component
public class ManualPaymentProvider implements PaymentProvider {

    static final long TOLERANCE_SECONDS = 300;

    private final SaasProperties properties;
    private final ObjectMapper mapper;

    public ManualPaymentProvider(SaasProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return "manual";
    }

    @Override
    public Checkout checkout(BillingEntities.Invoice invoice, String customerEmail) {
        return new Checkout(invoice.getNumber(), null,
                "Référence à indiquer sur le paiement : " + invoice.getNumber());
    }

    @Override
    public Optional<WebhookEvent> verify(Map<String, String> headers, byte[] body) {
        String secret = properties.getBilling().getWebhookSecret();
        if (secret == null || secret.length() < 32) {
            return Optional.empty();
        }
        String header = headers.entrySet().stream()
                .filter(e -> e.getKey().toLowerCase(Locale.ROOT).equals("x-autopilot-signature"))
                .map(Map.Entry::getValue).findFirst().orElse(null);
        if (header == null) {
            return Optional.empty();
        }
        String t = null;
        String v1 = null;
        for (String part : header.split(",")) {
            String[] kv = part.strip().split("=", 2);
            if (kv.length == 2 && kv[0].equals("t")) t = kv[1];
            if (kv.length == 2 && kv[0].equals("v1")) v1 = kv[1];
        }
        if (t == null || v1 == null) {
            return Optional.empty();
        }
        long ts;
        try {
            ts = Long.parseLong(t);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (Math.abs(Instant.now().getEpochSecond() - ts) > TOLERANCE_SECONDS) {
            return Optional.empty();
        }
        if (!Hashing.constantTimeEquals(sign(secret, t, body), v1.toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }
        try {
            Map<String, Object> raw = mapper.readValue(body, new TypeReference<>() {});
            String id = String.valueOf(raw.get("id"));
            String type = String.valueOf(raw.get("type"));
            String invoice = raw.get("invoiceNumber") == null ? null : String.valueOf(raw.get("invoiceNumber"));
            String ref = raw.get("providerRef") == null ? null : String.valueOf(raw.get("providerRef"));
            if (id.isBlank() || "null".equals(id)) {
                return Optional.empty();
            }
            return Optional.of(new WebhookEvent(id, type, invoice, ref, raw));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public static String sign(String secret, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
