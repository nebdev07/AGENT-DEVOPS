# Généré par VPS Autopilot (template next) — aucun Dockerfile requis dans le dépôt.
FROM node:20-bookworm-slim AS deps
WORKDIR /app
COPY package.json package-lock.json* ./
RUN if [ -f package-lock.json ]; then npm ci --no-audit --no-fund || npm ci --legacy-peer-deps --no-audit --no-fund || npm install --legacy-peer-deps --no-audit --no-fund; else npm install --legacy-peer-deps --no-audit --no-fund; fi

FROM node:20-bookworm-slim AS builder
WORKDIR /app
#BUILD_ARGS#
COPY --from=deps /app/node_modules ./node_modules
COPY . .
ENV NEXT_TELEMETRY_DISABLED=1
RUN mkdir -p public && npm run build

FROM node:20-bookworm-slim AS runner
WORKDIR /app
ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
ENV PORT=#PORT#
ENV HOSTNAME=0.0.0.0
#BUILD_ARGS#
COPY --from=builder /app ./
EXPOSE #PORT#
CMD ["sh", "-c", "exec node node_modules/next/dist/bin/next start -H 0.0.0.0 -p ${PORT}"]
