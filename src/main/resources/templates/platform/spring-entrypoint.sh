#!/bin/sh
# VPS Autopilot : traduit db.env / REDIS_* en propriétés Spring si l’opérateur ne les a pas fixées.
set -e
if [ -n "${DB_HOST:-}" ] && [ -z "${SPRING_DATASOURCE_URL:-}" ]; then
  SPRING_DATASOURCE_URL="jdbc:mysql://${DB_HOST}:${DB_PORT:-3306}/${DB_NAME}?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8&serverTimezone=UTC"
  export SPRING_DATASOURCE_URL
fi
if [ -n "${DB_USER:-}" ] && [ -z "${SPRING_DATASOURCE_USERNAME:-}" ]; then
  export SPRING_DATASOURCE_USERNAME="$DB_USER"
fi
if [ -n "${DB_PASSWORD:-}" ] && [ -z "${SPRING_DATASOURCE_PASSWORD:-}" ]; then
  export SPRING_DATASOURCE_PASSWORD="$DB_PASSWORD"
fi
if [ -n "${REDIS_HOST:-}" ]; then
  if [ -z "${SPRING_REDIS_HOST:-}" ]; then export SPRING_REDIS_HOST="$REDIS_HOST"; fi
  if [ -z "${SPRING_DATA_REDIS_HOST:-}" ]; then export SPRING_DATA_REDIS_HOST="$REDIS_HOST"; fi
  if [ -z "${SPRING_REDIS_PORT:-}" ]; then export SPRING_REDIS_PORT="${REDIS_PORT:-6379}"; fi
  if [ -z "${SPRING_DATA_REDIS_PORT:-}" ]; then export SPRING_DATA_REDIS_PORT="${REDIS_PORT:-6379}"; fi
  if [ -n "${REDIS_PASSWORD:-}" ]; then
    if [ -z "${SPRING_REDIS_PASSWORD:-}" ]; then export SPRING_REDIS_PASSWORD="$REDIS_PASSWORD"; fi
    if [ -z "${SPRING_DATA_REDIS_PASSWORD:-}" ]; then export SPRING_DATA_REDIS_PASSWORD="$REDIS_PASSWORD"; fi
  fi
fi
exec java ${JAVA_OPTS:-} -jar /app/app.jar
