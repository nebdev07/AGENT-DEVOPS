# API — VPS Autopilot

> Dernière mise à jour : 2026-09-08  
> Implémenté : V0–V3 partiel. Base : `http://127.0.0.1:8088`

Auth : **aucune** (outil local). Ne pas exposer tel quel sur Internet. Profil `cargo` (image Docker) : `/` + `GET /api/v1/health` seulement ; le reste → `403` (ADR-030).

| Méthode | Chemin | Rôle | Implémenté |
|---|---|---|---|
| GET | `/` | UI : accueil `#home`, aide `#help`, cockpit `#cockpit` | oui |
| GET | `/api/v1/health` | Liveness (`role` = `operator` ou `cargo`) | oui |
| GET | `/api/v1/runtime` | Mode `ssh` ou `lab-fixture`, host, fixture | oui |
| GET | `/api/v1/state` | Dernier `InfrastructureState` (`204` si vide) | oui |
| POST | `/api/v1/discover` | Lance la discovery (fixture, `.env`, ou `{host,user,port}` depuis la console) | oui |
| POST | `/api/v1/ssh/accept-host` | Lit la clé d’hôte via SSHJ, stocke l’empreinte (`state/trusted-hosts.json`) | oui |
| POST | `/api/v1/ssh/install-key` | CHANGE : installe la clé publique de l’agent (`confirm: true` + mot de passe, jamais stocké) | oui |
| GET / PUT | `/api/v1/stack` | Intention de pile (composants cochés). `409` si écart politique sans `acknowledgeWarnings` | oui |
| GET | `/api/v1/plan` | Écart pile voulue vs dernier snapshot (READY / SKIP / PLANNED / REFUSED) | oui |
| POST | `/api/v1/install` | CHANGE : installe un composant READY (`confirm: true`) puis re-discovery | oui |
| GET / PUT | `/api/v1/apps` | Intention d’apps cargo (slug, Git HTTPS, hôte, port) | oui |
| DELETE | `/api/v1/apps?id=` | Retire une app de la liste locale (pas d’arrêt conteneur VPS) | oui |
| GET | `/api/v1/apps/deploy-plan` | Plan CHANGE avant confirmation | oui |
| GET / PUT | `/api/v1/apps/public-domain` | Domaine public de base (ex. `apps.nebdev.org`) | oui |
| POST | `/api/v1/apps/deploy` | CHANGE `deploy-app` (`confirm: true`) puis re-discovery | oui |

## POST `/api/v1/discover`

Corps JSON optionnel :

```json
{ "name": "vps-perso", "host": "x.x.x.x", "port": 22, "user": "debian", "os": "auto" }
```

`os` : `auto` (défaut) ou `ubuntu` | `debian` | `rhel` | `rocky` | `alma` | `fedora` | `alpine` | `amazon`.

| Condition | Comportement |
|---|---|
| `fixture` fourni | Lit cette fixture |
| sinon `DEVOPS_SSH_HOST` + user + clé | SSH réel (script stdin) |
| sinon | `400` — pas de fixture dans la console |

Réponse : `{ mode, snapshot, report, stateFile }`.

Erreurs : `400` config / argument ; `403` action interdite (`UnsafeActionException`).

CLI équivalent : argument `discover` (`--fixture=…` optionnel), puis arrêt du process.

## POST `/api/v1/ssh/install-key`

```json
{ "host": "x.x.x.x", "port": 22, "user": "ubuntu", "password": "…", "confirm": true }
```

`password` : celui du mail OVH. Jamais écrit dans `state/` ni `.env`.  
Sans `confirm: true` → `403`. Cible interdite (ARC/DTSI) → `403`.  
Réponse : `{ status, outcome: KEY_INSTALLED|KEY_ALREADY_PRESENT, host, user, risk: CHANGE }`.

## GET / PUT `/api/v1/stack`

Intention locale (ADR-017 / ADR-020). PUT :

```json
{
  "enabled": ["security", "docker", "traefik", "mariadb", "deploy", "backups"],
  "details": {
    "security": ["ufw", "fail2ban", "ssh-harden", "unattended"],
    "docker": ["engine", "compose", "docker-group"]
  },
  "acknowledgeWarnings": false
}
```

`details` omis → valeurs par défaut du catalogue. Décocher un détail ARC (UFW, fail2ban, SSH durci) sans `acknowledgeWarnings` → `409`.

Une entrée catalogue `defaultEnabled` absente d’un ancien `stack-intent.json` est ajoutée **une fois** (`catalogSeen`). Décocher ensuite n’est pas recassé.

Écart de politique sans `acknowledgeWarnings: true` → `409` + liste d’avertissements. Rien n’est installé sur le VPS.
