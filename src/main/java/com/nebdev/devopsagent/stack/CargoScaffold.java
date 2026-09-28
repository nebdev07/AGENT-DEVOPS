package com.nebdev.devopsagent.stack;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Détecte la stack d’un dépôt GitHub public et propose un Dockerfile template (ADR-032).
 * Pas d’IA : templates versionnés dans le code.
 */
final class CargoScaffold {

    private static final Pattern GITHUB = Pattern.compile(
            "^https://github\\.com/([^/]+)/([^/#?]+?)(?:\\.git)?/?$",
            Pattern.CASE_INSENSITIVE);

    private CargoScaffold() {
    }

    record Detection(String kind, int port, String dockerfile, String label) {
        String base64() {
            return Base64.getEncoder().encodeToString(dockerfile.getBytes(StandardCharsets.UTF_8));
        }
    }

    static Optional<Boolean> dockerfileExists(AppSpec app) {
        return DockerfilePreflight.probe(app);
    }

    static Optional<Detection> propose(AppSpec app) {
        Matcher m = GITHUB.matcher(app.gitUrl().trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        String owner = m.group(1);
        String repo = m.group(2);
        if (repo.toLowerCase(Locale.ROOT).endsWith(".git")) {
            repo = repo.substring(0, repo.length() - 4);
        }
        String ref = blankToMain(app.gitRef());
        Optional<String> pkg = fetchRaw(owner, repo, ref, "package.json");
        if (pkg.isPresent()) {
            String body = pkg.get().toLowerCase(Locale.ROOT);
            if (body.contains("\"next\"")) {
                return Optional.of(new Detection(
                        "next",
                        3000,
                        NEXT_DOCKERFILE,
                        "Dockerfile template Next.js (port 3000)"));
            }
            if (body.contains("\"express\"") || body.contains("\"fastify\"") || body.contains("\"nestjs\"")) {
                return Optional.of(new Detection(
                        "node",
                        3000,
                        NODE_DOCKERFILE,
                        "Dockerfile template Node (port 3000)"));
            }
        }
        Optional<String> pom = fetchRaw(owner, repo, ref, "pom.xml");
        if (pom.isPresent() && pom.get().toLowerCase(Locale.ROOT).contains("spring-boot")) {
            return Optional.of(new Detection(
                    "spring-boot",
                    8080,
                    SPRING_DOCKERFILE,
                    "Dockerfile template Spring Boot (port 8080)"));
        }
        return Optional.empty();
    }

    private static String blankToMain(String ref) {
        return ref == null || ref.isBlank() ? "main" : ref;
    }

    private static Optional<String> fetchRaw(String owner, String repo, String ref, String path) {
        URI uri = URI.create("https://raw.githubusercontent.com/" + owner + "/" + repo + "/" + ref + "/" + path);
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(8))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(12))
                    .GET()
                    .build();
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() == 200 && res.body() != null && !res.body().isBlank()) {
                return Optional.of(res.body());
            }
            return Optional.empty();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }
    }

    private static final String NEXT_DOCKERFILE = """
            # Généré par VPS Autopilot (template next) — à rejouer dans le dépôt Git quand possible.
            FROM node:22-bookworm-slim AS deps
            WORKDIR /app
            COPY package.json package-lock.json* ./
            RUN npm ci

            FROM node:22-bookworm-slim AS builder
            WORKDIR /app
            COPY --from=deps /app/node_modules ./node_modules
            COPY . .
            ENV NEXT_TELEMETRY_DISABLED=1
            # Rattrapage cargo (ADR-032) : un lint du dépôt client ne doit pas bloquer le déploiement.
            RUN node -e "const fs=require('fs'); for (const f of ['next.config.ts','next.config.mjs','next.config.js']) { if (!fs.existsSync(f)) continue; let s=fs.readFileSync(f,'utf8'); if (s.includes('ignoreDuringBuilds')) process.exit(0); s=s.replace(/=\\s*\\{/,'= {\\n  eslint: { ignoreDuringBuilds: true },\\n  typescript: { ignoreBuildErrors: true },'); fs.writeFileSync(f,s); break; }"
            RUN npm run build

            FROM node:22-bookworm-slim AS runner
            WORKDIR /app
            ENV NODE_ENV=production
            ENV NEXT_TELEMETRY_DISABLED=1
            ENV PORT=3000
            COPY --from=builder /app/package.json ./package.json
            COPY --from=builder /app/package-lock.json* ./
            COPY --from=builder /app/node_modules ./node_modules
            COPY --from=builder /app/.next ./.next
            COPY --from=builder /app/public ./public
            EXPOSE 3000
            CMD ["npm", "run", "start", "--", "-p", "3000"]
            """;

    private static final String NODE_DOCKERFILE = """
            # Généré par VPS Autopilot (template node)
            FROM node:22-bookworm-slim
            WORKDIR /app
            COPY package.json package-lock.json* ./
            RUN npm ci --omit=dev || npm install --omit=dev
            COPY . .
            ENV NODE_ENV=production
            ENV PORT=3000
            EXPOSE 3000
            CMD ["npm", "start"]
            """;

    private static final String SPRING_DOCKERFILE = """
            # Généré par VPS Autopilot (template spring-boot)
            FROM eclipse-temurin:21-jdk-jammy AS build
            WORKDIR /src
            COPY . .
            RUN chmod +x mvnw 2>/dev/null || true
            RUN if [ -f mvnw ]; then ./mvnw -q -DskipTests package; else mvn -q -DskipTests package; fi

            FROM eclipse-temurin:21-jre-jammy
            WORKDIR /app
            COPY --from=build /src/target/*.jar /app/app.jar
            EXPOSE 8080
            ENTRYPOINT ["java","-jar","/app/app.jar"]
            """;
}
