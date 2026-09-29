# Roadmap — VPS Autopilot

> Dernière mise à jour : 2026-09-29  
> Distinguer spécifié vs implémenté.

| Version | Contenu | Statut |
|---|---|---|
| **V0** | Discovery + JSON + rapport + console. READ + un CHANGE (`install-agent-pubkey`). Lab fixture si pas de SSH. | **done** (SSH réel : backlog VPS-01 / LAB-01) |
| **V1** | Provisioning : sécurité + Docker + réseaux Docker. Ansible. CHANGE + validation. | **en cours** — rôles `security` et `docker` livrés dans l’agent ; Ansible pas encore |
| **V2** | Traefik + HTTPS + MariaDB (bases isolées) + Redis à la demande | **en cours** — Traefik + MariaDB + **Redis** livrés ; |
| **V3** | Git + image Docker + déploiement d’**une** app (cargo 1:1) | **en cours** — deploy-app + cargo + grants + plan popup + rattrapage Dockerfile (ADR-032) |
| **V4** | Backup chiffré + copie hors-site (poste opérateur au moins) + restore par l’agent | **en cours** — dump+encrypt+copie+preuve OK (ADR-034) ; restore SQL complet plus tard |
| **V5** | IA : langage naturel → plan structuré | spécifié |
| **V6** | Autonomie contrôlée : plan → validation → exécution → vérification | spécifié |
| **V7** | Plusieurs **produits** 1:1 sur un VPS **et** **plateforme cargo** (N services) | **en cours** — V7-01/02/04 livrés ; Git privé / templates Java 11 ouverts |
| **V8** | Multi-serveurs (OVH, Hetzner, …) | spécifié |
| **V9** | Plateforme **multi-opérateurs** : auth, sessions, paramètres par compte, cockpit en ligne derrière login | **spécifié** (ADR-031) — pas de code. Ne pas confondre avec V7 (plusieurs *services* d’un produit). |

SKIP/READY = déjà là **sur l’IP analysée**. La roadmap suit les traitements **livrés dans l’agent**, pour n’importe quel VPS (ADR-025). Le lab ne clôt pas une version.

**Lecture produit** : V0–V8 = outil mono-opérateur (poste local). V9 = plusieurs utilisateurs, chacun sa session et ses VPS (ADR-031).

## Sprint 1 (V0) — critère de done

L’agent se connecte (ou lit une fixture), décrit **exactement** le serveur, écrit un JSON d’état, n’a **rien modifié**.

## Infra VPS (rappel, exécutée par l’agent à partir de V1)

Acheter VPS-2 → l’agent sécurise → Docker → Traefik/HTTPS → MariaDB/Redis → backups → CI/CD → monitoring → premier projet (cargo 1:1) → **plateforme multi-services (V7)** → template.

## Non-objectifs tant que les métriques ne le justifient pas

Kubernetes, VPS-4, snapshot OVH payant, Premium Backup, serveur dédié.
