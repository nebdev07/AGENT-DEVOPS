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
- **Statut** : Accepted (spécifié ; **pas encore implémenté**)
- **Contexte** : L’attente produit est qu’un utilisateur se connecte, ait **sa** session, et ne voie / ne pilote que **ses** VPS et paramètres. Aujourd’hui l’outil est mono-opérateur local (poste + `state/` local). Le lien public cargo n’est pas ce cockpit.
- **Décision** :
  1. **Phase actuelle (V0–V3)** : un opérateur, console sur le poste, CHANGE après confirmation. Cargo public = preuve uniquement (ADR-030).
  2. **Phase plateforme (V9)** : comptes + sessions ; chaque opérateur fournit ses paramètres (hôte SSH, user, apps) **valables pour lui seul** ; isolation des secrets ; cockpit joignable en ligne **uniquement après authentification**.
  3. Pas de CHANGE anonymes sur Internet. Auth avant plan/confirmation.
  4. L’executor peut vivre sur un serveur dédié ou le VPS lab ; le modèle de risque (READ / CHANGE / DESTRUCTIVE) ne change pas.
- **Non-objectifs V9 jour 1** : marketplace, multi-org complexe, facturation.
- **Conséquence** : roadmap **V9** ; backlog `PLAT-*`. Aucun code multi-tenant tant que V3 (grants) / V4 (backups) utiles au mono-opérateur ne bloquent pas. ADR-030 reste valide jusqu’à la première livraison auth.

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

---

## ADR-036 — Plateforme cargo (plusieurs sites + API)

- **Date** : 2026-09-28
- **Statut** : Accepted (spécifié ; **pas encore de code**)
- **Contexte** : V3 déploie **un** dépôt → **un** conteneur → **un** `Host()`. Ça suffit pour Bourse. Ça ne décrit pas une infra du type ARC : site public, backoffice, API Spring, Redis, volume fichiers, un même produit. Coller trois cargos indépendants oblige l’opérateur à recâbler à la main les URL, Redis et les chemins. V7 (« multi-projets ») était trop vague : plusieurs **produits** 1:1 sur un VPS existent déjà (liste d’apps) ; ce qui manque est un **produit multi-services**.
- **Décision** :
  1. **Deux formes de cargo, un seul agent** (ADR-023). Pas de nouvel agent sous `AGENTS/`.
     | Forme | Contrat | Exemple |
     |---|---|---|
     | **App** (ADR-029) | 1 Git, 1 Dockerfile racine, 1 hôte, 1 volume `/app/data` | Bourse, preuve `vps-autopilot` |
     | **Plateforme** (cette ADR) | N services, un CHANGE, réseaux/DB/Redis/volumes partagés | ARC *en lab*, futurs SaaS |
  2. **Objet `platform`** (intention locale `state/platforms-intent.json`) : `id`, domaine de base, mode de routage, liste `services[]`. Chaque service : `id`, Git (URL + ref), port interne, `context` Docker optionnel (sous-dossier d’un monorepo), `needs` (`mariadb` \| `redis` \| `volume:<nom>`), vars de build (`NEXT_PUBLIC_*`).
  3. **CHANGE whitelisté `deploy-platform`**. Plan + popup + confirmation, comme `deploy-app`. L’executor **génère** un compose sous `/opt/vps-autopilot/platforms/<id>/` : N services, réseau `proxy` + `data`, **aucun port publié**. Pas de shell inventé par l’IA. Pas d’Apache / Nginx / PM2 / Tomcat **hôte** (ADR-021 inchangé).
  4. **Routage jour 1 = sous-domaines** (`Host(www.…)` / `Host(api.…)` / `Host(admin.…)`). C’est ce que Traefik sait déjà. **Chemins** (`/` + `/arc-backoffice` + `/arc-api` sur le même host) = phase 2 (`PathPrefix`, strip prefix) — pas un bloqueur du premier lab.
  5. **Redis** : si un service `needs: redis` et le conteneur `redis` est SKIP, injecter `REDIS_HOST=redis` + mot de passe (fichier plateforme, mode 600) via `env_file`. Aujourd’hui Redis s’installe mais n’est **pas** branché aux apps : c’est un trou à fermer ici, pas un rôle séparé.
  6. **Volumes** : `/app/data` reste le défaut d’une app 1:1. Une plateforme peut déclarer des volumes nommés (`uploads` → chemin dans le conteneur). Traefik ne sert pas de PHP sur un webroot.
  7. **Git** : HTTPS public d’abord (comme V3). **Clone privé** (clé deploy lecture seule) = chantier V7 listé, requis pour des repos association, **pas** pour ouvrir la prod ARC.
  8. **Cible d’exécution** : VPS lab Autopilot seulement. Prod ARC et staging DTSI restent **interdites** (`ActionGuard`). Le scénario ARC sert de **spécimen** (forme d’infra), pas de premier bouton Déployer.
  9. **Packaging** : chaque service a un Dockerfile (racine du repo **ou** `context` monorepo). Le rattrapage ADR-032 reste pour l’app 1:1. Pour une plateforme, les templates (Next + build-args, Spring **Java 11** WAR/JAR) sont des Dockerfiles **dans le cargo** ou des templates versionnés **explicitement choisis** dans le plan — pas un pom racine GitHub magique.
  10. **Unité de déploiement** : un CHANGE met à jour **toute** la plateforme (ordre : API puis fronts, healthcheck API avant de basculer les fronts). Redeploy d’un seul service = chantier suivant, pas jour 1.
- **Non-objectifs jour 1** : Kubernetes, Apache hôte, pointer l’agent sur `arc-nwanyo.org` prod, migrer Tomcat existant sans images, multi-opérateurs (V9).
- **Conséquence** : roadmap **V7** précisée ; backlog `V7-01`… ; module architecture « Plateforme cargo ». L’app 1:1 ne disparaît pas. Premier lab plateforme = copie / maquette sur VPS-2, jamais l’IP ARC.

---

