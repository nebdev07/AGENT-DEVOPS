# Changelog — VPS Autopilot

> Ne jamais effacer une entrée.  
> Dernière mise à jour : 2026-09-29

## 2026-09-29

- **V7-02** : CHANGE `deploy-platform` — rôle `roles/platform/apply.sh`, compose généré (aucun port public), clones par service, Redis/`db.env`/volume uploads si `needs`, popup confirmation. Aide §7b. Cache `?v=app18`.
- **V7-01** : déclaration de **plateforme** (N services) — `GET/PUT/DELETE /api/v1/platforms`, `state/platforms-intent.json`, bande console **Plateformes**. Aucun CHANGE SSH. Sous-domaines, besoins MariaDB/Redis/volume, `NEXT_PUBLIC_API_URL`. `deploy-platform` toujours V7-02. Aide §7b. Cache `?v=app17`.

## 2026-09-28

- **ADR-036** : plateforme cargo (N services : sites + API + Redis + volumes). CHANGE `deploy-platform` spécifié, pas de code. Cargo 1:1 (ADR-029) inchangé. Scénario de forme = infra type ARC ; exécution = lab seulement (pas l’IP prod). Roadmap **V7** précisée ; backlog `V7-01`…`V7-07`.

## 2026-09-14

- Rôle `deploy-app` : `app.env` (`JOB_SECRET`, `PERSISTENCE_DRIVER`), `DATABASE_URL` dans `db.env`, volume nommé `/app/data` pour persistance cargo.

## 2026-09-09

- Console apps : **Modifier** / **Supprimer** / **Nouvelle fiche** ; messages clairs si un DNS est collé dans Identifiant (pré-correction slug + hôte). `DELETE /api/v1/apps?id=` (liste locale seulement). Cache `?v=app16`.
- ADR-034 : rôle **backups** exécutable (`install-backups`) — dump MariaDB chiffré AES-256-CBC sur le VPS, copie hors-site vers `state/backups/` (poste opérateur), preuve locale de déchiffrement. Passphrase `state/backup.key`. Discovery marqueur `.vps-autopilot-backups`. Cache `?v=app15`.
- Autonomie : Redis installé + testé VPS ; backups V4 (dump chiffré → `state/backups/`, `restoreProofOk=true`) ; vérif HTTPS post-deploy (slice V6) ; ADR-035 executor bash.
- ADR-033 suite autonome : rôle **Redis** exécutable (conteneur data, requirepass, pas de port public). Test VPS : PONG OK. Discovery conteneur `redis`.
- ADR-033 : HTTPS forcé à l’install Traefik + redirect HTTP→HTTPS ; liens console en `https://` ; domaine de base optionnel pour URL mémorisables. Cache `?v=app14`.
- Lien public : si le nom d’hôte d’une 2e app copie celui de `vps-autopilot`, le popup ouvre la page cargo. Refus des hôtes en double à l’enregistrement + blocage au plan. Les deux conteneurs coexistent (pas d’écrasement).
- Build `my-bourse` : échec ESLint (`prefer-const` dans BOURSE), pas le clone. Template Next ignore eslint/tsc cargo ; messages BUILD_FAILED explicites. Cache `?v=app13`.
- ADR-032 : popup confirmation listant les actions ; rattrapage Dockerfile (templates Next/Node/Spring) après confirm. `GET /api/v1/apps/deploy-plan`. Cache `?v=app12`.
- Déployer : échec `my-bourse` mal lu (stderr « Cloning into… » masquait `NEED_DOCKERFILE`). SSH remonte stdout+stderr ; préflight GitHub Dockerfile ; BOURSE n’a pas de Dockerfile racine. Cache `?v=app11`.
- Messages opérateur **Cause / Où / À faire** (enregistrement app + prérequis / échecs Déployer). Erreur formulaire : détail sous Enregistrer ; barre Analyser = pointeur seulement. Cache `?v=app10`.

## 2026-09-08

- Console : 2e app — `my_bourse` refusé (underscore). Normalisation `_`→`-`, erreur sous le formulaire Applications (plus à côté d’Analyser). Cache `?v=app9`.
- ADR-031 : plateforme multi-opérateurs (sessions, paramètres par compte, cockpit public derrière auth) = **V9 spécifié**. Mono-opérateur local jusqu’alors. Aide à jour. Cache `?v=app8`.
- ADR-029 amendé : au `deploy-app`, si MariaDB présent → base/user `app_<slug>` + `db.env` (V3-02).
- Traefik : sur Docker Ubuntu récent, `traefik:v3.3` ne lit plus le socket (API 1.24). Image `v3.5` + `daemon.json` `min-api-version: 1.24` (workaround). Sans ça, apps UP mais 404 public. Console : lien public cliquable après deploy. Cache `?v=app7`.
- Console : coller une URL GitHub dans « Nom d’hôte » → message clair + auto-correction vers le champ Git + sslip. Formulaire prérempli pour le premier cargo. Cache `?v=app6`.
- Console : nom d’hôte ≠ URL Git. Si le champ est vide, proposition `id.<IP>.sslip.io`. Aide §7. Cache `?v=app5`.
- ADR-030 : premier cargo = cet agent. `Dockerfile` racine, profil `cargo` (santé seulement). Cockpit inchangé en local. Aide §7. Cache `?v=app4`.
- Console : menu Docker « 2 restants » sans bouton — les cases sont l’intention, pas l’install. Détails Docker encore cochés (Compose, groupe) → READY + Installer. Detection Compose/`GROUPS=`. Carte Installations au-dessus du menu. Aide §5–§6. Cache `?v=app3`.
- Console : une pile enregistrée avant Compte deploy n’affichait aucun bouton Installer. Les nouvelles sections par défaut sont fusionnées une fois (`catalogSeen`). Installations liste READY en premier. Bandeau Applications si le compte deploy manque. Aide §5–§7. Cache `?v=app2`.
- ADR-029 : CHANGE `deploy-app` (Git HTTPS public, Dockerfile, compose généré, Traefik, pas de port publié). Aide §7. Lab : MariaDB SKIP sur cet hôte.
- ADR-028 : compte `deploy` exécutable (docker, pas sudo, clé agent, AllowUsers). Git → image plus tard. Aide à jour.
- V4 (spec) : dumps chiffrés stockables sur le **poste opérateur** ; restore par l’agent, pas à la main. Pas encore implémenté.
- Console : après une install (composant ou clé SSH), une fenêtre indique succès ou échec, avec la durée et la suite. Aide §6 à jour.
- Console : palette douce (menthe / bleu / sable / gris) et bandes de section (filet gauche + teinte + libellé) pour séparer état, logiciels, pile/install et rapport. Aide §7 à jour.
- Baseline sécurité SKIP sur VPS-2 (`PasswordAuthentication no`). V2-01 Traefik/HTTPS/MariaDB débloquée.
- ADR-023 : déploiements d’apps = module du même `devops-agent` (pas un second agent). Cerveau sur le poste opérateur ; executor déterministe ; apps = cargo.
- Pile « Que doit installer l’agent ? » repliable (fermée par défaut) pour alléger le cockpit.
- Rôle sécurité : drop-in SSH `00-vps-autopilot.conf` (OpenSSH, première valeur gagnante) pour que `PasswordAuthentication no` passe avant cloud-init `50-`. L’ancien `99-` est retiré. Le bouton Installer Sécurité reste tant que le mot de passe SSH est encore accepté.

## 2026-09-07

- Création du dépôt `devops-agent` (produit VPS Autopilot).
- ADR-001 → ADR-011 (produit dédié, risque, Java 21, V0 observation, lab ≠ prod tierce).
- Sprint 1 / V0 : discovery SSH read-only, parseur, état JSON, CLI, API, tests fixtures.
- ADR-012 : déplacement vers `PROJECTS/ME/AGENTS/devops-agent` (catalogue multi-agents, un projet par agent). Ancien chemin `PROJECTS/ME/devops-agent` abandonné.
- ADR-013 / ADR-014 : lab Docker (substitution VPS) + console web statique. Fallback fixture tant que Docker/WSL/VPS absents.
- Alignement doc BRVM/SmartFlow : `documentation.md`, `conventions.md`, `glossary.md`, `api.md`, `backlog.md`, `.cursor/rules.md`.
- Roadmap : V0 marqué **done** (code + console + docs) ; SSH réel reste LAB-01 / VPS-01.
- Console : formulaire hôte / user / port + enregistrement d’empreinte (`POST /api/v1/ssh/accept-host`) pour tester le VPS-2 réel.
- ADR-015 : paramètres VPS (nom, host, user, port, OS). Discovery multi-familles Linux ; Ubuntu n’est plus le seul OS.
- Empreinte SSH via SSHJ (plus `ssh-keyscan` Windows, incompatible OpenSSH 10). Analyse n’affiche plus la fixture après un échec réel.
- ADR-016 : premier CHANGE `install-agent-pubkey` (mot de passe OVH, confirmation, script idempotent). Console : bouton « Installer la clé ».
- Console : mini-tuto SSH (générer / copier / ajouter sur le VPS). Le catalogue OVH n’est plus présenté comme cible.
- Console : accueil produit animé ; tuto déplacé en `#guide` ; cockpit en `#cockpit` ; couleurs d’état (vert / gris / ambre / rouge).
- ADR-017 : pile voulue décochable (`/api/v1/stack`) avec avertissement si ça casse la politique.
- ADR-018 : après analyse, plan d’install + boutons CHANGE (`security`, `docker`).
- Scripts de rôles : fins de ligne normalisées en LF (CRLF Windows cassait `bash -s`).
- Console : plus d’analyse mockée (fixture / 203.0.113.x). Tableau vide tant que les paramètres ne sont pas analysés en SSH.
- Pile voulue après analyse seulement. Pas de bouton Installer si déjà présent. Page Aide `#help`.
- Discovery : fail2ban détecté (`###FAIL2BAN###` : le parseur acceptait `[A-Z]+` et ignorait le `2`). Sécurité = déjà là si fail2ban tourne ou si `fail2ban.service` est dans les services — plus seulement UFW active.
- Console : overlay loader pendant analyse, empreinte, clé SSH et install de composant (titre, description, étapes, durée). Boutons bloqués le temps du traitement.
- ADR-019 : baseline sécurité (UFW + fail2ban + SSH clés seulement). fail2ban seul ≠ SKIP. Discovery : `sudo -n` pour UFW/`sshd -T`, signaux miner/PHP/Apache/Nginx. V2 bloquée tant que READY sécurité.
- ADR-020 : pile par section + détails cochables. Explications (pourquoi / ce que ça fera) dans un menu fermé par défaut. Les rôles n’appliquent que les cases cochées.
- ADR-021 : Nginx hôte retiré de la pile à installer. Bloc « Non proposé » + explication. Toujours détecté à l’analyse s’il est déjà là.
- Détails déjà présents (fail2ban, Docker, …) non cochés par défaut ; mention « déjà installé ». Pas d’écart de politique pour ça.
- fail2ban restait coché : `defaultEnabled` du catalogue ignorait la présence. Le catalogue API pose maintenant `present` + `defaultEnabled=false` si déjà là.
- ADR-022 : textes publics sans noms de projets personnels (console, catalogue, alertes, plan).
