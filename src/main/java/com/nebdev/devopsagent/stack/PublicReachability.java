package com.nebdev.devopsagent.stack;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Vérification post-déploiement (slice V6) : le hostname répond en HTTPS.
 */
final class PublicReachability {

    private PublicReachability() {
    }

    static void checkHttps(String hostname) {
        if (hostname == null || hostname.isBlank()) {
            return;
        }
        checkHttpsUrl("https://" + hostname.trim().toLowerCase() + "/");
    }

    static void checkHttpsUrl(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(12))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();
            HttpResponse<Void> res = client.send(req, HttpResponse.BodyHandlers.discarding());
            int code = res.statusCode();
            if (code >= 200 && code < 500) {
                return;
            }
            throw new IllegalStateException(OperatorMessage.of(
                    "après déploiement, HTTPS « " + url + " » a répondu HTTP " + code + ".",
                    "Vérification post-deploy (V6)",
                    "Traefik / DNS / conteneur. L’app peut être UP localement mais pas joignable publiquement."));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(OperatorMessage.of(
                    "après déploiement, HTTPS « " + url + " » injoignable : " + e.getMessage(),
                    "Vérification post-deploy (V6)",
                    "vérifie Traefik, le nom d’hôte, Let’s Encrypt, et que le conteneur tourne."), e);
        }
    }
}
