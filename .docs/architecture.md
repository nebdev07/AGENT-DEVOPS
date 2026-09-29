# Architecture — VPS Autopilot

> Mémoire permanente. Enrichir, ne jamais effacer.  
> Dernière mise à jour : 2026-09-29  
> Statut code : voir `current_state.md`

## 1. Couches

```
TOI
 │
 ▼
Agent UI (Web / CLI)          ← console `http://127.0.0.1:8088/` (V0) + overlay loader CHANGE/READ
 │
 ▼
Agent Core
 ├── Planner                  ← V5 (IA) ; V0 : plan fixe « discover only »
 ├── Validator                ← niveaux de risque
 ├── State                    ← JSON local
 └── Executor                 ← V0 : SSH read-only whitelist
        │
        ▼
     Ansible (à partir de V1 CHANGE)
        │ SSH
        ▼
     VPS Ubuntu LTS
```

L’IA **ne SSH pas en root librement**. Elle produit un plan ; seules des actions autorisées partent vers l’executor.

## 2. Modules produit (spécifié)

| Module | Rôle | V0 |
|---|---|---|
| Discovery | OS, CPU, RAM, disque, IP, ports, services, Docker, firewall | **implémenté** |
| Security | user deploy, SSH clés, UFW, fail2ban, unattended-upgrades | **baseline exécutable** (ADR-019) ; user deploy encore spécifié |
| Container | Docker + Compose | spécifié |
| Network | Traefik, réseaux Docker, HTTPS | **rôle exécutable** (ADR-024) |
| Database | MariaDB (une base / projet), Redis à la demande | **instance + grants au deploy-app** (ADR-026 / ADR-029) ; Redis = plus tard |
| Backup | dump, chiffrement, hors-site, preuve déchiffrement | **partiel V4** — exécutable (ADR-034) ; restore SQL complet plus tard |
| Deploy | Git → image → Traefik ; compte `deploy` | **compte + deploy-app + cargo 1:1** (ADR-028→030) |
| Plateforme cargo | N services (sites + API), Redis, volumes, un CHANGE | **V7-01 + V7-02** (`deploy-platform`) |
| Plateforme opérateurs | Auth, sessions, params par opérateur | **spécifié V9** (ADR-031) |

## 3. Dépôt (V0 : un module Maven)

Emplacement : `PROJECTS/ME/AGENTS/devops-agent` (ADR-012). Frères futurs : d’autres dossiers sous `AGENTS/`.

```
AGENTS/devops-agent/
  .docs/                 vérité officielle
  .cursor/rules/         règles agent
  infrastructure/        ansible / docker / templates (vides en V0)
  state/                 snapshots JSON (gitignorés)
  src/main/java/...      Spring Boot
  src/test/              parseur, garde de risque, fixtures
```

Packages Java (`com.nebdev.devopsagent`) :

| Package | Rôle |
|---|---|
| `api` | HTTP health / runtime / state / discover |
| `cli` | `discover` puis exit |
| `config` | `DevopsProperties` |
| `discovery` | script, parseur, service |
| `report` | rapport texte |
| `risk` | `ActionGuard`, cibles interdites |
| `ssh` | SSHJ, script stdin |
| `state` | JSON local |

Split `agent-core` / `api` / `planner` / `executor` **quand** le monolithe V0 le justifiera (ADR-008).

## 4. Flux V0

```
CLI/API discover
  → ActionGuard (READ)
  → SSH clé publique (script bash stdin, aucune écriture distante)
  → Parser
  → state/<host>.json
  → rapport texte

CLI/API install-key (ADR-016)
  → ActionGuard CHANGE whitelist + confirm
  → SSH mot de passe (jamais persisté)
  → 1 ligne dans ~/.ssh/authorized_keys

CLI/API stack (ADR-017)
  → intention locale
  → avertissement si écart politique

CLI/API plan + install (ADR-018)
  → écart voulu vs présent
  → CHANGE confirmé → rôle versionné stdin
  → re-discovery
```

## 5. Hors V0

Pas d’Ansible, pas d’IA générative. Docker + Traefik + MariaDB + Redis + compte `deploy` + **deploy-app** + **deploy-platform** + **backups** : rôles bash exécutables (après SKIP sécurité **sur l’hôte**). Restore SQL complet / grants avancés : suite.
