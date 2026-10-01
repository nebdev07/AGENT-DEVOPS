# État actuel — VPS Autopilot

> Distinguer spécifié vs implémenté.  
> Dernière mise à jour : 2026-10-01

## Spécifié

Produit Agent DevOps (V0→V9), Ansible dès les CHANGE, Traefik/MariaDB/backups, IA en V5, **plateforme cargo multi-services en V7 (ADR-036)**, plateforme multi-opérateurs en V9 (ADR-031). Catalogue : `documentation.md`.  
V4 backups : dump chiffré + copie hors-site + preuve de déchiffrement minimale. **Option retenue** : dumps sur le **poste opérateur** (`state/backups/`). Rôle exécutable (ADR-034). Restore SQL complet : pas encore.  
V7 : un produit = N conteneurs. **V7-01 + V7-02 + V7-04 + V7-05 livrés**. ~~Templates Java 11 : pas encore.~~ (obsolète 2026-10-01 : **V7-06 livré**, voir Implémenté). Lab seulement (pas l’IP prod ARC).  
2026-10-01 : **V7-06 implémenté** (modèles Dockerfile par service, variables build/exécution, `keepPath`, Redis privé, clés Git par dépôt, préréglage ARC, **restauration des données** `restore-platform`). Testé : build des modèles ARC sur le lab, rôle de restauration sur le lab (import MySQL 8 → MariaDB, sauvegarde avant remplacement), API restore de bout en bout (SFTP). **Pas encore fait** : déploiement complet ARC depuis la console sur le nouveau VPS (en attente de l’IP).  
V9 : auth + sessions ; chaque utilisateur ses paramètres ; cockpit en ligne seulement après login. **Pas encore de code.** Ne pas confondre V7 (plusieurs *services*) et V9 (plusieurs *opérateurs*).

## Implémenté (V0)

- Projet Spring Boot 3.3 / Java 21 : discovery SSH **read-only**.
- Garde de risque : READ auto ; un CHANGE whitelisté (`install-agent-pubkey`) si confirmé.
- Parseur de rapport brut → snapshot JSON.
- CLI `discover` + API `POST /api/v1/discover` + `GET /api/v1/runtime` + `GET /api/v1/state`.
- Console : accueil (`#home`), aide (`#help` = guide opérateur, ADR-027), cockpit (`#cockpit`). Overlay loader pendant analyse, clé SSH et installation. À la fin d’une install (clé ou composant), fenêtre succès/échec à fermer. Copie publique : pas de noms de projets personnels (ADR-022). L’Aide est mise à jour à chaque changement UI/fonction.
- Pile « Que doit installer l’agent ? » uniquement après analyse SSH réelle.
- Bouton Installer si le composant (ou un détail encore coché) manque sur ce VPS. Sécurité = **baseline complet** (ADR-019) : UFW actif **et** fail2ban **et** mot de passe SSH coupé **et** root SSH fermé. fail2ban seul → bouton Installer encore visible.
- Couleurs d’état : vert doux présent/sain, gris absent, ambre inconnu, rouge doux à surveiller. Cockpit en quatre bandes (état / logiciels / pile+install / rapport), filet gauche et teinte calme.
- Pile voulue (ADR-017) : section **repliable** (fermée par défaut), **sous** la carte Installations. Détails cochables (ADR-020). Déjà installé = grisé. Plan d’install après analyse (ADR-018). Docker SKIP seulement si moteur **et** plus aucun détail coché non détecté. Nouvelle section `defaultEnabled` fusionnée une fois (`catalogSeen`).
- CHANGE exécutable : `security`, `docker`, `traefik`, `mariadb`, `deploy`, **`redis`**, **`backups`**, **`deploy-app`**, **`deploy-platform`**. Premier cargo = ce dépôt (ADR-030). Nginx hôte : **pas proposé**.
- Console : bandeau Applications **et Plateformes**. Git HTTPS **ou** `git@github.com` + clé lecture seule (V7-05). Apps : bouton Déployer. Plateformes : Enregistrer + **Déployer**. Routage sous-domaines **ou** chemins (`PathPrefix`).
- **V7-06 (2026-10-01)** : par service de plateforme, `template` (`next`, `node`, `spring-boot-11/17/21` → `Dockerfile.autopilot` + entrypoint Spring `DB_*`→`SPRING_DATASOURCE_*`), `buildArgs` et `env` (lignes `CLE=valeur`, ~~`{{HOST}}` remplacé à l’enregistrement~~ — obsolète 2026-10-01 : `{{HOST}}` gardé dans la fiche, remplacé **au déploiement** par `PlatformSpec.resolved()`), `keepPath` (pas de StripPrefix), besoin `redis-private` (sidecar `platform-cache`, réseau `internal`). Secrets d’env masqués `********` dans l’API, valeur conservée à l’enregistrement si le masque revient. Volume fichiers nommé fixe `plat_<id>_uploads`.
- Clés Git **par dépôt** : `state/git-deploy-keys/<keyId>` ; `GET/POST /api/v1/git-deploy-key/repos` (liste des dépôts `git@` des apps et plateformes, lien Deploy keys GitHub/GitLab). Clé unique `state/git-deploy` = repli.
- **Restauration** `POST /api/v1/platforms/restore` (multipart, CHANGE `restore-platform` confirmé) : SFTP vers `/tmp/autopilot-restore-<id>-<hex>`, rôle `roles/platform-restore/apply.sh` : arrêt des services, sauvegarde de la base / des fichiers existants dans `/opt/vps-autopilot/platforms/<id>/backups`, base `app_<id>` recréée (utf8mb4_unicode_ci), import avec réécriture collations `0900`, retrait `DEFINER` / `USE` / `CREATE DATABASE`, archive `.tar.gz`/`.zip` extraite dans le volume (sous-dossier optionnel), redémarrage. Utilisable avant le premier Déployer.
- **Console SQL (2026-10-01)** : `POST /api/v1/platforms/sql` (+ `/sql/analyze`). Lecture directe ; écriture = CHANGE `sql-write-platform` confirmé avec sauvegarde de la base avant (`/opt/vps-autopilot/platforms/<id>/backups/app_<id>-before-sql-<ts>.sql.gz`, 20 gardées). Exécutée avec l’utilisateur de la plateforme (droits limités à `app_<id>`), `--binary-mode` (aucune commande client). DROP/TRUNCATE et tout ce qui touche serveur, droits ou fichiers refusés par `SqlConsolePolicy`. Testé sur le VPS ARC 213.156.134.92.
- **Parcours guidé (2026-10-01)**, mode par défaut du cockpit : 6 étapes (Analyser, Préparer le serveur, Plateforme, Accès Git, Données, En ligne), une carte « Prochaine étape » à la fois, étapes faites cliquables. **Préparer le serveur** = un plan confirmé puis installs `security/docker/traefik/mariadb/deploy` enchaînées tant que le plan propose READY. Vérification Deploy key `POST /api/v1/git-deploy-key/repos/verify` (SSH `git-upload-pack`, lecture seule). Statut en ligne `GET /api/v1/platforms/status?id=` (HTTPS certificat valide, parallèle). **Mode expert** (localStorage `vpsap.expert`) réaffiche tous les panneaux. Progression « Accès Git » / « Données » mémorisée dans le navigateur (`vpsap.git.*`, `vpsap.data.*`). **Pas encore testé de bout en bout** sur un VPS vierge (ARC sur le nouveau VPS = prochaine étape).
- Bootstrap clé : mot de passe OVH ponctuel → `~/.ssh/authorized_keys` (ADR-016).
- Clé dédiée `~\.ssh\vps-autopilot` (hors git). `.env` local sans hôte (saisi dans l’UI).
- Console : aucune fixture affichée. Analyse seulement après IP + user SSH. Fixture CLI/tests uniquement.
- Lab : sécurité / Docker moteur / Traefik / **MariaDB SKIP** sur cet hôte. Compose ou groupe docker non détectés → Docker peut rester READY. Compte deploy : bouton **Installer** en haut de Installations après analyse. Une autre IP neuve reprend la pile depuis le début.

## Non implémenté

Ansible, restore SQL complet des backups V4 (la restauration d’un dump fourni par l’opérateur existe depuis V7-06), IA (V5), ~~templates Spring 11 (V7-06)~~ (livré 2026-10-01), assistant « Préparer le serveur » pour débutant, **plateforme multi-opérateurs V9** (ADR-031). Cargo public ≠ cockpit. Redis n’est **pas** injecté dans les apps 1:1 (seulement les plateformes qui le cochent).

## Runtime

- JDK : `C:\env\java\jdk-21` (le `java` PATH est encore 11).
- Config SSH : variables `DEVOPS_SSH_*` (voir `.env.example`).
- Chemin : `d:\PROJECTS\ME\AGENTS\devops-agent` (pas dans BRVM / SmartFlow).
- VPS-2 : acheté ; IP à saisir dans la console (pas encore dans `.env`).
