package com.nebdev.devopsagent.stack;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Contrôle léger depuis le poste opérateur : le Dockerfile existe-t-il sur GitHub avant le SSH ?
 */
final class DockerfilePreflight {

    private static final Pattern GITHUB = Pattern.compile(
            "^https://github\\.com/([^/]+)/([^/#?]+?)(?:\\.git)?/?$",
            Pattern.CASE_INSENSITIVE);

    private DockerfilePreflight() {
    }

    /**
     * @return empty si non vérifiable (pas GitHub, réseau, etc.)
     *         false si 404 certain
     *         true si présent
     */
    static Optional<Boolean> probe(AppSpec app) {
        Matcher m = GITHUB.matcher(app.gitUrl().trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        String owner = m.group(1);
        String repo = m.group(2);
        if (repo.toLowerCase(Locale.ROOT).endsWith(".git")) {
            repo = repo.substring(0, repo.length() - 4);
        }
        String ref = app.gitRef() == null || app.gitRef().isBlank() ? "main" : app.gitRef();
        URI uri = URI.create("https://raw.githubusercontent.com/" + owner + "/" + repo + "/" + ref + "/Dockerfile");
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(8))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(12))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> res = client.send(req, HttpResponse.BodyHandlers.discarding());
            int code = res.statusCode();
            if (code == 200) {
                return Optional.of(true);
            }
            if (code == 404) {
                return Optional.of(false);
            }
            // Certains CDN refusent HEAD : essayer GET tronqué
            HttpRequest get = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(12))
                    .GET()
                    .header("Range", "bytes=0-0")
                    .build();
            HttpResponse<Void> getRes = client.send(get, HttpResponse.BodyHandlers.discarding());
            if (getRes.statusCode() == 200 || getRes.statusCode() == 206) {
                return Optional.of(true);
            }
            if (getRes.statusCode() == 404) {
                return Optional.of(false);
            }
            return Optional.empty();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }
    }

    static void assertPresentOrSkip(AppSpec app) {
        Optional<Boolean> hit = probe(app);
        if (hit.isPresent() && !hit.get()) {
            throw new IllegalStateException(OperatorMessage.of(
                    "sur GitHub, aucun Dockerfile à la racine de « " + app.gitUrl()
                            + " » (branche « " + app.gitRef() + " ») — vérifié depuis ton PC avant le SSH.",
                    "Étape Déployer — dépôt (préflight)",
                    "ajoute un Dockerfile à la racine du repo, commit + push, puis Déployer. "
                            + "Un docker-compose.yml seul ne suffit pas : l’agent build via Dockerfile."));
        }
    }
}
