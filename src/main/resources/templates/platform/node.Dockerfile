# Généré par VPS Autopilot (template node) — aucun Dockerfile requis dans le dépôt.
FROM node:20-bookworm-slim
WORKDIR /app
#BUILD_ARGS#
COPY package.json package-lock.json* ./
RUN if [ -f package-lock.json ]; then npm ci --omit=dev --no-audit --no-fund || npm ci --omit=dev --legacy-peer-deps --no-audit --no-fund || npm install --omit=dev --legacy-peer-deps --no-audit --no-fund; else npm install --omit=dev --legacy-peer-deps --no-audit --no-fund; fi
COPY . .
ENV NODE_ENV=production
ENV PORT=#PORT#
EXPOSE #PORT#
CMD ["npm", "start"]
