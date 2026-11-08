# Instance publique (ADR-046) : profil platform par défaut. Cargo = SPRING_PROFILES_ACTIVE=cargo.
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package && cp target/devops-agent-*.jar /src/app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 --no-create-home app \
 && mkdir -p /app/data && chown -R app:app /app/data
COPY --from=build /src/app.jar /app/app.jar
RUN chown app:app /app/app.jar
USER 10001
ENV SPRING_PROFILES_ACTIVE=platform
ENV SERVER_ADDRESS=0.0.0.0
ENV SERVER_PORT=8080
ENV DEVOPS_STATE_DIR=/app/data/state
ENV PLATFORM_FOUNDER_EMAIL=nebdev07@gmail.com
ENV PLATFORM_PUBLIC_URL=https://autopilot.inovne.com
ENV PLATFORM_COOKIE_SECURE=true
ENV PLATFORM_MAIL_DEV_CAPTURE=false
# Mot de passe initial : à poser dans app.env sur le VPS (PLATFORM_DEFAULT_PASSWORD), jamais dans l’image.
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
