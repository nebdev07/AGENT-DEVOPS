# Règles Cursor — VPS Autopilot (devops-agent)

Ce fichier est **obligatoire** pour toute conversation agent sur ce dépôt.

La source de vérité officielle est **`.docs/`**.  
Complété par : `.cursor/rules/*.mdc`

## Avant toute réponse ou modification

1. Lire le parcours dans `.docs/documentation.md`, puis `current_state.md`, `architecture.md`, `decisions.md`.
2. Respecter les ADR `Accepted`. Pas d’architecture parallèle sans nouvel ADR.
3. L’IA ne génère pas de shell root libre. READ auto ; CHANGE = plan + confirmation ; DESTRUCTIVE = confirmation + liste.
4. CHANGE whitelistés : clé agent, sécurité, Docker, Traefik, MariaDB, compte deploy, deploy-app, deploy-platform (plan + confirmation). Pas d’install manuelle Docker/MariaDB/Traefik/deploy/apps sur un VPS.
5. Lab = VPS perso ou `infrastructure/lab`. Jamais `54.37.156.11`. `154.0.30.233` : lecture seule (ADR-037).
6. Après évolution : `.docs` (`changelog`, `current_state`) **et** le guide Aide `#help` (même chantier). Ne jamais effacer une info.
7. Distinguer **spécifié** vs **implémenté**.
8. Pas de commit sauf demande explicite. Secrets hors git.

## Fichiers règles

| Fichier | Portée |
|---|---|
| `rules/devops-agent-core.mdc` | always apply — risque, cibles, secrets |
| `rules/devops-docs.mdc` | `.docs/**` — doc progressive |
| `rules/devops-user-guide.mdc` | always apply — Aide `#help` à chaque UI/fonction |
