package com.nebdev.devopsagent.stack;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import javax.net.ssl.SSLException;

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

    /** Code HTTP de l’URL publique (certificat valide exigé), ou -1 si injoignable. Ne lève pas. */
    static int probe(String url) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(6))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(12))
                    .GET()
                    .build();
            return client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Let’s Encrypt peut mettre une à deux minutes à émettre le certificat d’un nouveau nom d’hôte. */
    private static final Duration CERT_WAIT = Duration.ofMinutes(3);
    private static final Duration RETRY_EVERY = Duration.ofSeconds(10);

    static void checkHttpsUrl(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        long deadline = System.nanoTime() + CERT_WAIT.toNanos();
        while (true) {
            try {
                checkHttpsUrlOnce(url);
                return;
            } catch (IllegalStateException e) {
                if (System.nanoTime() + RETRY_EVERY.toNanos() > deadline) {
                    throw e;
                }
                try {
                    Thread.sleep(RETRY_EVERY.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private static void checkHttpsUrlOnce(String url) {
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
        } catch (SSLException e) {
            throw new IllegalStateException(OperatorMessage.of(
                    "après déploiement, HTTPS « " + url + " » : certificat pas encore valide après "
                            + CERT_WAIT.toMinutes() + " min (" + e.getMessage() + ").",
                    "Vérification post-deploy (V6)",
                    "Let’s Encrypt n’a pas encore émis le certificat : le nom d’hôte doit pointer vers ce serveur, ports 80/443 ouverts. Relance « Vérifier l’accès » dans quelques minutes."), e);
        } catch (Exception e) {
            throw new IllegalStateException(OperatorMessage.of(
                    "après déploiement, HTTPS « " + url + " » injoignable : " + e.getMessage(),
                    "Vérification post-deploy (V6)",
                    "vérifie Traefik, le nom d’hôte, Let’s Encrypt, et que le conteneur tourne."), e);
        }
    }
}
