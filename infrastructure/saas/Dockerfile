# VPS Autopilot SaaS (ADR-047, ADR-055) — une image, deux rôles :
#   control plane : SPRING_PROFILES_ACTIVE=saas   (API v2 + application Angular, PostgreSQL)
#   runner        : SPRING_PROFILES_ACTIVE=runner (exécution SSH, aucune base, aucun port)
# Construire depuis la racine du dépôt :  docker build -f infrastructure/saas/Dockerfile -t vps-autopilot-saas .
# L'image historique (profil platform) reste ./Dockerfile, inchangée.

FROM node:22-bookworm-slim AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web/ ./
RUN npx ng build --configuration production

FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package && cp target/devops-agent-*.jar /src/app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 --no-create-home app \
 && mkdir -p /app/data/state && chown -R app:app /app/data
COPY --from=build --chown=app:app /src/app.jar /app/app.jar
COPY --from=web --chown=app:app /web/dist/web/browser /app/web
USER 10001
ENV SPRING_PROFILES_ACTIVE=saas \
    SERVER_ADDRESS=0.0.0.0 \
    SERVER_PORT=8080 \
    DEVOPS_STATE_DIR=/app/data/state \
    AUTOPILOT_WEB_DIR=/app/web \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
# Aucun secret dans l'image : clé maîtresse, mots de passe, jetons et clé du runner viennent de l'environnement.
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
