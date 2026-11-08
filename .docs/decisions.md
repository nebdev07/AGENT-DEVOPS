# Décisions d’architecture (ADR) — VPS Autopilot

> Mémoire permanente. **Ne jamais supprimer** une décision : la marquer `Superseded` et en ajouter une nouvelle.  
> Dernière mise à jour : 2026-09-09 (ADR-034 : backups exécutables)

---

## ADR-001 — Produit dédié, pas un module BRVM/SmartFlow

- **Date** : 2026-09-07
- **Statut** : Accepted (emplacement précisé par **ADR-012**)
- **Décision** : Dépôt `devops-agent` séparé des produits métier. Le VPS personnel n’est pas configuré à la main dans un projet métier.
- **Conséquence** : BRVM, SmartFlow, etc. consommeront l’infra ; ils ne la provisionnent pas. Emplacement : `PROJECTS/ME/AGENTS/devops-agent` (ADR-012).

---

## ADR-002 — L’IA planifie, l’executor est déterministe

- **Date** : 2026-09-07
- **Statut** : Accepted
- **Décision** : Pas de shell généré librement par le LLM vers root. Chaîne : plan → validation → Ansible (CHANGE+) ou script whitelist (READ).
- **Conséquence** : V0 n’a pas d’IA. V1 CHANGE passera par des playbooks versionnés.

---

## ADR-003 — Niveaux de risque READ / CHANGE / DESTRUCTIVE

- **Date** : 2026-09-07
- **Statut** : Accepted
- **Décision** : READ auto. CHANGE = plan affiché + confirmation. DESTRUCTIVE = confirmation explicite + liste de ce qui sera détruit.
- **Conséquence** : `ActionGuard` refuse tout ce qui n’est pas READ, sauf CHANGE whitelisté + confirmé (**ADR-016**).

---

## ADR-004 — Idempotence via état

- **Date** : 2026-09-07
- **Statut** : Accepted
- **Décision** : Avant d’installer, lire l’état (fichier JSON + re-discovery). « Docker déjà là et correct » → no-op.
- **Conséquence** : Le snapshot V0 est la première source d’état.

---

## ADR-005 — Java 21 / Spring Boot 3 pour le cerveau

- **Date** : 2026-09-07
- **Statut** : Accepted
- **Décision** : Agent core en Spring Boot 3.3, JDK 21 (`C:\env\java\jdk-21`). SSH client : SSHJ. Ansible arrive au premier module CHANGE.
- **Conséquence** : Maven doit tourner avec `JAVA_HOME=C:\env\java\jdk-21` (le `java` du PATH est encore le 11).

---

## ADR-006 — Secrets hors Git

- **Date** : 2026-09-07
- **Statut** : Accepted
- **Décision** : Host, user, chemin de clé, passphrase : variables d’environnement / `.env` gitignoré. Jamais de clé privée dans le dépôt.
- **Conséquence** : `.env.example` sans valeurs. Host keys : `known_hosts` OpenSSH, pas de trust implicite par défaut.

---

## ADR-007 — V0 observation uniquement

- **Date** : 2026-09-07
- **Statut** : Accepted — amendé par **ADR-016**
- **Décision** : Sprint 1 = SSH + discovery + JSON + rapport. Zéro écriture sur le VPS (pas même un fichier temporaire distant : script via stdin).
- **Conséquence** : Le VPS-2 peut être acheté après ; les tests passent sur fixtures. Exception : installer la clé publique de l’agent (ADR-016).

---

## ADR-008 — Un artefact Maven en V0

- **Date** : 2026-09-07
- **Statut** : Accepted
- **Décision** : Un module `devops-agent`. Extraire `planner` / `executor` / `api` quand le code le demandera, pas avant.
- **Conséquence** : Arborescence logique en packages, pas en 5 jars vides.

---

## ADR-009 — Laboratoire ≠ production tierce

- **Date** : 2026-09-07
- **Statut** : Accepted
- **Décision** : Premier serveur = VPS-2 personnel. Interdit comme cible par défaut : ARC `54.37.156.11`, staging DTSI `154.0.30.233`.
- **Conséquence** : Aucun host SSH par défaut dans `application.yml`.
- **Amendement 2026-10-05 (ADR-037)** : `154.0.30.233` passe en **lecture seule** (analyse permise, aucune modification). ARC prod reste interdit.

---

## ADR-010 — Roadmap infra alignée agent

- **Date** : 2026-09-07
- **Statut** : Accepted
- **Décision** : On n’installe pas Docker/MariaDB/Traefik à la main sur le VPS-2. Ordre : acheter VPS → construire l’agent → l’agent provisionne → audit sécurité manuel → réutiliser l’agent.
- **Conséquence** : Les playbooks Ansible sont le livrable d’infra ; le serveur n’est que l’instance.

---

## ADR-011 — Base de données : une base par projet, pas une BD fourre-tout

- **Date** : 2026-09-07
- **Statut** : Accepted
- **Décision** : MariaDB (ou Postgres si un projet l’exige) avec utilisateurs et grants séparés. Redis seulement si un projet en a besoin.
- **Conséquence** : Module Database V2 ; hors V0.

---

## ADR-012 — Dossier `AGENTS`, un projet Git par agent

- **Date** : 2026-09-07
- **Statut** : Accepted
- **Décision** : Tous les agents personnels vivent sous `PROJECTS/ME/AGENTS/`. Chaque agent est un **projet séparé** (pas un monorepo). `devops-agent` est le premier ; d’autres agents s’ajouteront à côté, pas dedans.
- **Conséquence** : Chemin canonique `d:\PROJECTS\ME\AGENTS\devops-agent`. Catalogue : `AGENTS/README.md`. Ouvrir **ce** dossier dans Cursor pour travailler l’agent DevOps — pas `BOURSE APP`.

---

## ADR-013 — Lab local Docker comme VPS de substitution

- **Date** : 2026-09-07
- **Statut** : Accepted
- **Décision** : Conditions SSH « réelles » = VPS-2 **ou** conteneur Ubuntu + SSH (`infrastructure/lab`). Pas de Docker/WSL sur le PC de dev au 2026-09-07 → fallback **fixture** (même JSON). Jamais ARC/DTSI comme lab.
- **Conséquence** : `scripts/lab-up.ps1`. Console : mode `lab-fixture` tant que `DEVOPS_SSH_*` est vide.

---

## ADR-014 — Console web dans le même artefact Spring (V0)

- **Date** : 2026-09-07
- **Statut** : Accepted
- **Décision** : Interface graphique V0 = pages statiques servies par l’API (`http://127.0.0.1:8088/`). Pas de front Angular/Next séparé tant que le cockpit tient dans cet artefact.
- **Conséquence** : `src/main/resources/static/`. Un front dédié seulement si l’UI dépasse ce cadre.

---

## ADR-015 — Paramètres VPS, OS non figé

- **Date** : 2026-09-07
- **Statut** : Accepted
- **Décision** : L’hôte (IP/DNS) identifie **la machine**. L’OS est un **paramètre** (`auto` ou famille) : Ubuntu, Debian, RHEL, Rocky, Alma, Fedora, Alpine, Amazon Linux. La discovery lit `/etc/os-release` ; un écart déclaré/détecté produit un warning. Windows Server : hors executor bash V0.
- **Conséquence** : Plus de user SSH imposé `ubuntu`. Le lab VPS-2 Ubuntu reste un cas, pas le produit entier.

---

## ADR-016 — Premier CHANGE : installer la clé de l’agent

- **Date** : 2026-09-07
- **Statut** : Accepted
- **Décision** : Le Manager OVH n’injecte pas une clé SSH sur un VPS déjà livré. Le premier CHANGE whitelisté est `install-agent-pubkey` : mot de passe du mail OVH (jamais persisté) + plan affiché + confirmation. Script déterministe, idempotent, une ligne dans `~/.ssh/authorized_keys`. Pas d’autre écriture.
- **Conséquence** : `POST /api/v1/ssh/install-key`. Docker / UFW / fail2ban restent V1 Ansible. DESTRUCTIVE toujours refusé.

---

## ADR-017 — Pile voulue, décochable, politique visible

- **Date** : 2026-09-07
- **Statut** : Accepted
- **Décision** : L’humain choisit les composants **à installer plus tard** (intention locale). Décocher est permis. Si ça contredit la politique (sécurité, Docker, Traefik, backups), l’UI avertit et exige une confirmation. Redis reste optionnel (ADR-011). Rien n’est provisionné en V0.
- **Amendement 2026-09-08** : cocher Nginx hôte n’est plus proposé (**ADR-021**). La phrase « ou si on coche Nginx hôte » est obsolete.
- **Amendement 2026-09-08 (catalogue)** : une entrée `defaultEnabled` absente d’un `stack-intent.json` ancien est fusionnée **une fois** (`catalogSeen`). L’opérateur n’a pas à rouvrir le menu pour cocher Compte deploy. Décocher ensuite n’est pas recassé.
- **Conséquence** : `GET/PUT /api/v1/stack`, fichier `state/stack-intent.json`. L’executor V1+ ne posera que ce qui est coché **et** autorisé. `catalogSeen` mémorise les ids déjà vus.

---

## ADR-018 — Après analyse : plan d’install + rôles versionnés

- **Date** : 2026-09-07
- **Statut** : Accepted
- **Décision** : La discovery produit un **plan** (voulu vs présent). Chaque install est CHANGE (plan + confirmation). Rôles exécutables aujourd’hui : `security`, `docker` — scripts idempotents dans `src/main/resources/roles/` (stdin `bash -s`, pas de shell IA). Traefik / MariaDB / Redis / backups : plan visibles, pas encore exécutables. Nginx hôte : **refusé**. Pont vers Ansible quand `ansible-playbook` sera dispo sur le poste opérateur.
- **Amendement 2026-09-09** : Traefik, MariaDB, deploy, Redis, **backups** (ADR-034) sont exécutables. Nginx hôte reste refusé.
- **Amendement 2026-09-08 (détails restants)** : un composant n’est SKIP que si le moteur/signal est là **et** qu’il ne reste aucun détail coché non détecté. Ex. Docker présent mais Compose / groupe docker encore cochés → READY + bouton Installer (rôle idempotent, `APPLY_DOCKER=0`). Discovery : `GROUPS=$(id -nG)` + corps Docker conservé (Compose).
- **Conséquence** : `GET /api/v1/plan`, `POST /api/v1/install`. Timeout install 180 s. sudo NOPASSWD requis. Carte Installations au-dessus du menu de cases.

---

## ADR-019 — Baseline sécurité V1 avant toute V2 (leçon ARC)

- **Date** : 2026-09-07
- **Statut** : Accepted
- **Décision** : fail2ban seul ne suffit pas. Le composant `security` n’est **complet** (SKIP) que si UFW est actif, fail2ban tourne, `PasswordAuthentication no`, et `PermitRootLogin` `no` / `prohibit-password`. Le rôle applique aussi jail sshd, unattended-upgrades, drop-in sshd (sans changer le port). Discovery lit `ufw`/`sshd -T` via `sudo -n` et signale miner / PHP / Apache / Nginx hôte. **V2 (Traefik, ports 80/443, MariaDB) est bloquée** tant que ce baseline n’est pas SKIP. User `deploy` : pas dans ce script (risque de lockout, CHANGE séparé).
- **Conséquence** : Relancer **Installer Sécurité** est idempotent même si fail2ban est déjà là. Pas de V2 tant que le cockpit montre encore READY pour la sécurité.
- **Amendement 2026-09-08** : OpenSSH prend la **première** valeur. Un drop-in `50-cloud-init.conf` (`PasswordAuthentication yes`) l’emportait sur `99-vps-autopilot.conf`. Le rôle écrit `00-vps-autopilot.conf` et retire l’ancien `99-`.
- **Amendement 2026-09-08 (périmètre)** : SKIP/READY s’évaluent **par hôte** (dernier snapshot). La roadmap produit (V1, V2…) décrit les traitements disponibles pour **toute** IP, pas l’état d’un lab. Un VPS neuf voit Sécurité en READY ; le même rôle s’applique. Voir **ADR-025**.

---

## ADR-020 — Détails cochables + explications repliées

- **Date** : 2026-09-08
- **Statut** : Accepted
- **Décision** : La pile voulue se coche par **section** (Sécurité, Docker, …) et par **détail** (UFW, fail2ban, SSH durci, …). Chaque bloc a un texte *pourquoi* + *ce que l’agent fera*, dans un `<details>` **fermé par défaut**. L’intention (`state/stack-intent.json`) stocke `enabled` et `details`. Le rôle bash n’applique que les drapeaux cochés. Décocher un détail ARC (UFW, fail2ban, SSH durci) = écart de politique, comme décocher la section.
- **Amendement 2026-09-08** : les cases de détail d’un composant sont dans un `<details>` **fermé par défaut** (comme les textes pourquoi / ce que ça fera). Déplier pour cocher. L’état ouvert est conservé après enregistrement.
- **Amendement 2026-09-08 (cockpit)** : toute la section « Que doit installer l’agent ? » est elle-même dans un `<details>` fermé par défaut, pour ne pas encombrer.

---

## ADR-021 — Nginx hôte n’est pas une option d’install

- **Date** : 2026-09-08
- **Statut** : Accepted
- **Décision** : Si ce n’est pas sécurisé, on ne le propose pas. Nginx (ou Apache) sur l’hôte avec un webroot n’apparaît plus dans la pile à cocher. C’est le modèle ARC. Mention **« Non proposé »** + explication repliée. La discovery peut encore le **détecter** (alerte). Reverse-proxy prévu : Traefik dans Docker.
- **Conséquence** : `StackCatalog.all()` sans `nginx`. `notOffered()` alimente l’UI. Un `nginx` dans un ancien `stack-intent.json` est ignoré.

---

## ADR-022 — Copie publique sans projets personnels

- **Date** : 2026-09-08
- **Statut** : Accepted
- **Décision** : L’interface, l’aide, les messages API et le catalogue vus par l’utilisateur ne citent **aucun** projet personnel (noms, domaines, IP). Les textes parlent de risques génériques (webshell, miner, brute-force). La mémoire interne (ADR, `.docs`) peut garder le contexte d’origine. Le blocage de cibles reste technique, le message utilisateur est « Cette cible n’est pas autorisée ».
- **Conséquence** : Console, `StackCatalog`, alertes discovery, plan d’install : formulation publique.

---

## ADR-023 — Déploiement d’apps : même produit, pas un second agent

- **Date** : 2026-09-08
- **Statut** : Accepted
- **Décision** : Git → image → Traefik (V3) reste un **module de `devops-agent`**. On ne crée pas `AGENTS/deploy-agent`. Un seul cerveau (plan, confirmation, state, garde de risque, SSH) ; les apps métier restent des dépôts **cargo**. Un agent sous `AGENTS/` = un autre métier, pas un clone DevOps.
- **Où ça tourne** :
  1. **Aujourd’hui** : console Spring Boot sur le poste opérateur → SSH → rôles versionnés sur le VPS.
  2. **Ensuite (même produit)** : user `deploy` sur le VPS (docker, pas root, pas de LLM). L’executor peut vivre là ; le plan et la confirmation restent hors du VPS public.
  3. **Plus tard** : CI (GitHub Actions, etc.) *déclenche* `POST /api/v1/…` confirmé, elle n’invente pas le shell.
- **Quand extraire** : seulement si le monolithe le justifie (ADR-008) — ex. executor 24/7 sur le VPS, ou plusieurs opérateurs. On extrait `executor`, on ne forke pas un nouvel agent.
- **Conséquence** : pas de logique de provision/deploy dans les repos d’applications. Pas d’IA en root sur le VPS. Traefik (V2) est le prérequis d’entrée publique avant tout déploiement d’app.

---

## ADR-024 — Traefik exécutable après SKIP sécurité

- **Date** : 2026-09-08
- **Statut** : Accepted
- **Décision** : Le rôle `traefik` est un CHANGE whitelisté (`install-traefik`). Il ne passe en READY que si le baseline sécurité est SKIP **et** Docker est présent. Il pose : réseau Docker `proxy`, compose versionné sous `/opt/vps-autopilot/traefik`, conteneur `traefik` (`exposedByDefault=false`, pas de dashboard public), UFW 80 (et 443 si HTTPS coché). Let’s Encrypt est préparé au proxy ; **aucune app n’est exposée** tant qu’un routeur n’est pas déclaré (V3).
- **Amendement 2026-09-08 (Docker API)** : Docker Engine 29+ (Ubuntu récent) impose MinAPI 1.44. Traefik ≤ 3.5 parle encore en 1.24 via le socket → provider Docker mort, **404** sur les apps alors qu’elles tournent. Le rôle Traefik pose `min-api-version: 1.24` dans `/etc/docker/daemon.json` + image `traefik:v3.5`.

---

## ADR-025 — SKIP/READY par hôte ; roadmap = traitements du produit

- **Date** : 2026-09-08
- **Statut** : Accepted
- **Décision** : Le plan (SKIP, READY, PLANNED, REFUSED) se calcule uniquement sur le **dernier snapshot de l’IP analysée**. SKIP = déjà conforme **sur ce serveur**. Un visiteur qui saisit une autre IP reprend le même catalogue (sécurité, Docker, Traefik, …) : s’il manque, le bouton Installer apparaît. La **roadmap V0→V8** suit les capacités du produit (quels rôles existent), pas l’état du lab. Le lab sert à valider un traitement avant de le considérer implémenté dans la roadmap.
- **Conséquence** : Ne pas écrire « V1 terminé » parce qu’un serveur de test est SKIP. Un rôle reste dans la roadmap tant qu’il n’est pas livré dans l’agent. Chaque hôte a son `state/<host>.json`.

---

## ADR-026 — MariaDB exécutable (conteneur interne)

- **Date** : 2026-09-08
- **Statut** : Accepted
- **Décision** : Le rôle `mariadb` est un CHANGE whitelisté (`install-mariadb`). READY seulement si Docker est présent **et** le baseline sécurité de **cet** hôte est SKIP. Conteneur `mariadb:11.4`, réseau Docker `data`, **aucun port publié**. Mot de passe root généré une fois dans `/opt/vps-autopilot/mariadb/root.pw` (mode 600, hors git). Les bases/users par projet restent au déploiement d’app (V3). Pas de `apt mariadb-server` sur l’hôte.
- **Conséquence** : Discovery = conteneur nommé `mariadb` (pas le binaire hôte). Signal `MARIADB_HOST` si apt/mysqld est déjà là.

---

## ADR-027 — Guide Aide mis à jour à chaque changement UI / fonction

- **Date** : 2026-09-08
- **Statut** : Accepted
- **Décision** : La page **Aide** (`#help` dans `index.html`) est le guide utilisateur. Toute livraison qui change l’interface ou une fonction l’amende **dans le même chantier**, sans demande explicite. Règle Cursor `devops-user-guide.mdc`. Copie publique (ADR-022). SKIP expliqué comme « déjà là sur ce VPS ».
- **Conséquence** : Une PR / un chantier UI sans Aide à jour est incomplet. `.docs` reste la mémoire technique ; l’Aide est pour l’opérateur.

---

## ADR-028 — Compte `deploy` (premier CHANGE V3)

- **Date** : 2026-09-08
- **Statut** : Accepted
- **Décision** : Avant de déployer une app, l’agent pose un compte système `deploy` (CHANGE `install-deploy`). READY si Docker est présent **et** le baseline sécurité de **cet** hôte est SKIP. Le compte : groupe `docker`, **pas** sudo, mot de passe verrouillé, clé de l’agent copiée dans `~deploy/.ssh/authorized_keys`. `AllowUsers` du drop-in SSH inclut `deploy` **en plus** du compte actuel (pas de réouverture du mot de passe). Aucune app n’est lancée.
- **Conséquence** : `roles/deploy/apply.sh`. Git → image → Traefik reste un CHANGE suivant. Pas de déploiement à la main. Le SSH opérateur (ubuntu/root) reste le compte des CHANGE d’infra.

---

## ADR-029 — Déploiement d’app : Git → image → Traefik

- **Date** : 2026-09-08
- **Statut** : Accepted
- **Décision** : CHANGE whitelisté `deploy-app`. L’opérateur déclare slug, URL Git **HTTPS publique**, ref, nom d’hôte, port interne. READY si **ce** VPS a : baseline sécurité SKIP, Docker, Traefik, compte `deploy`. L’agent clone sous `/opt/vps-autopilot/apps/<slug>/src`, **génère** le compose (réseau `proxy`, labels Traefik, **aucun port publié**). Le dépôt cargo n’a besoin que d’un `Dockerfile` à la racine — pas de logique de provision (ADR-023). Dépôts privés / SSH Git : pas encore.
- **Amendement 2026-09-08 (grants)** : si le conteneur `mariadb` et le réseau `data` sont présents, le même CHANGE crée une base/user `app_<slug>` (tirets → `_`), mot de passe dans `apps/<slug>/db.env` (600), injecté via `env_file` du compose. Sans MariaDB : déploiement app inchangé (pas d’échec).
- **Conséquence** : `roles/app/apply.sh` + intention locale `state/apps-intent.json`. DNS du nom d’hôte vers le VPS = charge de l’opérateur. HTTP (entrypoint `web`) d’abord ; certificat Let’s Encrypt sur `websecure` si Traefik HTTPS est déjà là.
- **Amendement 2026-09-28** : le cargo 1:1 reste le défaut (Bourse, preuve Autopilot). Un **produit à plusieurs services** (sites + API) n’est plus un second `deploy-app` bricolé : voir **ADR-036** (V7).

---

## ADR-030 — Premier cargo : cet agent, sans CHANGE public

- **Date** : 2026-09-08
- **Statut** : Accepted
- **Décision** : Le premier déploiement Git→image→Traefik est **ce dépôt** (`devops-agent`). Le cerveau (cockpit, confirmation, CHANGE) reste sur le poste opérateur (`127.0.0.1:8088`). L’instance sur le VPS démarre avec le profil Spring `cargo` (Dockerfile) : page de preuve + `/api/v1/health` seulement. Aucun discover / install / deploy depuis Internet. Pas d’auth à inventer pour ouvrir l’API admin.
- **Conséquence** : `Dockerfile` à la racine (port 8080). Dépôt **HTTPS public** obligatoire (ADR-029). Ce workspace n’a pas encore d’`origin` : pousser avant le bouton Déployer. Identifiant prévu : `vps-autopilot`.
- **Amendement 2026-09-08** : le mode cargo reste la preuve **tant que** la plateforme multi-opérateurs (ADR-031) n’est pas livrée. Le cockpit public derrière auth remplacera progressivement cette page, sans rouvrir les CHANGE anonymes.
- **Amendement 2026-10-17 (ADR-046)** : le profil Spring `platform` ouvre le **cockpit derrière login** (RBAC). Le profil `cargo` reste la preuve anonyme (santé + page). Les CHANGE publics anonymes restent **interdits**.

---

## ADR-032 — Rattrapage cargo + popup de confirmation

- **Date** : 2026-09-09
- **Statut** : Accepted
- **Contexte** : L’opérateur veut coller une URL Git et déployer sans écrire Dockerfile / compose. Le client ne configure pas le packaging. Il faut quand même un CHANGE explicite (ADR-003 / ADR-002).
- **Décision** :
  1. Avant `deploy-app`, la console charge un **plan** (`GET /api/v1/apps/deploy-plan`) et affiche un **popup** listant chaque action (clone, rattrapage éventuel, build, Traefik).
  2. Confirmation = bouton « Confirmer le CHANGE » (plus le `window.confirm` navigateur pour ce flux).
  3. Si Dockerfile absent et stack reconnue (Next / Node / Spring Boot) : **template déterministe** injecté sur le VPS après clone (`APP_SCAFFOLD_B64`), port ajusté si besoin. Pas de shell généré par LLM.
  4. Le fichier injecté n’est **pas** poussé automatiquement dans Git (reproductibilité : à recopier plus tard ; push Git = chantier suivant).
  5. Stack inconnue sans Dockerfile → plan **bloqué**, bouton Confirmer désactivé.
- **Conséquence** : l’utilisateur ne remplit pas de config packaging ; il lit et confirme. Amendement soft d’ADR-029 (Dockerfile peut être fourni par rattrapage confirmé).

---

## ADR-031 — Plateforme multi-opérateurs (sessions, paramètres par compte)

- **Date** : 2026-09-08
- **Statut** : Accepted — **implémentation démarrée 2026-10-17** (détail ADR-046) ; ~~spécifié ; pas encore implémenté~~
- **Contexte** : L’attente produit est qu’un utilisateur se connecte, ait **sa** session, et ne voie / ne pilote que **ses** VPS et paramètres. Aujourd’hui l’outil est mono-opérateur local (poste + `state/` local). Le lien public cargo n’est pas ce cockpit.
- **Décision** :
  1. **Phase actuelle (V0–V3)** : un opérateur, console sur le poste, CHANGE après confirmation. Cargo public = preuve uniquement (ADR-030).
  2. **Phase plateforme (V9)** : comptes + sessions ; chaque opérateur fournit ses paramètres (hôte SSH, user, apps) **valables pour lui seul** ; isolation des secrets ; cockpit joignable en ligne **uniquement après authentification**.
  3. Pas de CHANGE anonymes sur Internet. Auth avant plan/confirmation.
  4. L’executor peut vivre sur un serveur dédié ou le VPS lab ; le modèle de risque (READ / CHANGE / DESTRUCTIVE) ne change pas.
- **Non-objectifs V9 jour 1** : marketplace, multi-org complexe, facturation.
- **Conséquence** : roadmap **V9** ; backlog `PLAT-*`. Aucun code multi-tenant tant que V3 (grants) / V4 (backups) utiles au mono-opérateur ne bloquent pas. ADR-030 reste valide jusqu’à la première livraison auth.
- **Amendement 2026-10-17** : la première livraison V9 est le **MVP RBAC** décrit dans **ADR-046** (rôles éditables, assignation serveur, exécuteur = instance `platform`). La console locale sans auth (`127.0.0.1`, profil défaut) reste pour l’exploitation mono-poste ; elle n’est pas la forme « prod publique ».

---

## ADR-033 — HTTPS par défaut + liens publics mémorisables

- **Date** : 2026-09-09
- **Statut** : Accepted
- **Contexte** : L’opérateur veut du SSL dès l’install Traefik, et des URL simples (pas seulement `slug.IP.sslip.io` en HTTP).
- **Décision** :
  1. Install Traefik : **HTTPS toujours** (`APPLY_HTTPS=1`), UFW 443, Let’s Encrypt, **redirection HTTP→HTTPS** sur l’entrypoint `web`.
  2. Liens console / popup : schéma **`https://`**.
  3. **Domaine de base** optionnel (`state/public-domain.json`, UI Applications) : suggestion `https://<id>.<base>/`. DNS wildcard à la charge de l’opérateur. Sans base : fallback lab sslip (déjà compatible Let’s Encrypt).
  4. Pas de génération de domaine magique hors DNS contrôlé par l’utilisateur.
- **Conséquence** : réinstaller Traefik (ou ré-appliquer le rôle) pour activer la redirection sur un lab déjà installé. Apps déjà déployées : le cert LE fonctionne si le Host est public.

---

## ADR-035 — Executor bash accepté (Ansible différé)

- **Date** : 2026-09-09
- **Statut** : Accepted
- **Contexte** : ADR-002 vise Ansible pour les CHANGE. Les rôles `src/main/resources/roles/*/apply.sh` couvrent déjà security→backups de façon déterministe et testée sur VPS réel.
- **Décision** : Tant que les playbooks Ansible ne sont pas requis par une contrainte produit, **les scripts bash versionnés restent l’executor**. Ansible = chantier optionnel de convergence, pas un bloqueur de roadmap V2–V4.
- **Conséquence** : V1-01 reste Open (Ansible) mais n’empêche pas de clôturer les capacités infra. `infrastructure/ansible/README.md` reste le point d’atterrissage.

---

## ADR-034 — Backups chiffrés exécutables (dump + opérateur)

- **Date** : 2026-09-09
- **Statut** : Accepted
- **Contexte** : V4 exige un filet 3-2-1. Un dump seulement sur le VPS ne protège pas contre compromission. L’option hors-site retenue est le **poste opérateur** (pas un bucket cloud jour 1).
- **Décision** :
  1. CHANGE whitelisté `install-backups` (après baseline sécurité SKIP).
  2. Sur le VPS : `/opt/vps-autopilot/backups/` — si conteneur `mariadb` : `mysqldump` via `docker exec` + gzip ; chiffrement `openssl enc -aes-256-cbc -pbkdf2` ; plaintext effacé ; marqueur `.vps-autopilot-backups`.
  3. Passphrase générée/chargée dans `state/backup.key` (32 hex, hors git) injectée par `InstallService` (pas dans `RoleScript` seul).
  4. Hors-site : l’agent télécharge le `.enc` (SSH `base64`) vers `state/backups/` + métadonnées `.last-backup`.
  5. Preuve minimale : déchiffrement local + magic gzip/sql → `RESTORE_PROOF_OK` dans la réponse API. Restore SQL complet = chantier suivant.
- **Conséquence** : discovery lit le marqueur ; pile backups devient SKIP après premier apply réussi. Relance périodique / restore complet : backlog V4 suite.
- **Amendement 2026-10-14** : points 2 (`mysqldump`), 4 (copie `base64`, `.last-backup`) et 5 (preuve minimale) **obsolètes, remplacés par ADR-041**. `mysqldump` est absent de `mariadb:11.4` : les sauvegardes produites par ce rôle contenaient le message d'erreur Docker chiffré, et la preuve minimale les acceptait.

---

## ADR-036 — Plateforme cargo (plusieurs sites + API)

- **Date** : 2026-09-28
- **Statut** : Accepted (V7-01/02/04/05 livrés ; templates V7-06 ouverts)
- **Contexte** : V3 déploie **un** dépôt → **un** conteneur → **un** `Host()`. Ça suffit pour Bourse. Ça ne décrit pas une infra du type ARC : site public, backoffice, API Spring, Redis, volume fichiers, un même produit. Coller trois cargos indépendants oblige l’opérateur à recâbler à la main les URL, Redis et les chemins. V7 (« multi-projets ») était trop vague : plusieurs **produits** 1:1 sur un VPS existent déjà (liste d’apps) ; ce qui manque est un **produit multi-services**.
- **Décision** :
  1. **Deux formes de cargo, un seul agent** (ADR-023). Pas de nouvel agent sous `AGENTS/`.
     | Forme | Contrat | Exemple |
     |---|---|---|
     | **App** (ADR-029) | 1 Git, 1 Dockerfile racine, 1 hôte, 1 volume `/app/data` | Bourse, preuve `vps-autopilot` |
     | **Plateforme** (cette ADR) | N services, un CHANGE, réseaux/DB/Redis/volumes partagés | ARC *en lab*, futurs SaaS |
  2. **Objet `platform`** (intention locale `state/platforms-intent.json`) : `id`, domaine de base, mode de routage, liste `services[]`. Chaque service : `id`, Git (URL + ref), port interne, `context` Docker optionnel (sous-dossier d’un monorepo), `needs` (`mariadb` \| `redis` \| `volume:<nom>`), vars de build (`NEXT_PUBLIC_*`).
  3. **CHANGE whitelisté `deploy-platform`**. Plan + popup + confirmation, comme `deploy-app`. L’executor **génère** un compose sous `/opt/vps-autopilot/platforms/<id>/` : N services, réseau `proxy` + `data`, **aucun port publié**. Pas de shell inventé par l’IA. Pas d’Apache / Nginx / PM2 / Tomcat **hôte** (ADR-021 inchangé).
  4. **Routage** : sous-domaines (`Host(www.…)` / `Host(api.…)` / `Host(admin.…)`) **ou** chemins sur un seul hôte (`PathPrefix` + `StripPrefix`, livré V7-04). Les apps derrière un chemin écoutent à la racine du conteneur (préfixe retiré). Défaut UI : sous-domaines.
  5. **Redis** : si un service `needs: redis` et le conteneur `redis` est SKIP, injecter `REDIS_HOST=redis` + mot de passe (fichier plateforme, mode 600) via `env_file`. Aujourd’hui Redis s’installe mais n’est **pas** branché aux apps : c’est un trou à fermer ici, pas un rôle séparé.
  6. **Volumes** : `/app/data` reste le défaut d’une app 1:1. Une plateforme peut déclarer des volumes nommés (`uploads` → chemin dans le conteneur). Traefik ne sert pas de PHP sur un webroot.
  7. **Git** : HTTPS public (comme V3) **ou** SSH `git@github.com` / `gitlab.com` avec une **clé lecture seule** locale (`state/git-deploy`, livré V7-05). Pas de jeton dans l’URL. Requis association / DTSI-forme ; **pas** pour ouvrir la prod ARC.
  8. **Cible d’exécution** : VPS lab Autopilot seulement. Prod ARC et staging DTSI restent **interdites** (`ActionGuard`). Le scénario ARC sert de **spécimen** (forme d’infra), pas de premier bouton Déployer.
  9. **Packaging** : chaque service a un Dockerfile (racine du repo **ou** `context` monorepo). Le rattrapage ADR-032 reste pour l’app 1:1. Pour une plateforme, les templates (Next + build-args, Spring **Java 11** WAR/JAR) sont des Dockerfiles **dans le cargo** ou des templates versionnés **explicitement choisis** dans le plan — pas un pom racine GitHub magique.
  10. **Unité de déploiement** : ~~un CHANGE met à jour **toute** la plateforme (ordre : API puis fronts…). Redeploy d’un seul service = chantier suivant.~~ **Amendement 2026-11-02 (V7-07)** : le CHANGE full plateforme reste l’action « Tout déployer ». On peut aussi cibler **un seul service** (`service` dans `POST /platforms/deploy`) : clone + `docker compose up -d --build <service>` ; les autres services ne sont pas reconstruits. Un seul déploiement CHANGE à la fois **par hôte** (`DeployLock`, `409 DEPLOY_BUSY`) pour ne pas croiser deux builds.
- **Non-objectifs jour 1** : Kubernetes, Apache hôte, pointer l’agent sur `arc-nwanyo.org` prod, migrer Tomcat existant sans images, multi-opérateurs (V9).
- **Conséquence** : roadmap **V7** précisée ; backlog `V7-01`… ; module architecture « Plateforme cargo ». L’app 1:1 ne disparaît pas. Premier lab plateforme = copie / maquette sur VPS-2, jamais l’IP ARC.

---


## ADR-037 - Reprise du serveur DTSI 154.0.30.233 (lecture seule d'abord, puis migration)

- **Date** : 2026-10-05
- **Statut** : Accepted
- **Contexte** : `154.0.30.233` héberge plusieurs applications DTSI derrière un seul Apache (port 91, SSH port 90) : **DISMAS** (Next.js 15 sous PM2 :3002 `/dismas` + WAR Spring Boot 2.4 / Java 11 dans Tomcat `/dismas-api`, MySQL `db_dismas`, Redis) et **PRISM / SIM-AENF** (Angular 17 statique `/var/www/dcspa` servi sous `/dcspa` + WAR Spring Boot 4 / Java 21 dans Tomcat `:8081/prism`, MySQL `prism_bd`). `/` redirige vers `/dismas/`. D'autres applications sont prévues. Déploiement actuel manuel (scp, rsync, redémarrage Tomcat, PM2).
- **Décision** :
  1. **Amende ADR-009** (sans l'annuler) : `154.0.30.233` passe d'« interdit » à **lecture seule**. `ForbiddenTargetGuard.assertReadAllowed` autorise l'analyse et la lecture de l'empreinte SSH ; `assertAllowed` (installation, déploiement, restauration, SQL, pose de clé SSH) refuse toujours. ARC prod `54.37.156.11` / `arc-nwanyo.org` reste interdit en lecture comme en écriture.
  2. **Stratégie retenue : migration progressive** vers le modèle de l'agent (Docker + Traefik, une plateforme par application), pas de pilotage durable d'Apache / Tomcat / PM2 hôte.
     - Phase 0 : inventaire lecture seule (vhosts Apache, instances Tomcat, PM2, bases MySQL, ports, disque).
     - Phase 1 : répétition sur un VPS de test (PRISM puis DISMAS en plateformes, données de démo).
     - Phase 2 : sur le serveur, Docker + Traefik **à côté** d'Apache, bascule application par application, Apache gardé en repli.
     - Phase 3 : retrait d'Apache, Tomcat et PM2 hôte une fois toutes les applications migrées.
  3. Le passage en écriture (phase 2) exige un **nouvel ADR** qui retire `154.0.30.233` de `READ_ONLY`.
  3b. **Amendement 2026-10-05 (produit grand public)** : les listes `blocked` / `read-only` ne sont plus dans le code mais dans `state/agent-local.yml` (non versionné) de chaque opérateur ; `ForbiddenTargetGuard` les lit via `devops.guard.*`.
  4. **Racine `/` = portail DTSI** (amendement 2026-10-05, demande opérateur) : ce serveur est le **serveur de développement de la DTSI**. `/` ne redirige plus vers une application (aujourd'hui 301 → `/dismas/`) mais affiche une page de présentation de la DTSI listant les applications hébergées (DISMAS, PRISM / SIM-AENF, suivantes) avec leur lien. Spécifié, pas encore implémenté : page statique servie par Traefik sur `PathPrefix(/)` avec la priorité la plus basse, liste générée à partir des plateformes déclarées sur le serveur, mise à jour à chaque Déployer.
  5. **Amendement 2026-10-05 après inventaire (phase 0 faite)** — constats en lecture seule (`state/dtsi-devvps-inventaire-20261005.report.txt`, non versionné) et décisions opérateur :
     - **Entrée web publique = port 91 uniquement** (NAT DTSI 91 → 80 interne, comme 90 → 22 pour SSH). 80 public n'aboutit pas au serveur, 443 ne répond pas, aucun certificat, aucun nom DNS. Décision : **HTTP sur `154.0.30.233:91`**, routage Traefik par chemin (`PathPrefix`, sans règle `Host`), pas de Let's Encrypt sur ce serveur. Les URLs actuelles (`/dismas/`, `/dcspa/login`, `/dismas-api/`, `/dcspa/api/` → `/prism/api/`) sont conservées.
     - **Copies ARC mortes** (`/var/www/ui`, `/var/www/admin`, PM2 :3000/:3001 arrêtés, 503) : **abandonnées**, non migrées.
     - **DISMAS se déploie par GitHub Actions** (push `main` → SSH → `ci-cd/deploy-vps.sh` : pull, build, PM2). Décision : **remplacé par l'agent**, action à désactiver au moment de la bascule de DISMAS (sinon elle réécrit l'hôte).
     - `prism.service` (`java -jar /opt/prism/prism.war`) **plante en boucle** (≈ 666 000 redémarrages), doublon de `tomcat-prism` ; journal systemd 3,9 Go. MySQL écoute sur `0.0.0.0:3306`. Dumps SQL dans des dossiers personnels. Disque 76 % (8,8 Go libres).
     - **Export des applications (phase 1)** : sudo **temporaire** sans mot de passe pour le compte de l'agent, posé et retiré par l'admin, utilisé **uniquement pour lire** (mysqldump, archives, configs) en flux vers le PC (`state/exports/`, non versionné), rien écrit sur le serveur. L'agent reste en lecture seule (le garde n'est pas modifié). Snapshot de la VM demandé à l'équipe infra DTSI (l'agent ne fait pas de snapshot d'hyperviseur).
     - **Chemin de bascule précisé (phase 2)** : conteneurs d'abord sur ports internes, Apache redirige vers eux application par application ; Traefik remplace Apache en dernier, dans une fenêtre courte.
- **Conséquence** : la pose de la clé de l'agent sur ce serveur se fait à la main (le bootstrap SSH écrit `authorized_keys`, donc refusé). Manques à combler avant la phase 2 : ~~modèle Dockerfile Angular statique~~ (fait, ADR-038), ~~Traefik coexistant avec un Apache qui tient déjà des ports~~ (remplacé : bascule franche, ADR-038), ~~inventaire des services hôtes~~ (fait 2026-10-05, point 5), redirection `/` vers une application (remplacée par le portail DTSI, point 4), base MySQL hôte existante (import via « Restaurer les données »), ~~routage HTTP sans certificat sur un port non standard~~ (fait, ADR-038).
- **Amendement 2026-10-05** : les phases 2 et 3 sont fusionnées par ADR-038 (bascule franche, ancienne installation arrêtée mais gardée pour retour arrière).

## ADR-038 - DTSI 154.0.30.233 en écriture : réinstallation en conteneurs, profil serveur

- **Date** : 2026-10-05
- **Statut** : Accepted
- **Contexte** : export complet fait (ADR-037 point 5 : bases, sources, WAR, configs dans `state/exports/`, empreintes SHA-256 vérifiées). L'opérateur demande de tout réinstaller en conteneurs pilotés par l'agent, en une fois, avec tests fonctionnels à la fin, et accepte de lever la lecture seule. Le snapshot de VM reste hors de portée de l'agent (hyperviseur KVM sans agent invité).
- **Décision** :
  1. **Lecture seule levée** : `154.0.30.233` est retiré de `devops.guard.read-only-hosts` dans `state/agent-local.yml` de ce poste. Toute action reste un CHANGE avec plan + confirmation. ARC prod reste interdit (inchangé).
  2. **Bascule franche** au lieu de la cohabitation : Apache, Tomcat (`tomcat`, `tomcat-prism`), `prism.service` et PM2 sont **arrêtés et désactivés, pas désinstallés** ; MySQL hôte reste en place (données d'origine intactes). Traefik prend le port 80 interne (91 public). Retour arrière = arrêter la pile Docker, réactiver les services hôtes.
  3. **Profil serveur** (réglage local de l'agent, par serveur connu, `state/known-servers.json`) : `httpOnly` (Traefik sans 443 ni ACME ni redirection, routeurs `web` seuls, URLs en `http://`), `publicPort` (port tapé dans le navigateur quand la passerelle traduit, ex. 91), `sharedServer` (la sécurité n'applique pas `ssh-harden` : les autres comptes gardent leur connexion SSH par mot de passe). Valeurs par défaut = comportement précédent (HTTPS, port standard, durcissement SSH). API `PUT /api/v1/servers/profile`, cases à cocher sous chaque serveur dans « Mes serveurs ».
  4. **Routage par chemin seul quand l'hôte est une IP** : la règle Traefik devient `PathPrefix(...)` sans `Host(...)`, pour que la même URL marche par l'adresse publique NAT et l'adresse interne. Avec un nom de domaine, la règle reste `Host && PathPrefix`.
  5. **Chemins à plusieurs segments** (3 max, ex. `/dcspa/api`) : une application peut garder son API sous son propre chemin. Traefik préfère la règle la plus longue.
  6. **Modèle Dockerfile `static`** : build Node (variables `STATIC_BUILD`, `STATIC_DIST`), servi par nginx en conteneur (pas d'nginx hôte), repli SPA, base href conservée si « Garder le préfixe ».
  7. **Analyse par serveur** : plan d'installation, installation et déploiement de plateforme lisent la dernière analyse **du serveur visé** (`StateStore.load(host)`), plus la dernière analyse tous serveurs confondus.
  8. **Déploiement GitHub Actions de DISMAS** : déclencheur `push` retiré des deux dépôts (lancement manuel seulement, pour l'ancienne installation) avant le premier push, sinon il réécrirait l'hôte.
  9. **Choix opérateur (2026-10-05)** : pare-feu UFW activé (SSH 22 + web 80 seulement ; MySQL 3306 et Tomcat 8080/8081 fermés au réseau) ; journal systemd réduit à 200 Mo avant les builds (≈ 3,5 Go libérés sur 8,8 Go libres).
  10. **Plusieurs plateformes sur un même hôte** en routage par chemins (ici DISMAS et PRISM sur l'IP) : autorisé tant qu'aucun chemin n'est pris deux fois ; un hôte en sous-domaines ou une app 1:1 reste exclusif.
- **Conséquences** : le sudo temporaire de l'agent doit être actif pendant le chantier (Docker, Traefik, MariaDB, arrêt des services hôtes). Les dépôts privés DISMAS exigent une clé de déploiement lecture seule par dépôt. Le portail `/` (ADR-037 point 4) reste spécifié, non implémenté : en attendant, `/` répond 404.
- **Amendement 2026-10-05 — Statut : Suspended (pour ce serveur)**. Constat : la passerelle du réseau DTSI bloque tout accès internet sortant du serveur depuis environ fin juillet 2026 (le DNS répond ; apt, `docker pull`, `git fetch`, Maven et npm échouent). Aucune image ni dépendance ne peut être tirée : la réinstallation en conteneurs est impossible. Décision opérateur : **garder l'installation existante** (Apache, Tomcat, PM2, MySQL hôte), **réactiver les déploiements GitHub Actions DISMAS** (déclencheur `push` sur `main` remis dans les deux dépôts ; ils échoueront à `git fetch` tant que la sortie internet reste fermée) et **sécuriser le serveur sans internet**.
  - Point 1 annulé : `154.0.30.233` est **remis en lecture seule** dans `state/agent-local.yml`.
  - Points 2 et 8 annulés (pas de bascule ; workflows DISMAS à nouveau automatiques).
  - Point 9 amendé : UFW reste actif, mais MySQL 3306 et Tomcat 8080 restent ouverts **aux réseaux privés seulement** (10/8, 172.16/12, 192.168/16) pour ne pas couper les postes de l'équipe ; règle SMTP 25 (rien n'écoute) retirée. Journal à 200 Mo : fait.
  - Durcissement appliqué hors agent (SSH + sudo temporaire) : SSH `X11Forwarding no`, `MaxAuthTries 3`, `LoginGraceTime 30` (fichier `/etc/ssh/sshd_config.d/90-vps-autopilot.conf`, mot de passe gardé : serveur partagé) ; Apache `ServerTokens Prod`, `ServerSignature Off` (sauvegarde `security.conf.bak-vps-autopilot`) ; `prism.service` (doublon en boucle de plantage) arrêté et désactivé. Applications vérifiées en 200 par l'adresse publique.
  - **fail2ban écarté** : la passerelle traduit les adresses sources (toutes les connexions SSH arrivent de la même adresse interne), un bannissement couperait tout le monde.
  - Points 3 à 7 et 10 (profil serveur, routage par chemin, modèle `static`, analyse par serveur, plusieurs plateformes par hôte) restent **implémentés dans l'agent** et valables pour tout serveur. Les plateformes `dismas` et `prism` restent déclarées localement pour une reprise si la sortie internet est rétablie (ou avec un registre d'images interne).

---

## ADR-039 - Monétisation : service géré d'abord, logiciel ensuite

- **Date** : 2026-10-13
- **Statut** : Accepted (stratégie spécifiée ; aucune offre facturée à ce jour)
- **Contexte** : l'opérateur veut que la plateforme devienne une activité rentable. L'agent a deux mises en production réelles (ARC en ligne sur son domaine, serveur DTSI analysé et sécurisé), mais reste mono-opérateur, local, et dépend de son auteur. V9 (multi-opérateurs) n'a pas de code.
- **Décision** :
  1. **Étape 1 : vendre un service** (audit, mise en ligne au forfait, maintenance mensuelle). L'agent est l'outil interne qui rend le service rentable ; il n'est pas livré au client.
  2. **Étape 2 : licence auto-hébergée** pour agences, seulement après plusieurs clients du service.
  3. **Étape 3 : plateforme en ligne (V9)** seulement si les étapes 1 et 2 prouvent la demande. ADR-031 inchangé (facturation hors V9 jour 1).
  4. Avant le premier client payant, les tâches `BIZ-01` à `BIZ-07` (P0) sont livrées. On ne vend pas de sauvegarde jamais restaurée.
  5. Tout serveur client suit les mêmes règles que le lab : plan + confirmation (ADR-003), sauvegarde avant écriture, cibles bloquées et lecture seule dans `state/agent-local.yml`.
- **Conséquence** : nouveau doc `business.md` ; backlog `BIZ-*` ; la roadmap technique V0–V9 ne change pas, les `BIZ-*` passent devant V5/V8/V9.
- **Suite 2026-10-14** : grille de prix de départ (hypothèses) dans `business.md`, documents `commercial/` (offre, devis, mandat). `BIZ-01`, `BIZ-03`, `BIZ-04`, `BIZ-07` (modèle) livrés.

---

## ADR-040 - Opérations longues : tâche détachée suivie par courtes connexions

- **Date** : 2026-10-14
- **Statut** : Accepted (implémenté, testé sur le lab)
- **Contexte** : déployer une plateforme ou restaurer des données dure de quelques minutes à 45 min. Une seule session SSH portait tout le travail : une coupure réseau (vue le 2026-10-08 après ~5 min) faisait annoncer un échec alors que le rôle continuait et terminait sur le serveur. Une connexion figée sans RST bloquait aussi la lecture indéfiniment.
- **Décision** :
  1. `RemoteJobRunner` : le script est déposé dans `~/.vps-autopilot/jobs/<id>/` (dossier 700, `umask 077`), lancé par `systemd-run --collect` (repli `setsid nohup`), puis suivi toutes les 10 s par de courtes connexions SSH (`job-poll.sh`) ; sortie et code retour récupérés en fin (`job-fetch.sh`).
  2. Lancement idempotent (un dossier existant n'est pas relancé), 3 essais de lancement, coupures de suivi tolérées jusqu'au délai. Le script est **effacé du disque** dès qu'il a tourné (il peut contenir des secrets) ; dossiers de plus de 14 jours supprimés.
  3. Même contrat d'erreur que `SshConnector.runStdinScript` (`Commande distante exit=N`, `###STDOUT###`, `###STDERR###`) : les lecteurs de marqueurs ne changent pas. Délai dépassé : message « toutes les étapes continuent sur le serveur ».
  4. Branché sur Déployer plateforme (2 700 s), Déployer app (1 800 s), Restaurer, Sauvegarder maintenant, Exercice de restauration. `SshConnector` pose un délai de lecture (`setTimeout`) sur toutes ses connexions.
- **Conséquence** : `BIZ-01` livré. Essai lab : tâche de 6 min 20 récupérée, code 3 transmis pour une tâche en échec. Le script vit brièvement sur le disque du serveur (700, propriétaire SSH) : accepté, plutôt que de le garder dans une session fragile.

---

## ADR-041 - Sauvegardes réelles : dump vérifié, minuteur, exercice de restauration

- **Date** : 2026-10-14
- **Statut** : Accepted (implémenté, testé sur le lab) — remplace les points 2, 4 et 5 d'ADR-034
- **Contexte** : les sauvegardes d'ADR-034 étaient vides (erreur `mysqldump` chiffrée), jouées une seule fois à l'installation, et la « preuve » ne lisait que l'en-tête. On ne vend pas une sauvegarde jamais restaurée (ADR-039).
- **Décision** :
  1. Rôle `backups` : installe `/usr/local/bin/vps-autopilot-backup` (700 root). `mariadb-dump` (repli `mysqldump`) de toutes les bases, `pipefail`, erreur affichée (`BACKUP_DUMP_FAILED`, `DUMP_ERR:`), marque de fin `-- Dump completed` exigée (`BACKUP_DUMP_INCOMPLETE`), mot de passe root par variable d'environnement (pas en ligne de commande), 14 fichiers gardés.
  2. **Minuteur systemd quotidien** (`vps-autopilot-backup.timer`, 02:30 ± 30 min, `Persistent`). La phrase de chiffrement est donc aussi sur le serveur : `/opt/vps-autopilot/backups/.passphrase`, 600 root, à côté des fichiers qu'elle chiffre. Accepté : la copie hors serveur protège contre la perte du serveur, pas contre un attaquant root (déjà maître des bases).
  3. L'analyse ne considère les sauvegardes « présentes » que si le marqueur **et** le script existent : un serveur resté sur l'ancien rôle est reproposé à l'installation.
  4. Côté agent (`BackupArchive`, `BackupService`) : copie par SFTP vers `state/backups/<serveur>/` (copie temporaire 600 dans `/tmp`, effacée), SHA-256 comparé ; **vérification** = déchiffrement et lecture en flux de tout le fichier, marque de fin, tables comptées par base ; résultat dans `<fichier>.json`.
  5. **Exercice de restauration** (CHANGE `restore-drill`) : chaque base applicative est extraite localement, envoyée, importée dans une base jetable `restore_check_<hex>`, ses tables (`BASE TABLE`) comptées et comparées aux `CREATE TABLE` du dump, puis la base jetable supprimée (même en échec). Les bases en service ne sont pas touchées.
  6. **Restaurer une plateforme depuis une sauvegarde** : extraction de `app_<id>` puis `PlatformRestoreService` (sauvegarde de l'existant, retour arrière si l'import échoue). CHANGE `restore-platform`.
  7. CHANGE `run-backup` (Sauvegarder maintenant). « Copier la dernière » sans confirmation, refusée sur un serveur en lecture seule (écrit dans `/tmp`, sort les données d'un tiers).
- **Conséquence** : `BIZ-03` livré pour les bases MariaDB. Non couverts : ~~fichiers déposés par les utilisateurs (volumes, `BIZ-11`)~~ (couverts depuis l'amendement ci-dessous), copie hors poste opérateur (`BIZ-09`), purge des copies locales. Essai lab : dump 4,3 Mo, exercice 25/25 tables, audit du lab C 62 → A 91.
- **Amendement 2026-10-14 — fichiers des volumes (BIZ-11)** (implémenté, testé sur le lab) :
  8. Le script (désormais marqué `# vps-autopilot-backup v2`) archive après le dump les volumes Docker `plat_<id>_uploads` (plateformes) et `*app_data_<slug>` (apps 1:1) : `tar` depuis le dossier des volumes Docker → `openssl` **en flux** (aucune copie en clair sur le disque) → `files-<horodatage>.tar.gz.enc`, **même horodatage que le dump**, 14 gardés. Un volume dont le point de montage n'est pas `<docker>/volumes/<nom>/_data` est ignoré et signalé (`FILES_SKIPPED`). `tar` qui sort en 1 (fichier modifié pendant la lecture) est accepté ; tout autre échec → `BACKUP_FILES_FAILED` (le dump de la nuit reste écrit).
  9. L'analyse considère un script sans la marque `v2` comme absent : les serveurs équipés avant sont reproposés à l'installation (sans droit de lecture du script, pas de contrôle).
  10. Agent : la copie, la vérification (en-têtes tar avec somme de contrôle, fin d'archive, fichiers et octets par volume, chemins hors `<volume>/_data/` refusés) et l'exercice couvrent l'archive. Exercice des fichiers : extraction dans `/var/tmp/autopilot-files-drill-*` (place exigée : taille + 10 % + 100 Mo, sinon exercice des fichiers non fait et signalé), comptage par volume, dossier supprimé.
  11. Restaurer une plateforme depuis une sauvegarde remet aussi `plat_<id>_uploads` : l'archive chiffrée est envoyée, déchiffrée sur le VPS avec sa phrase (`NEED_BACKUP_PASSPHRASE` sinon), le volume est **vidé puis remis à l'identique** (propriétaires et droits numériques gardés) après copie de l'existant ; si l'extraction échoue, l'existant est remis (`UPLOADS_ROLLED_BACK`). Une archive personnelle reste fusionnée comme avant.
  12. Téléchargement SFTP **repris** à l'octet près après une coupure (abandon après 3 essais de suite sans progrès) ; le `.part` est gardé pour reprendre au prochain appel, l'empreinte SHA-256 tranche à la fin. Copies temporaires de plus de 2 h laissées dans `/tmp` effacées à la copie suivante. Motif : liaison opérateur mesurée à ~13 Ko/s le 2026-10-14, cinq copies coupées avant reprise.
  - Non couvert : restauration des fichiers d'une **app** 1:1 depuis la console (sauvegardés, vérifiés et testés seulement) ; volumes d'autres noms.
- **Amendement 2026-10-14 (2) — exercice automatique et purge locale (BIZ-12)** (implémenté, testé sur le lab) :
  13. Le rôle installe aussi `/usr/local/bin/vps-autopilot-backup-drill` (700 root, marqué `# vps-autopilot-backup-drill v1`) et le minuteur `vps-autopilot-backup-drill.timer` (**1er du mois, 04:00 ± 1 h**, `Persistent`, `Nice=10`), et lance un premier exercice à l'installation. L'exercice se fait **sur le VPS, sans l'agent** : dernière sauvegarde du VPS, chaque base applicative découpée du dump (`awk`, en-tête et pied repris, `DEFINER` retirés) et importée dans `restore_check_<hex>`, tables comptées contre les `CREATE TABLE` du dump ; archive des fichiers listée puis extraite dans `/var/tmp/autopilot-auto-drill-*`, fichiers comptés contre la liste de l'archive ; tout est supprimé (même en échec). Place exigée : 10 × taille chiffrée + 200 Mo.
  14. Résultat dans `/opt/vps-autopilot/backups/drill-last.txt` (600 root : `DRILL_AT`, `DRILL_DUMP`, `DRILL_DB=base:attendu:obtenu`, `DRILL_VOL=…`, `DRILL_PROBLEM=…`, `DRILL_OK`) et une ligne par exercice dans `drill-history.log` (24 gardées). L'analyse le lit (`ServerSnapshot.Hygiene.autoDrill`, `drillTimer`) ; l'audit dit **ELEVE** si le dernier exercice a échoué, **FAIBLE** s'il n'y en a pas depuis 40 jours, point fort sinon. Un serveur sans le script d'exercice est reproposé à l'installation (point 9 étendu).
  15. Purge des copies du poste après chaque nouvelle copie : les **10** plus récentes par serveur sont gardées, **plus la plus récente dont l'exercice a réussi** (la dernière preuve de restauration n'est jamais effacée) ; dump, archive des fichiers et fiches `.json` partent ensemble.
  - Motif : l'exercice à la demande dépendait de l'opérateur ; le mandat (article 5) promet un exercice mensuel. Un exercice sur le VPS ne prouve pas la copie hors serveur : celle-ci reste vérifiée à chaque copie (point 4).

---

## ADR-042 - Journal des actions par serveur

- **Date** : 2026-10-14
- **Statut** : Accepted (implémenté, testé sur le lab) — `BIZ-06`
- **Contexte** : le mandat d'intervention (article 8) promet un journal des actions remis au client. Rien ne gardait la trace de qui avait fait quoi, quand, avec quel résultat.
- **Décision** :
  1. Un filtre HTTP (`JournalFilter`) journalise **après exécution** toute requête de la console qui agit : POST / PUT / DELETE sous `/api/v1/` et la génération du rapport d'audit. Les lectures de la console (GET) ne le sont pas. Libellé et type (Lecture / Poste / Modification) donnés par `ActionCatalog` ; une route inconnue est journalisée comme « Modification » avec méthode et chemin.
  2. Une ligne JSON par action dans `state/journal/<serveur>.jsonl` (append-only, non versionné) ; sans serveur désigné : `_poste.jsonl`. Champs : date UTC, compte Windows, serveur, utilisateur SSH, action, type, résultat (OK / ECHEC), code HTTP, durée, détails, message d'erreur.
  3. **Liste blanche** des détails recopiés (`id`, `platformId`, `service`, `name`, `component`, `file`, `repo`, `confirm`, `rotate` ; en réponse `file`, `risk`) : le corps brut n'est **jamais** écrit (SQL, mots de passe, valeurs de secrets, variables d'environnement).
  4. Export `GET /api/v1/journal/export?format=html|csv` : page imprimable pour le client ou CSV (`;`, BOM UTF-8, formules neutralisées). Rien ne s'efface depuis la console.
  5. Un échec d'écriture du journal n'empêche pas l'action (avertissement dans le log de l'agent).
- **Conséquence** : bande **Journal** (mode expert), Aide 7e. Limite : journal local au poste d'exploitation ; une action faite à la main en SSH n'y figure pas.

---

## ADR-043 - Secrets d'exécution posés depuis le cockpit

- **Date** : 2026-10-14
- **Statut** : Accepted (implémenté, testé sur le lab) — `BIZ-05`
- **Contexte** : les variables secrètes d'une plateforme (mot de passe SMTP, jetons) étaient soit dans l'intention locale (`platforms-intent.json`, masquées à l'écran mais stockées), soit posées à la main en SSH dans `svc-<service>.local.env` (ARC, 2026-10-08, avec un incident d'affichage du mot de passe).
- **Décision** :
  1. CHANGE `set-platform-secret` : `POST /api/v1/platforms/secrets` (poser / remplacer) et `/secrets/remove` (retirer) écrivent **une** variable dans `svc-<service>.local.env` sur le VPS, puis recréent le service (`docker compose up -d --no-build <service>`) si demandé.
  2. La valeur ne passe que par le script envoyé sur l'**entrée standard** SSH (base64), jamais en argument de commande ; sur le VPS elle va par un tube dans un fichier 600 `deploy` créé avant (`install -m 600`), puis remplacement atomique. Elle n'est **jamais** enregistrée sur le poste, renvoyée par l'API, affichée, ni journalisée (ADR-042). La sortie du rôle ne contient que des noms.
  3. Écriture `NOM='valeur'` (guillemets simples) : Docker Compose ne fait alors aucune substitution (`$`, `#`, `\` gardés). Refusé : apostrophe droite, retour à la ligne, valeur > 4 096 caractères ; nom `[A-Za-z_][A-Za-z0-9_.]*`.
  4. `GET /api/v1/platforms/secrets` (READ) liste les **noms** posés par service, jamais les valeurs.
  5. `svc-<service>.local.env` est lu en dernier par Compose : il prime sur une variable de même nom de la fiche.
- **Conséquence** : panneau « Secrets d'exécution » sous la console SQL, Aide 7b. Non couvert : apps 1:1 (une app = un conteneur) ; secrets de build (figés dans l'image). Les secrets déjà dans l'intention y restent tant que l'opérateur ne les retire pas de la fiche.

---

## ADR-044 — Alertes e-mail envoyées par le VPS

- **Date** : 2026-10-15
- **Statut** : Accepted (implémenté, testé sur le lab) — `BIZ-15`
- **Contexte** : sauvegardes et exercices automatiques tournaient sans prévenir l’opérateur ni le client en cas d’échec.
- **Décision** :
  1. Le **VPS** envoie le mail lui-même (`curl` SMTP/SMTPS). Réglages dans `/opt/vps-autopilot/notify.env` (600 root, mot de passe en base64). Jamais stockés sur le poste opérateur.
  2. Rôle `notify` (set / test / status / remove) ; CHANGE `configure-notify` / `test-notify` ; 1–5 destinataires.
  3. Déclencheurs systemd : `OnFailure=` (sauvegarde, exercice) ; résumé mensuel après exercice réussi.
  4. Analyse / audit : `NOTIFY`, dernière alerte ; constats `alerts-missing` / `alerts-failing`.
- **Limite** : serveur éteint = aucun message (voir BIZ-16). Pas de SMS / WhatsApp.
- **Conséquence** : panneau console « Alertes e-mail », Aide 7g.

---

## ADR-045 — Version livrable et console limitée au poste

- **Date** : 2026-10-15
- **Statut** : Accepted (implémenté) — `REL-01` ; versions 1.0.0 / 1.0.1
- **Contexte** : la console sans authentification écoutait sur toutes les interfaces ; pas de paquet d’installation hors sources.
- **Décision** :
  1. `server.address` = `127.0.0.1` par défaut (`SERVER_ADDRESS` pour surcharger). Profil `cargo` : `0.0.0.0`.
  2. `GET /api/v1/health` expose `version` (build-info Maven).
  3. Paquet `scripts/release.ps1` → `dist/vps-autopilot-<v>.zip` + `.sha256` ; notice `LISEZMOI.txt`.
- **Conséquence** : doc `deploiement.md`. Le profil `platform` (ADR-046) pourra écouter publiquement **uniquement** derrière auth.

---

## ADR-046 — Plateforme multi-comptes : RBAC, cockpit en ligne, exécuteur

- **Date** : 2026-10-17
- **Statut** : Accepted (spécifié ; implémentation en cours) — remplace le périmètre « portail emails seuls »
- **Contexte** : monétiser et ouvrir la plateforme à tout compte autorisé pour gérer **ses** serveurs et paramètres. Un compte super-admin (porteur) administre comptes, rôles et rattachements. Les clés SSH ne doivent jamais passer par le navigateur.
- **Décision** :
  1. **Profil Spring `platform`** : cockpit + API derrière session ; clés SSH sur cette instance (exécuteur). Profil défaut = mono-poste local sans auth (1.x). Profil `cargo` = preuve anonyme inchangée.
  2. **Comptes** + **sessions** (lien magique e-mail, cookie HttpOnly, durée limitée). Bootstrap : premier `SUPER_ADMIN` via `PLATFORM_BOOTSTRAP_EMAIL` si aucun compte.
  3. **RBAC** : catalogue de permissions stables ; rôles (`SUPER_ADMIN`, `OPERATOR`, `VIEWER` au seed) dont l’ensemble de permissions est **éditable** par qui a `role.manage`. `SUPER_ADMIN` conserve un accès global (tous serveurs) tant que le rôle porte la permission spéciale `platform.admin` ou équivalent seed.
  4. **Assignation** : `server-grants` lie `(accountId → host, port, user)`. Un opérateur sans grant sur un serveur → 403.
  5. Chaque requête sensible : session → permission du rôle → grant serveur (sauf admin global) → puis `ActionGuard` (whitelist + confirm, ADR-003 / ADR-016).
  6. État sous `state/platform/` (hors git) : accounts, roles, grants, sessions, tokens. Journal : conserver l’identité du compte connecté quand la plateforme est active (amende ADR-042 à la livraison).
- **Permissions seed** : `account.manage`, `role.manage`, `server.assign`, `server.read`, `server.analyze`, `server.change.install`, `server.change.deploy`, `server.change.backup`, `server.change.notify`, `server.change.secret`, `server.journal`, `audit.read`, `platform.admin`.
- **Non-objectifs jour 1** : facturation, multi-org imbriquée, marketplace, IA (V5), alerte serveur éteint (BIZ-16).
- **Conséquence** : backlog `PLAT-04`… ; version cible **2.0.0** quand lab vert. Amendements ADR-030 / ADR-031 ci-dessus.
## ADR-047 — SaaS : monolithe modulaire hexagonal (profil `saas`)

- **Date** : 2026-11-07
- **Statut** : Accepted — implémenté (validé par la consigne du 2026-11-07 « fais toutes les implémentations » après l'audit `saas/audit-trajectoire-saas.md`)
- **Décision** : un seul déployable ; modules `identity`, `organization`, `server`, `credential`, `operation`, `audit`, `notification`, `apitoken`, `managed`, `billing`, `ai`, `admin`, `security`, `shared` sous `com.nebdev.devopsagent.saas`, chargés seulement en profil `saas`. Le moteur historique (discovery, stack, ssh) est appelé par le port `EnginePort` (adaptateur `saas.engine.LocalEngineAdapter`). Règles ArchUnit : moteur sans dépendance vers `saas`, aucun cycle entre modules, `identity` ne dépend d'aucun module métier. Pas de microservices.
- **Conséquence** : profils historiques (défaut, `cargo`, `platform`) inchangés ; `saas` refuse d'être combiné avec eux.

## ADR-048 — SaaS : PostgreSQL 16 + Flyway

- **Date** : 2026-11-07 — **Statut** : Accepted — implémenté
- **Décision** : migrations `classpath:db/saas` jouées par le rôle propriétaire ; l'application se connecte avec `autopilot_app` (NOSUPERUSER, NOBYPASSRLS, non propriétaire). JPA en `validate`. Les stores JSON restent l'adaptateur des éditions locales. Démarrage refusé si le rôle applicatif contourne la RLS.

## ADR-049 — Multi-tenancy : `organization_id` + RLS

- **Date** : 2026-11-07 — **Statut** : Accepted — implémenté
- **Décision** : toute table métier porte `organization_id` ; politiques RLS sur `app.org_id` posé par transaction (`RlsContext`) ; contournement plateforme explicite (`app.bypass_rls`) réservé aux traitements système. Contrôle applicatif en plus (`OrgAccess`) : une autre organisation reçoit 404 et la tentative est auditée. Tests d'isolation sur PostgreSQL réel (SQLSTATE 42501).

## ADR-050 — Opérations : plan haché, approbation liée au hash

- **Date** : 2026-11-07 — **Statut** : Accepted — implémenté (amende ADR-016 / ADR-018 pour le SaaS)
- **Décision** : READ / CHANGE / DESTRUCTIVE ; une opération CHANGE porte un plan déterministe dont le hash (JSON canonique SHA-256) couvre le plan, la cible et l'empreinte d'hôte. L'approbation porte sur ce hash, expire (TTL), règle des quatre yeux en option. Le plan est recalculé sur l'état frais juste avant d'agir : différent ⇒ rien n'est exécuté (`plan_changed`). Une exécution active par serveur (index partiel), file `FOR UPDATE SKIP LOCKED`, clé d'idempotence, aucune relance automatique d'un CHANGE (`runner_lost`).

## ADR-051 — Coffre des identifiants

- **Date** : 2026-11-07 — **Statut** : Accepted — implémenté (complété par ADR-055)
- **Décision** : une clé SSH par serveur (RSA-3072 générée ou importée), chiffrement par enveloppe AES-256-GCM avec AAD (DEK par organisation, KEK `AUTOPILOT_MASTER_KEY` hors base). Empreinte d'hôte confirmée explicitement avant la première connexion, vérifiée exactement à chaque connexion. Rotation (PENDING → ACTIVE par opération approuvée) et révocation. Jamais vers l'IA, jamais renvoyée par l'API.

## ADR-052 — Personnel INOVNE et accès support

- **Date** : 2026-11-07 — **Statut** : Accepted — implémenté
- **Décision** : personnel (`PLATFORM_ADMIN`, `PLATFORM_OPERATOR`) distinct des membres clients ; aucun accès implicite aux données client : seulement par `support_access_grant` accordé par le client, borné dans le temps, révocable, audité. **Amendement 2026-11-08** : le rôle de personnel n'est effectif qu'avec la MFA active (`autopilot.auth.staff-mfa-required`, défaut vrai).

## ADR-053 — Audit append-only chaîné

- **Date** : 2026-11-07 — **Statut** : Accepted — implémenté
- **Décision** : `audit_event` en ajout seul (trigger + REVOKE UPDATE/DELETE/TRUNCATE au rôle applicatif), chaîne de hachage par organisation, vérification par API. Sorties de commandes tronquées et hachées (SHA-256), purge des sorties après rétention.

## ADR-054 — IA : conseil seulement

- **Date** : 2026-11-07 — **Statut** : Accepted — implémenté
- **Décision** : port `AiProvider` (Anthropic via SDK officiel, sortie structurée ; conseiller à règles déterministe en repli). Contexte = faits techniques sans adresse ni secret (`EnginePort.summarize`). Sortie validée par le code ; une recommandation ne devient qu'un brouillon (DRAFT) d'un `OperationType` en liste blanche, qu'un humain doit proposer et faire approuver.

## ADR-055 — Runner d'exécution séparé, coffre scellé

- **Date** : 2026-11-08 — **Statut** : Accepted — implémenté
- **Contexte** : risque n°1 de l'audit : l'instance publique détenait la KEK et pouvait lire toutes les clés SSH.
- **Décision** :
  1. Mode `AUTOPILOT_RUNNER_MODE=remote` : le control plane ne détient que la **clé publique** RSA (≥ 3072 bits) du runner (`AUTOPILOT_RUNNER_PUBLIC_KEY`). Clés SSH et phrase de sauvegarde sont **scellées** (RSA-OAEP-SHA256 + AES-256-GCM, AAD organisation + usage, format 2) ; le control plane ne sait plus les rouvrir.
  2. Au démarrage en remote, les valeurs encore chiffrées par la KEK (format 1) sont rescellées (migration sans coupure) ; ensuite la KEK ne protège plus que les secrets MFA.
  3. Runner = même jar, profil `runner` : aucune base, aucun port d'écoute ; il réserve (`POST /api/v2/internal/runner/claim`), ouvre les secrets avec sa clé privée, laisse le moteur recalculer le plan et n'agit que si le hash est identique, envoie battements et compte rendu. Une exécution ne peut être rapportée que par le runner qui l'a réservée ; un seul compte rendu fait foi.
  4. Authentification par jeton partagé (≥ 32 caractères) ; https obligatoire hors boucle locale (dérogation explicite pour un réseau Docker privé).
- **Limites** : les clés SSH sont encore générées par le control plane (qui les voit à la création) ; génération côté runner = étape suivante. Lecture d'empreinte d'hôte faite par le control plane (lecture seule, sans secret).

## ADR-056 — Cibles SSH : anti-SSRF

- **Date** : 2026-11-08 — **Statut** : Accepted — implémenté
- **Contexte** : une organisation pouvait enregistrer un « serveur » sur une adresse interne (boucle locale, réseau privé, base de données, métadonnées cloud) et faire ouvrir des connexions depuis la plateforme.
- **Décision** : `TargetPolicy` refuse boucle locale, RFC 1918, lien local, CGNAT, ULA IPv6, IPv4 encapsulées ; jamais (même en lab) : adresse non spécifiée, multicast, 169.254.169.254. Contrôle à l'enregistrement **et** juste avant chaque connexion (control plane et runner, chacun avec sa politique), connexion vers l'IP résolue (pas de second DNS). Liste d'interdiction configurable (`AUTOPILOT_DENIED_TARGETS`). `AUTOPILOT_ALLOW_PRIVATE_TARGETS=true` réservé au lab et à l'auto-hébergé.

## ADR-057 — Migration des données `platform` → SaaS

- **Date** : 2026-11-08 — **Statut** : Accepted — implémenté (jamais exécuté sur les données de production)
- **Décision** : `PlatformImporter` (profil `saas`), déclenché par `AUTOPILOT_MIGRATION_PLATFORM_STATE_DIR`. **Essai à blanc par défaut** : tout est écrit puis annulé (contraintes réellement vérifiées), rapport JSON sans secret ; `AUTOPILOT_MIGRATION_APPLY=true` pour valider. Idempotent.
  1. Une organisation (`AUTOPILOT_MIGRATION_ORGANIZATION_NAME`, défaut INOVNE) ; comptes repris avec leur hachage BCrypt (mot de passe inchangé) ; administrateurs ⇒ propriétaires, droits `server.change.*` ⇒ responsable technique, sinon lecteur.
  2. **Fail-closed** : un compte non administrateur limité à certains serveurs n'est pas repris (le rôle SaaS couvre toute l'organisation) ; il est listé pour une invitation manuelle.
  3. Serveurs depuis les rattachements ; empreinte d'hôte à reconfirmer (ancien format MD5) ; clé SSH de l'instance reprise si `AUTOPILOT_MIGRATION_SSH_PRIVATE_KEY_FILE` est fourni.
  4. Non repris : sessions, liens magiques, journaux `state/journal` (à archiver). MFA à activer par le personnel.
- **Plan de bascule de `autopilot.inovne.com`** (à faire, avec autorisation explicite) : sauvegarde de `state/` → SaaS déployé à côté → essai à blanc → lecture du rapport → application → reconfirmation des empreintes → bascule DNS/proxy → ancienne instance gardée arrêtée, pas supprimée.
