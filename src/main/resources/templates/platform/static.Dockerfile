# Généré par VPS Autopilot (template static) — site compilé (Angular, Vue, React…) servi par un conteneur nginx.
FROM node:20-bookworm-slim AS build
WORKDIR /app
COPY package.json package-lock.json* ./
RUN if [ -f package-lock.json ]; then npm ci --no-audit --no-fund || npm ci --legacy-peer-deps --no-audit --no-fund || npm install --legacy-peer-deps --no-audit --no-fund; else npm install --legacy-peer-deps --no-audit --no-fund; fi
#BUILD_ARGS#
ARG STATIC_BUILD="npm run build"
ARG STATIC_DIST=dist
COPY . .
RUN sh -c "$STATIC_BUILD"
RUN set -e; index="$(find "$STATIC_DIST" -name index.html -not -path '*/node_modules/*' | awk '{ print length, $0 }' | sort -n | head -n 1 | cut -d' ' -f2-)"; \
    test -n "$index" || { echo "STATIC_INDEX_MISSING: aucun index.html sous $STATIC_DIST"; exit 1; }; \
    mkdir -p /out && cp -a "$(dirname "$index")/." /out/

FROM nginx:1.27-alpine
RUN rm -f /etc/nginx/conf.d/default.conf
COPY autopilot-nginx.conf /etc/nginx/conf.d/autopilot.conf
COPY --from=build /out /usr/share/nginx/html#WEBROOT#
EXPOSE #PORT#
