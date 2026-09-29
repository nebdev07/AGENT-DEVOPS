# Backlog — VPS Autopilot

> Enrichir ; marquer `Done` sans effacer.  
> Dernière mise à jour : 2026-09-29

| ID | Priorité | Item | Statut |
|---|---|---|---|
| V0-01 | P0 | Discovery SSH read-only + parseur + tests | Done |
| V0-02 | P0 | Console web cockpit | Done |
| V0-03 | P0 | Docs reprise (catalogue, conventions, API, règles) | Done |
| V0-04 | P0 | Bootstrap clé agent (CHANGE confirmé, mot de passe OVH) | Done |
| V0-05 | P1 | Mini-tuto SSH dans la console (générer / ajouter) | Done |
| V0-06 | P1 | Accueil produit + guide dédié + couleurs d’état | Done |
| V0-07 | P1 | Pile voulue décochable + alertes politique | Done |
| LAB-01 | P1 | Docker Desktop / WSL puis `scripts/lab-up.ps1` + discovery SSH loopback | Open |
| VPS-01 | P1 | Acheter VPS-2, `.env`, discovery réelle | Done — IP `57.128.186.38` ; discovery SSH OK ; sécurité SKIP ; Docker présent |
| V1-01 | P1 | Ansible + module Security (CHANGE + validation) | Open — rôles bash idempotents livrés ; Ansible reporté (executor bash accepté tant que playbooks non requis) |
| V1-02 | P1 | Module Docker + réseaux | Open — moteur Docker présent ; réseaux / compose plus tard |
| V2-01 | P1 | Traefik + HTTPS + MariaDB isolée | Done — Traefik + MariaDB + grants V3 |
| V2-02 | P1 | Redis interne (conteneur, requirepass) | Done — rôle `redis` + discovery conteneur ; test VPS PONG OK |
| V3-01 | P1 | Compte `deploy` + Git → image → Traefik | Done — `deploy` + `deploy-app` + cargo |
| V3-02 | P1 | Grants MariaDB (base + user) au `deploy-app` | Done — `db.env` + SQL si conteneur `mariadb` |
| V4-01 | P1 | Backups chiffrés + restore par l’agent | Done (partiel) — dump+encrypt+copie opérateur+preuve déchiffrement (ADR-034) ; restore SQL complet plus tard |
| V5-01 | P3 | IA : langage naturel → plan (jamais root libre) | Open |
| V7-01 | P1 | ADR-036 : intention `platforms-intent.json` + API/UI déclaration plateforme (sans SSH) | Done — 2026-09-29 |
| V7-02 | P1 | CHANGE `deploy-platform` : compose N services, sous-domaines `Host()`, aucun port public | Done — 2026-09-29 |
| V7-03 | P1 | Brancher Redis (`needs: redis`) + volumes nommés (uploads) | Done — dans `deploy-platform` ; pas les apps 1:1 |
| V7-04 | P2 | Routage par **chemin** (`PathPrefix`) pour un seul nom d’hôte | Done — 2026-09-29 |
| V7-05 | P2 | Clone Git **privé** (clé lecture seule) | Open — requis association / DTSI, pas pour ouvrir la prod ARC |
| V7-06 | P2 | Monorepo : `context` Docker (sous-dossier) + templates Next build-args / Spring Java 11 | Open |
| V7-07 | P3 | Redeploy d’un seul service d’une plateforme | Open — après V7-02 |
| PLAT-01 | P2 | Auth + sessions opérateur (ADR-031 / V9) | Open — spécifié seulement |
| PLAT-02 | P2 | Paramètres VPS / apps isolés par compte | Open — après PLAT-01 |
| PLAT-03 | P2 | Cockpit public derrière login (remplace cargo anonyme) | Open — après PLAT-01 ; ADR-030 amendé |

Les items Open ne s’exécutent **pas** sur ARC (`54.37.156.11`) ni DTSI (`154.0.30.233`).
