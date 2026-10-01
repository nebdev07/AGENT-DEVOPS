# Généré par VPS Autopilot (template spring-boot, JDK #JDK#) — jar ou war exécutable.
FROM maven:3.9-eclipse-temurin-#JDK# AS build
WORKDIR /src
COPY . .
RUN mvn -B -q -DskipTests package
RUN set -e; f="$(ls target/*.jar target/*.war 2>/dev/null | grep -v '\.original$' | head -n 1)"; test -n "$f"; cp "$f" /app.bin

FROM eclipse-temurin:#JDK#-jre-jammy
WORKDIR /app
COPY --from=build /app.bin /app/app.jar
COPY autopilot-entrypoint.sh /app/autopilot-entrypoint.sh
RUN chmod +x /app/autopilot-entrypoint.sh && mkdir -p /app/data
ENV SERVER_PORT=#PORT#
EXPOSE #PORT#
ENTRYPOINT ["/app/autopilot-entrypoint.sh"]
