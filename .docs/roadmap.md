# Roadmap — VPS Autopilot

> Dernière mise à jour : 2026-09-08  
> Distinguer spécifié vs implémenté.

| Version | Contenu | Statut |
|---|---|---|
| **V0** | Discovery + JSON + rapport + console. READ + un CHANGE (`install-agent-pubkey`). Lab fixture si pas de SSH. | **done** (SSH réel : backlog VPS-01 / LAB-01) |
| **V1** | Provisioning : sécurité + Docker + réseaux Docker. Ansible. CHANGE + validation. | **en cours** — rôles `security` et `docker` livrés dans l’agent ; Ansible pas encore |
| **V2** | Traefik + HTTPS + MariaDB (bases isolées) + Redis à la demande | **en cours** — Traefik + MariaDB livrés ; Redis pas encore ; grants par projet au deploy (V3) |
| **V3** | Git + image Docker + déploiement d’une app | **en cours** — compte `deploy` + `deploy-app` + cargo de cet agent (ADR-030) livrés ; grants SQL pas encore |
| **V4** | Backup chiffré + copie hors-site (poste opérateur au moins) + restore par l’agent | spécifié |
| **V5** | IA : langage naturel → plan structuré | spécifié |
| **V6** | Autonomie contrôlée : plan → validation → exécution → vérification | spécifié |
| **V7** | Multi-projets sur un VPS | spécifié |
| **V8** | Multi-serveurs (OVH, Hetzner, …) | spécifié |

SKIP/READY = déjà là **sur l’IP analysée**. La roadmap suit les traitements **livrés dans l’agent**, pour n’importe quel VPS (ADR-025). Le lab ne clôt pas une version.

## Sprint 1 (V0) — critère de done

L’agent se connecte (ou lit une fixture), décrit **exactement** le serveur, écrit un JSON d’état, n’a **rien modifié**.

## Infra VPS (rappel, exécutée par l’agent à partir de V1)

Acheter VPS-2 → l’agent sécurise → Docker → Traefik/HTTPS → MariaDB/Redis → backups → CI/CD → monitoring → premier projet → template.

## Non-objectifs tant que les métriques ne le justifient pas

Kubernetes, VPS-4, snapshot OVH payant, Premium Backup, serveur dédié.
