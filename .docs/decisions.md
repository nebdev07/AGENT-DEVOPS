# Décisions d’architecture (ADR) — VPS Autopilot

> Mémoire permanente. **Ne jamais supprimer** une décision : la marquer `Superseded` et en ajouter une nouvelle.  
> Dernière mise à jour : 2026-09-08 (ADR-030 : premier cargo = cet agent)

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
- **Conséquence** : `RoleScript` + `apply.sh`. MariaDB / Redis / backups restent planifiés. Pas d’install Traefik à la main. SKIP sécurité = **ce** VPS, pas « le produit a fini V1 ».

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
- **Décision** : CHANGE whitelisté `deploy-app`. L’opérateur déclare slug, URL Git **HTTPS publique**, ref, nom d’hôte, port interne. READY si **ce** VPS a : baseline sécurité SKIP, Docker, Traefik, compte `deploy`. L’agent clone sous `/opt/vps-autopilot/apps/<slug>/src`, **génère** le compose (réseau `proxy`, labels Traefik, **aucun port publié**). Le dépôt cargo n’a besoin que d’un `Dockerfile` à la racine — pas de logique de provision (ADR-023). Pas de grants MariaDB dans ce CHANGE (plus tard). Dépôts privés / SSH Git : pas encore.
- **Conséquence** : `roles/app/apply.sh` + intention locale `state/apps-intent.json`. DNS du nom d’hôte vers le VPS = charge de l’opérateur. HTTP (entrypoint `web`) d’abord ; certificat Let’s Encrypt sur `websecure` si Traefik HTTPS est déjà là.

---

## ADR-030 — Premier cargo : cet agent, sans CHANGE public

- **Date** : 2026-09-08
- **Statut** : Accepted
- **Décision** : Le premier déploiement Git→image→Traefik est **ce dépôt** (`devops-agent`). Le cerveau (cockpit, confirmation, CHANGE) reste sur le poste opérateur (`127.0.0.1:8088`). L’instance sur le VPS démarre avec le profil Spring `cargo` (Dockerfile) : page de preuve + `/api/v1/health` seulement. Aucun discover / install / deploy depuis Internet. Pas d’auth à inventer pour ouvrir l’API admin.
- **Conséquence** : `Dockerfile` à la racine (port 8080). Dépôt **HTTPS public** obligatoire (ADR-029). Ce workspace n’a pas encore d’`origin` : pousser avant le bouton Déployer. Identifiant prévu : `vps-autopilot`.

