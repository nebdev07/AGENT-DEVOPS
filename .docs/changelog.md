# Changelog — VPS Autopilot

> Ne jamais effacer une entrée.  
> Dernière mise à jour : 2026-10-01

## 2026-10-05

- **ADR-037 — reprise du serveur DTSI `154.0.30.233`** (DISMAS + PRISM derrière Apache :91, SSH :90). `ForbiddenTargetGuard` a désormais deux niveaux : `assertReadAllowed` (analyse, empreinte SSH) et `assertAllowed` (toute modification). `154.0.30.233` est en **lecture seule** ; ARC prod reste interdit dans les deux cas. Stratégie : migration progressive vers Docker + Traefik (inventaire, répétition sur VPS de test, bascule application par application, retrait d'Apache/Tomcat/PM2). Tests : 115 → 116.
- Aide § 3 « Clé SSH » : **méthode D — l'admin du serveur pose la clé** (aucun mot de passe ni accès partagé), bloc prêt à coller en session root, rempli avec la clé publique de l'agent et l'utilisateur du cockpit, chemin absolu `/home/<user>` (ou `/root`), sans doublon, `chown`/`chmod`. Encadré « connecté en root : `~` = `/root` » (erreur constatée sur 154.0.30.233). Commandes PowerShell et de vérification reprennent le **port SSH** (`-p 90`). Le refus « lecture seule » renvoie vers la méthode D. Cache `?v=app29`.
- **Compte dédié à l'agent** (remplace « ubuntu » par défaut, tous les VPS ne sont pas Ubuntu) : la méthode D **crée le compte s'il manque** (`useradd -m`, toutes distributions courantes), lit son vrai dossier (`getent passwd`), pose la clé sans doublon, signale un `AllowUsers` sshd qui bloquerait le compte. Aucun sudo par défaut (suffisant pour l'analyse) ; ligne commentée pour sudo sans mot de passe si l'agent doit installer ou déployer. Nom par défaut `autopilot`, validé (minuscules, chiffres, `-`, `_`). Les comptes d'hébergeur (`ubuntu`, `debian`, `root`…) restent proposés pour la seule méthode A. Cache `?v=app30`. Encadré « Compte qui sera créé » (nom affiché en direct, défaut `autopilot`) : modifiable avant copie via le champ Utilisateur SSH du cockpit, ou après copie en changeant la ligne `U=…`. Cache `?v=app31`.
- ADR-037 amendé : sur ce serveur de développement DTSI, la racine `/` sera un **portail de présentation DTSI** listant les applications hébergées (et non plus une redirection vers DISMAS). Spécifié, pas encore implémenté.

## 2026-10-02

- Correctif **Console SQL** : les colonnes `BIT(1)` (booléens JPA, ex. `users.is_locked`) étaient écrites par `mariadb --xml` en octet brut 0x00/0x01, interdit en XML ; le parseur s'arrêtait au champ fautif et le résultat revenait sans aucune ligne. Ces octets sont désormais lus comme `0`/`1` (autres caractères de contrôle → `�`). Constaté sur 213.156.134.92 (`SELECT … is_locked … FROM users` vide alors que `COUNT(*)` = 12). Tests : 114 → 115.

## 2026-10-01

- **Parcours guidé** (cockpit, par défaut) : barre de 6 étapes (Analyser → Préparer le serveur → Plateforme → Accès Git → Données → En ligne) et une carte « Prochaine étape » avec un seul bouton. Les bandes Installer / Apps et les réglages SSH du rail sont masqués une fois connecté (**Mode expert** et **Options de connexion** pour les réafficher). **Préparer le serveur** : un seul plan confirmé, puis Sécurité, Docker, Traefik, MariaDB et compte deploy enchaînés (ce qui est déjà posé est sauté). Aide §4b. Cache `?v=app23`. Corrections de mise en page du formulaire de service et de l’onglet actif.
- `POST /api/v1/git-deploy-key/repos/verify` `{repo}` : vérifie en lecture seule (SSH `git-upload-pack`, aucun clone) que la Deploy key du dépôt est posée. `GET /api/v1/platforms/status?id=` : test HTTPS (certificat valide) de chaque service, en parallèle.
- `{{HOST}}` n’est plus remplacé à l’enregistrement (obsolète, voir V7-06 ci-dessous) : il reste tel quel dans la fiche et est remplacé **au déploiement**. Après le mapping DNS : changer le nom d’hôte, Enregistrer, Déployer (les `NEXT_PUBLIC_*` sont recompilés).
- **Restauration fiabilisée** (incident 213.156.134.92 : import en échec `ERROR 1452`, services ARC restés arrêtés ~2 h, base à moitié importée). Le rôle `platform-restore` relance désormais les services à la sortie **quel que soit le résultat** (`trap finish EXIT`), pose `.maintenance` pendant l’opération, refuse de remplacer la base si sa sauvegarde échoue (`DB_BACKUP_FAILED`, `pipefail`), et **réimporte la sauvegarde** si l’import échoue (`DB_ROLLED_BACK=`). Nouvelle option **Vérifier les clés étrangères pendant l’import** (cochée par défaut ; `foreignKeyChecks=false` → `SET FOREIGN_KEY_CHECKS=0` en tête et réécriture des `FOREIGN_KEY_CHECKS=1` du dump). Message d’échec : état de la base (remise en place ou non) et, sur 1452/1215/1216, conseil de décocher l’option. Testé sur le VPS avec une plateforme jetable (import valide, échec 1452 + retour arrière, succès option décochée), puis nettoyé.
- **Garde en ligne** : le rôle `platform` installe `vps-autopilot-keepalive` (script + service + minuteur systemd, toutes les 2 min et au démarrage) qui relance les conteneurs de plateforme arrêtés (`up -d --no-build --no-recreate`), sauf si `.maintenance` a moins de 2 h ; journal `journalctl -t vps-autopilot-keepalive`. Installé et testé sur 213.156.134.92 (cache arrêté → relancé en 5 s). Tests : 111 → 114. Cache `?v=app28`.
- **Console SQL** par plateforme : `POST /api/v1/platforms/sql/analyze` (classement READ/CHANGE, avertissement UPDATE/DELETE sans WHERE) et `POST /api/v1/platforms/sql`. `SqlConsolePolicy` (pur) découpe le script hors chaînes/commentaires, garde le code des commentaires exécutables `/*! */`, refuse DROP, TRUNCATE, DELIMITER, GRANT/USE/LOAD…, CREATE/ALTER DATABASE/USER, CREATE OR REPLACE TABLE, SET GLOBAL/PASSWORD, INTO OUTFILE/DUMPFILE, LOAD_FILE, barre oblique inverse hors chaîne. Rôle `platform-sql` : SFTP du script, client `mariadb --binary-mode --xml` dans le conteneur avec l’utilisateur `app_<id>` (`MYSQL_PWD`), `sql_select_limit=1000`, `ROW_COUNT()` après chaque écriture, sortie 4 Mo max. Écriture = CHANGE `sql-write-platform` confirmé, **sauvegarde `mariadb-dump` avant** (20 gardées). UI : section Console SQL (plateforme, fichier .sql, Ctrl+Entrée, Lister les tables, tableau des résultats, NULL distingués), visible en mode expert et à l’étape finale du parcours. Aide §7b. Cache `?v=app27`. Testé sur 213.156.134.92 (base ARC, 46 tables) : lecture, multi-instructions avec erreur, refus DROP (400), écriture sans confirmation (403), écriture confirmée avec sauvegarde (53 Ko). Tests : 102 → 111 (`SqlConsoleTest`).
- Vérification post-déploiement HTTPS : attend jusqu’à 3 min (essai toutes les 10 s) que Let’s Encrypt émette le certificat d’un nouveau nom d’hôte, au lieu d’échouer au premier essai (`PKIX path building failed` constaté sur 213.156.134.92 alors que le certificat a été émis peu après). Message dédié si le certificat reste invalide.
- **Tuto Deploy key** (étape Accès Git) : fenêtre en 5 étapes par dépôt (Générer, Copier, ouvrir la page Deploy keys, maquette du formulaire GitHub/GitLab avec la case d’écriture à laisser décochée, Vérifier), boutons d’action intégrés, enchaînement « Dépôt suivant ». Ouvert automatiquement une fois (`vpsap.tuto.git`), rejouable (« Suivre le tuto », bouton **Tuto** par dépôt). Cache `?v=app24`.
- Correctif MariaDB sur VPS neuf : la pile voulue est commune à tous les serveurs ; si seuls les « grants par projet » étaient cochés (instance déjà présente sur un autre VPS), l’install sortait `NEED_INSTANCE`. Désormais `per-project` entraîne `instance` quand le conteneur manque (`StackPresence.subtract`). Constaté sur 213.156.134.92 pendant « Préparer le serveur ».
- Préréglage ARC : vide le **Domaine de base** pour garder l’adresse `sslip.io` tant qu’aucun domaine n’est mappé.

- **V7-06** livré : modèles Dockerfile par service de plateforme (Next, Node, Spring Boot Java 11/17/21, `Dockerfile.autopilot` écrit sur le serveur), variables de build et d’exécution (`CLE=valeur`, `{{HOST}}`), **Garder le préfixe** (`keepPath`, pas de StripPrefix), **Redis privé** (`platform-cache`, réseau interne). Secrets d’env masqués `********` dans l’API et conservés à l’enregistrement. Volume fichiers au nom fixe `plat_<id>_uploads` (`name:` dans le compose).
- **Clés Git par dépôt** (GitHub refuse la même Deploy key sur deux dépôts) : `GET/POST /api/v1/git-deploy-key/repos`, liste dans la bande Plateformes avec Générer / Copier / lien Deploy keys.
- **Restaurer les données** : `POST /api/v1/platforms/restore` + rôle `platform-restore` (SFTP, sauvegarde de l’existant avant remplacement, import MySQL 8 → MariaDB, archive de fichiers vers le volume). CHANGE `restore-platform` whitelisté. Testé sur le lab (script puis API complète), puis nettoyé.
- Bouton **Remplir le modèle ARC** (3 services, chemins `/`, `/arc-backoffice`, `/arc-api`). Aide §7b complétée. Cache `?v=app22`. Tests : 89 → 101 (`PlatformTemplatesTest`, `PlatformRestoreTest`).

- Rôle **sécurité** : `AllowUsers` ne contient plus seulement l’utilisateur de l’agent. Il garde les utilisateurs déjà autorisés et ajoute chaque membre du groupe `sudo` ayant une clé dans `authorized_keys` (admin humain, ex. `nebdev`, à côté d’un compte agent dédié, ex. `agentuser`). Sortie `SSH_ALLOWED=…`.

- Guide SSH (étape 3) : précise **quelle** clé poser sur le VPS (`vps-autopilot.pub`, une ligne `ssh-ed25519 …`, jamais la privée), dans quel `authorized_keys` (utilisateur de connexion), commande `Get-Content` pour l’afficher, et une **vérification** `ssh -i … ubuntu@IP` sans mot de passe. Cache `?v=app21`.

## 2026-09-29

- **V7-05** : clone Git **privé** — clé Ed25519 lecture seule (`state/git-deploy`, hors git), Deploy key GitHub/GitLab, `git@…` pour apps et plateformes. Cache `?v=app20`. Tests réseau : le preflight « sans Dockerfile » cible désormais `octocat/Hello-World` (BOURSE a maintenant un Dockerfile) ; la détection Next sur BOURSE est ignorée (assumption) si le Dockerfile existe.
- **V7-04** : routage **chemins** (`PathPrefix` + `StripPrefix`) — un DNS, `/` / `/api` / `/admin`. Sous-domaines inchangés. Cache `?v=app19`.
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
