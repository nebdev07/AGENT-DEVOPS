# API — VPS Autopilot

> Dernière mise à jour : 2026-09-29  
> Implémenté : V0–V3 partiel + V7-01/02/04/05 (plateformes + Git privé). Base : `http://127.0.0.1:8088`

Auth : **aucune** (outil local). Ne pas exposer tel quel sur Internet. Profil `cargo` (image Docker) : `/` + `GET /api/v1/health` seulement ; le reste → `403` (ADR-030).

| Méthode | Chemin | Rôle | Implémenté |
|---|---|---|---|
| GET | `/` | UI : accueil `#home`, aide `#help`, cockpit `#cockpit` | oui |
| GET | `/api/v1/health` | Liveness (`role` = `operator` ou `cargo`) | oui |
| GET | `/api/v1/runtime` | Mode `ssh` ou `lab-fixture`, host, fixture | oui |
| GET | `/api/v1/state` | Dernier `InfrastructureState` (`204` si vide) | oui |
| GET | `/api/v1/servers` | Mes serveurs : serveurs déjà analysés (nom, hôte, port, utilisateur, OS, date, `readOnly`), plus récent d'abord. Jamais de secret (`state/known-servers.json`) | oui |
| GET | `/api/v1/servers/last?host=` | Dernière analyse enregistrée de ce serveur (`mode`, `snapshot`, `report`), sans connexion SSH ; `204` si aucune | oui |
| DELETE | `/api/v1/servers?host=&port=` | Retire le serveur de la liste ; l'analyse enregistrée (`state/<hôte>.json`) est gardée | oui (local) |
| PUT | `/api/v1/servers/profile` | Profil du serveur (ADR-038) : `{host, port, httpOnly, publicPort, sharedServer}`. Réglage local, aucune commande sur le serveur ; pris en compte aux prochaines installations et déploiements. `400` si serveur inconnu ou port hors plage | oui (local) |
| POST | `/api/v1/discover` | Lance la discovery (fixture, `.env`, ou `{host,user,port}` depuis la console) | oui |
| POST | `/api/v1/ssh/accept-host` | Lit la clé d’hôte via SSHJ, stocke l’empreinte (`state/trusted-hosts.json`) | oui |
| POST | `/api/v1/ssh/install-key` | CHANGE : installe la clé publique de l’agent (`confirm: true` + mot de passe, jamais stocké) | oui |
| GET / PUT | `/api/v1/stack` | Intention de pile (composants cochés). `409` si écart politique sans `acknowledgeWarnings` | oui |
| GET | `/api/v1/plan` | Écart pile voulue vs dernier snapshot (READY / SKIP / PLANNED / REFUSED). `?host=` (2026-10-05) : dernière analyse de ce serveur et son profil ; sans `host`, dernière analyse tous serveurs confondus | oui |
| POST | `/api/v1/install` | CHANGE : installe un composant READY (`confirm: true`) puis re-discovery | oui |
| GET / PUT | `/api/v1/apps` | Intention d’apps cargo (slug, Git HTTPS ou SSH, hôte, port) | oui |
| DELETE | `/api/v1/apps?id=` | Retire une app de la liste locale (pas d’arrêt conteneur VPS) | oui |
| GET | `/api/v1/apps/deploy-plan` | Plan CHANGE avant confirmation | oui |
| GET / PUT | `/api/v1/apps/public-domain` | Domaine public de base (ex. `apps.nebdev.org`) | oui |
| POST | `/api/v1/apps/deploy` | CHANGE `deploy-app` (`confirm: true`) puis re-discovery | oui |
| GET / POST | `/api/v1/git-deploy-key` | Clé Ed25519 lecture seule (locale, hors git). POST `{confirm, rotate}` | **oui V7-05** |
| GET / PUT | `/api/v1/platforms` | Intention de plateformes (N services). PUT optionnel `vpsIp` | oui (V7-01) |
| DELETE | `/api/v1/platforms?id=` | Retire une plateforme de la liste locale | oui |
| GET | `/api/v1/platforms/deploy-plan` | Plan CHANGE avant confirmation | **oui V7-02** |
| POST | `/api/v1/platforms/deploy` | CHANGE `deploy-platform` (`confirm: true`) | **oui V7-02** |
| POST | `/api/v1/platforms/restore` | CHANGE `restore-platform`, multipart : `id host port user confirm uploadsSubdir foreignKeyChecks` (défaut `true` ; `false` = clés étrangères non vérifiées pendant l’import) + fichiers `sql` (`.sql`/`.sql.gz`) et/ou `uploads` (`.tar.gz`/`.tgz`/`.tar`/`.zip`). Réponse `{status, id, risk, report[]}` | **oui V7-06** |
| GET / POST | `/api/v1/git-deploy-key/repos` | Une clé par dépôt `git@` (apps + plateformes) : `{repos:[{repo, keyId, usedBy, present, fallback, publicKey, fingerprint, deployKeyPage}]}`. POST `{repo, confirm, rotate}` | **oui V7-06** |
| POST | `/api/v1/git-deploy-key/repos/verify` | READ. `{repo}` → `{ok, message}` : la clé du dépôt est-elle acceptée (SSH `git-upload-pack`, aucun clone) | **oui 2026-10-01** |
| POST | `/api/v1/platforms/sql/analyze` | READ. `{sql}` → `{risk: READ\|CHANGE, statements[], warnings[]}` ; 400 si refusé (DROP, TRUNCATE, droits, fichiers…) | **oui 2026-10-01** |
| POST | `/api/v1/platforms/sql` | `{id, host, port, user, sql, confirm}`. Lecture : READ. Écriture : CHANGE `sql-write-platform` (confirm requis, sauvegarde avant). → `{platformId, risk, database, backup, statements:[{index, sql, status: ok\|error\|skipped, columns, rows, affectedRows}], error, outputTruncated}` | **oui 2026-10-01** |
| GET | `/api/v1/platforms/status?id=` | READ. `{id, online, services:[{service, url, httpCode, online}]}` (HTTPS, certificat valide, `httpCode=-1` si injoignable) | **oui 2026-10-01** |

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

## GET / PUT `/api/v1/platforms` (V7-01)

Intention locale `state/platforms-intent.json` (ADR-036). **Aucun SSH.**

PUT :

```json
{
  "vpsIp": "10.0.0.8",
  "platforms": [
    {
      "id": "ma-asso",
      "baseDomain": "apps.exemple.com",
      "routing": "host",
      "services": [
        {
          "id": "api",
          "gitUrl": "https://github.com/org/api.git",
          "gitRef": "main",
          "hostname": "",
          "pathPrefix": "/api",
          "port": 8080,
          "context": "backend/api",
          "needs": ["mariadb", "redis", "volume:uploads"],
          "buildArgs": {}
        },
        {
          "id": "www",
          "gitUrl": "https://github.com/org/site.git",
          "gitRef": "main",
          "hostname": "",
          "pathPrefix": "/",
          "port": 3000,
          "context": "",
          "needs": [],
          "buildArgs": { "NEXT_PUBLIC_API_URL": "https://api.ma-asso.apps.exemple.com" }
        }
      ]
    }
  ]
}
```

Hôte vide → `service.plateforme.domaine` (routage host) ou `plateforme.domaine` (routage path) ; sslip si `vpsIp`.  
`routing: path` : un DNS partagé, `pathPrefix` par service (`/` , `/api`). Traefik `PathPrefix` + `StripPrefix` (sauf `/`). Collision d’hôte avec une app 1:1 → `400`.  
`aliases` (optionnel, 2026-10-07, routage `path` seulement, 4 max) : adresses secondaires, ex. `["www.mondomaine.org"]`, redirigées en 301 vers l’hôte principal en gardant le chemin, avec leur propre certificat. Chaque adresse doit déjà pointer vers le serveur (sinon la demande de certificat échoue en boucle). IP, hôte principal ou routage `host` → `400`.  
Réponse : `{ platforms, deployReady: true, note }`.

## POST `/api/v1/platforms/deploy`

```json
{ "id": "ma-asso", "host": "x.x.x.x", "port": 22, "user": "debian", "confirm": true }
```

Sans `confirm: true` → `403`. Prérequis : analyse SSH réelle, sécurité SKIP, Docker, Traefik, compte `deploy`. Redis/MariaDB SKIP si un service les exige. GET `/deploy-plan` avant le popup.

