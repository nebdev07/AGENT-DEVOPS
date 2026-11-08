# SaaS VPS Autopilot — mise en route (profils `saas` et `runner`)

> Mis à jour le 2026-11-08. 284 tests verts (dont 62 SaaS, PostgreSQL 16 réel, RLS active). Décisions : ADR-047 → ADR-057.

## Deux processus
- **Control plane** (`SPRING_PROFILES_ACTIVE=saas`) : API v2, application Angular sous `/app/`, PostgreSQL.
- **Runner** (`SPRING_PROFILES_ACTIVE=runner`, même jar) : exécution SSH. Aucune base, aucun port. Seul détenteur de la clé privée qui ouvre les clés SSH (ADR-055).

Mode `embedded` (une seule instance qui exécute elle-même) : lab seulement — l'instance détient alors la clé maîtresse qui protège les clés SSH.

## Docker (recommandé)
```
cd infrastructure/saas
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out runner.key
openssl pkey -in runner.key -pubout -out runner.pub
cp .env.example .env        # remplir ; AUTOPILOT_RUNNER_PUBLIC_KEY = contenu de runner.pub
docker compose --env-file .env up -d --build
```
Le control plane écoute sur `127.0.0.1:${AUTOPILOT_HTTP_PORT:-8080}` : le placer derrière un reverse proxy TLS. La base n'est sur aucun réseau public. **Éprouvé en local le 2026-11-08** (pile saine, scénario lab rejoué dans les conteneurs). Lab : ajouter `-f compose.lab.yml` (cibles privées, MailHog) — jamais en production.

## Sans Docker
1. PostgreSQL 16, base `autopilot`. Flyway (`AUTOPILOT_DB_OWNER_USER`) crée le rôle `autopilot_app` s'il n'existe pas (sans mot de passe : le créer d'avance avec mot de passe, voir `infrastructure/saas/postgres-init.sh`).
2. `cd web && npx ng build`, puis `mvn -DskipTests package`.
3. Control plane : `SPRING_PROFILES_ACTIVE=saas`, `AUTOPILOT_DB_URL/USER/PASSWORD`, `AUTOPILOT_DB_OWNER_USER/PASSWORD`, `AUTOPILOT_MASTER_KEY` (openssl rand -base64 32, **à sauvegarder hors base**), `AUTOPILOT_PUBLIC_URL`, `AUTOPILOT_RUNNER_MODE=remote`, `AUTOPILOT_RUNNER_TOKEN` (≥ 32 car.), `AUTOPILOT_RUNNER_PUBLIC_KEY`, SMTP `SPRING_MAIL_HOST/PORT/USERNAME/PASSWORD`, facultatif `AUTOPILOT_AI_PROVIDER=anthropic` + `AUTOPILOT_AI_API_KEY`, `AUTOPILOT_PAYMENT_WEBHOOK_SECRET`.
4. Runner : `SPRING_PROFILES_ACTIVE=runner`, `AUTOPILOT_CONTROL_PLANE_URL` (https hors boucle locale), `AUTOPILOT_RUNNER_TOKEN`, `AUTOPILOT_RUNNER_PRIVATE_KEY_FILE`, `AUTOPILOT_RUNNER_ID`.
5. `/app/`, API `/api/v2`, OpenAPI `/api/v2/openapi`, santé `/actuator/health`.

## Premier administrateur INOVNE
`AUTOPILOT_BOOTSTRAP_ADMIN_EMAIL` : s'inscrire avec cette adresse, redémarrer le control plane, puis **activer la double authentification** (menu Sécurité) : sans elle, le rôle de personnel reste inactif (ADR-052).

## Cibles SSH (ADR-056)
Par défaut, boucle locale et réseaux privés sont refusés. `AUTOPILOT_ALLOW_PRIVATE_TARGETS=true` (control plane **et** runner) seulement en lab ou auto-hébergé. `AUTOPILOT_DENIED_TARGETS` : hôtes/IP toujours refusés.

## Passage d'embedded à remote
Configurer la clé publique et le mode remote, démarrer le control plane : les clés existantes sont rescellées automatiquement (journal « valeur(s) rescellée(s) »). Démarrer ensuite le runner.

## Modules (ADR-047, vérifiés par ArchUnit)
identity · organization · server · credential · operation · audit · notification · apitoken · managed · billing · ai · admin · security · runner · engine (adaptateur vers le moteur historique) · shared.

## Pas encore fait (honnêteté)
- Déploiement de plateformes/apps via le SaaS (catalogue : analyse, installations, sauvegarde, rotation de clé).
- Bascule réelle de `autopilot.inovne.com` (importeur prêt et testé, ADR-057 ; jamais exécuté sur les vraies données ; autorisation requise).
- Génération des clés SSH côté runner (aujourd'hui le control plane les voit à la création).
- Adaptateurs de paiement réels (Stripe, mobile money) ; tests navigateur automatisés. CI écrite (`.github/workflows/ci.yml`) mais jamais exécutée sur GitHub.
- Aucun test sur un VPS réel ; testé sur le lab SSH local (conteneur Ubuntu 24.04).

## Migration depuis le profil `platform` (ADR-057)
1. Sauvegarder `state/` de l'instance platform.
2. Démarrer le control plane avec `AUTOPILOT_MIGRATION_PLATFORM_STATE_DIR=<copie de state/platform>` (+ facultatif `AUTOPILOT_MIGRATION_SSH_PRIVATE_KEY_FILE`, `AUTOPILOT_MIGRATION_ORGANIZATION_NAME`) : **essai à blanc**, rapport `saas-import-report.json`.
3. Lire le rapport (comptes écartés, serveurs refusés), puis relancer avec `AUTOPILOT_MIGRATION_APPLY=true`. Retirer ensuite ces variables.
4. Reconfirmer l'empreinte de chaque serveur, activer la MFA du personnel.
