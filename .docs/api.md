# API — VPS Autopilot

> Dernière mise à jour : 2026-10-14 (audit, sauvegardes)  
> Implémenté : V0–V3 partiel + V7-01/02/04/05 (plateformes + Git privé). Base : `http://127.0.0.1:8088`

Auth : **aucune** (outil local). Ne pas exposer tel quel sur Internet. Depuis 1.0.0 la console n'écoute que sur `127.0.0.1` (`SERVER_ADDRESS`, ADR-045). Profil `cargo` (image Docker) : `/` + `GET /api/v1/health` seulement ; le reste → `403` (ADR-030).

| Méthode | Chemin | Rôle | Implémenté |
|---|---|---|---|
| GET | `/` | UI : accueil `#home`, aide `#help`, cockpit `#cockpit` | oui |
| GET | `/api/v1/health` | Liveness (`role` = `operator` ou `cargo`, `version` depuis 1.0.0) | oui |
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
| GET | `/api/v1/platforms/secrets?id=&host=&port=&user=` | READ (ADR-043). Noms des variables posées par service dans `svc-*.local.env` sur le VPS, **jamais les valeurs** : `{id, names:{service:[NOM…]}}` | **oui 2026-10-14** |
| POST | `/api/v1/platforms/secrets` | CHANGE `set-platform-secret` (ADR-043). `{id, service, name, value, restart, host, port, user, confirm}` : pose ou remplace `NOM='valeur'` dans `svc-<service>.local.env` (600 `deploy`), puis recrée le service si `restart` (défaut oui). → `{status, risk, outcome:{platformId, service, name, action: SECRET_ADDED\|SECRET_REPLACED, restart: RESTARTED\|RESTART_FAILED\|RESTART_SKIPPED\|RESTART_SKIPPED_NOT_DEPLOYED}}`. La valeur n'est jamais renvoyée ni journalisée. `400` : nom invalide, apostrophe ou retour à la ligne dans la valeur, plateforme pas encore déployée | **oui 2026-10-14** |
| POST | `/api/v1/platforms/secrets/remove` | CHANGE `set-platform-secret`. Même corps sans `value` : retire la ligne. `action: SECRET_REMOVED\|SECRET_ABSENT` | **oui 2026-10-14** |
| GET | `/api/v1/notify?host&port&user` | READ. État des alertes e-mail du VPS : `{on, host, port, tls, user, passwordSet, from, to[], label, monthly, lastAt, lastKind, lastOk, lastError}` — jamais le mot de passe (ADR-044) | **oui 2026-10-15** |
| POST | `/api/v1/notify` | CHANGE `configure-notify`. `{host, port, user, confirm, smtpHost, smtpPort, tls: ssl\|starttls\|none, smtpUser, smtpPassword (vide = garder), from, to: "a@x, b@y" (1–5), label, monthly}` → pose les réglages + message d'essai : `outcome {action: CONFIGURED, testOk, error, status}` | **oui 2026-10-15** |
| POST | `/api/v1/notify/test` | CHANGE `test-notify`. `{host, port, user, confirm}` → message d'essai : `outcome {action: TESTED, testOk, error, status}` | **oui 2026-10-15** |
| POST | `/api/v1/notify/remove` | CHANGE `configure-notify`. Retire réglages, scripts et drop-ins : `outcome {action: REMOVED}` | **oui 2026-10-15** |
| GET | `/api/v1/journal?host=&limit=` | Local (ADR-042). Actions journalisées pour ce serveur, les plus récentes d'abord (`limit` 1–1000, défaut 100) : `{host, key, entries:[{at, operator, host, user, method, path, action, risk: READ\|LOCAL\|CHANGE, outcome: OK\|ECHEC, status, durationMs, details{}, error}], journals[]}`. Sans `host` : réglages du poste (`_poste`) | **oui 2026-10-14** |
| GET | `/api/v1/journal/export?host=&format=html\|csv&client=` | Local. Tout le journal du serveur en pièce jointe `journal-<serveur>-<aaaammjj>.html` (imprimable, nom du client facultatif) ou `.csv` (`;`, BOM UTF-8) | **oui 2026-10-14** |
| GET | `/api/v1/audit?host=` | READ, sans connexion (dernière analyse enregistrée). `{host, hostname, capturedAt, os, score, grade, findings:[{id, severity: CRITIQUE\|ELEVE\|MOYEN\|FAIBLE, category, title, observed, risk, recommendation}], strengths[], facts}`. `400` si aucune analyse SSH réelle | **oui 2026-10-14** |
| GET | `/api/v1/audit/report?host=&client=` | READ. Rapport HTML autonome (imprimable en PDF), pièce jointe `audit-<hôte>-<aaaammjj>.html`. `client` = nom affiché (facultatif) | **oui 2026-10-14** |
| GET | `/api/v1/backups?host=` | Local. Copies de sauvegarde sur ce poste (`state/backups/<serveur>/`) : `{host, backups:[{file, sizeBytes, sha256, remotePath, copiedAt, check:{ok, noDatabase, databases{base: tables}, applicationTables, problem, checkedAt}, drill:{ok, lines[], drilledAt}, files:{file, sizeBytes, sha256, check:{ok, volumes{volume: fichiers}, files, bytes, problem, checkedAt}}}]}`. `files` = archive des volumes de même horodatage (`files-AAAAMMJJTHHMMSSZ.tar.gz.enc`, BIZ-11), `null` si aucune | **oui 2026-10-14** (`files` : 2026-10-14, BIZ-11) |
| POST | `/api/v1/backups/run` | CHANGE `run-backup`. `{host, port, user, confirm}` : sauvegarde immédiate sur le VPS (tâche détachée), copie SFTP (SHA-256 comparé), vérification. → une copie (forme ci-dessus). `400` + Cause/Où/À faire si le rôle `backups` manque ou si l'export échoue | **oui 2026-10-14** |
| POST | `/api/v1/backups/fetch-latest` | `{host, port, user}` : copie la sauvegarde la plus récente du VPS (et son archive de fichiers) puis la vérifie. Copie reprise là où elle a coupé (liaison lente). Sans confirmation ; `403` sur un serveur en lecture seule ou bloqué | **oui 2026-10-14** |
| POST | `/api/v1/backups/verify` | Local. `{host, file}` : déchiffre et lit toute la copie (marque de fin, tables par base) et l'archive de fichiers (en-têtes tar, fin d'archive, fichiers par volume). Nom de fichier imposé `dump-AAAAMMJJTHHMMSSZ.sql.gz.enc` | **oui 2026-10-14** |
| POST | `/api/v1/backups/drill` | CHANGE `restore-drill`. `{host, port, user, file, confirm}` : chaque base applicative importée dans une base jetable `restore_check_<hex>` du VPS, tables comptées puis base supprimée ; archive de fichiers extraite dans `/var/tmp/autopilot-files-drill-*`, fichiers comptés par volume, dossier supprimé (refus si place insuffisante). → copie avec `drill` | **oui 2026-10-14** |
| POST | `/api/v1/backups/restore-platform` | CHANGE `restore-platform`. `{host, port, user, file, platformId, foreignKeyChecks, confirm}` : remplace `app_<id>` par la base de la sauvegarde et/ou le volume `plat_<id>_uploads` par celui de l'archive, à l'identique (via `platform-restore` : copie de l'existant, retour arrière si échec). `400` si ni la base ni le volume ne sont dans la sauvegarde. → `{status, id, risk, report[]}` | **oui 2026-10-14** (fichiers : BIZ-11) |

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

