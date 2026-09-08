# VPS Autopilot

Premier agent du dossier [`AGENTS/`](../README.md). Les suivants seront des **projets séparés** à côté de celui-ci.

Agent DevOps personnel. **V0** : observer un VPS par SSH. Premier CHANGE : installer la clé de l’agent (mot de passe OVH, confirmation).

```
toi → console web / CLI / API → discovery READ → JSON d’état → rapport

Sans VPS ni Docker : la console tourne en **laboratoire** (fixture Ubuntu vierge).
Avec Docker : `powershell -File scripts/lab-up.ps1` puis `.env` vers `127.0.0.1:2222`.
Avec le VPS-2 : `.env` vers l’IP publique.
```

Les installations (Docker, Traefik, MariaDB, compte deploy, une app Git) passent par **l’agent** (plan + confirmation). Backups : plus tard.

**Reprendre le projet :** `.docs/documentation.md` (parcours) — puis `.docs/`. Règles agent : `.cursor/rules.md`.

## Prérequis

- JDK **21** : `C:\env\java\jdk-21` (le `java` du PATH peut encore être 11)
- Maven 3.9+
- Pour une discovery réelle : VPS Ubuntu + clé SSH déjà dans `~/.ssh/known_hosts`

## Configuration

```powershell
copy .env.example .env
# renseigner DEVOPS_SSH_HOST, USER, KEY — jamais committer .env
```

## Commandes

```powershell
$env:JAVA_HOME = "C:\env\java\jdk-21"

# tests (fixtures, sans VPS)
mvn test

# discovery sur fixture (démo)
mvn -q spring-boot:run "-Dspring-boot.run.arguments=discover --fixture=classpath:fixtures/ubuntu-vps2-blank.txt"

# discovery réelle (après .env)
mvn -q spring-boot:run "-Dspring-boot.run.arguments=discover"

# API
mvn spring-boot:run
# Console opérateur : http://localhost:8088/
# Image cargo (VPS, profil cargo) : Dockerfile à la racine, port 8080
# POST http://localhost:8088/api/v1/discover
```

## Structure

| Chemin | Rôle |
|---|---|
| `.docs/` | Vision, ADR, roadmap, état |
| `src/main/java/...` | Spring Boot (discovery, risque, SSH, état) |
| `infrastructure/` | Ansible / Docker / templates (V1+) |
| `state/` | Snapshots JSON locaux (gitignorés) |

## Règle

DESTRUCTIVE est refusé. Le seul CHANGE V0 est l’installation confirmée de la clé agent (`POST /api/v1/ssh/install-key`).
