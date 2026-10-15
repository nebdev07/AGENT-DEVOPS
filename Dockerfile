# Instance cargo (ADR-030) : Git → image → Traefik. Pas le cerveau opérateur.
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package && cp target/devops-agent-*.jar /src/app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 --no-create-home app
COPY --from=build /src/app.jar /app/app.jar
RUN chown app:app /app/app.jar
USER 10001
ENV SPRING_PROFILES_ACTIVE=cargo
ENV SERVER_PORT=8080
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
