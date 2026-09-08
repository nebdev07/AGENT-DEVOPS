# État actuel — VPS Autopilot

> Distinguer spécifié vs implémenté.  
> Dernière mise à jour : 2026-09-08

## Spécifié

Produit Agent DevOps (V0→V8), Ansible dès les CHANGE, Traefik/MariaDB/backups, IA en V5, VPS-2 comme premier lab. Catalogue : `documentation.md`.  
V4 backups : dump chiffré + copie hors-site + restore par l’agent (pas à la main). **Option retenue** : stocker les dumps sur **le poste opérateur** (la machine où tourne la console). Autres cibles plus tard. Pas encore de rôle ni de dossier.

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
- CHANGE exécutable pour **tout hôte** analysé : `security`, `docker`, `traefik`, `mariadb`, `deploy`, **`deploy-app`**. Premier cargo prévu : **ce dépôt** (ADR-030) — `Dockerfile` racine, profil `cargo`, port 8080. Redis/backups/grants SQL : planifiés. Nginx hôte : **pas proposé** (ADR-021).
- Console : bandeau Applications tant que le compte deploy est absent ; bouton Déployer seulement après enregistrement d’une app. Installations : READY en haut.
- Bootstrap clé : mot de passe OVH ponctuel → `~/.ssh/authorized_keys` (ADR-016).
- Clé dédiée `~\.ssh\vps-autopilot` (hors git). `.env` local sans hôte (saisi dans l’UI).
- Console : aucune fixture affichée. Analyse seulement après IP + user SSH. Fixture CLI/tests uniquement.
- Lab : sécurité / Docker moteur / Traefik / **MariaDB SKIP** sur cet hôte. Compose ou groupe docker non détectés → Docker peut rester READY. Compte deploy : bouton **Installer** en haut de Installations après analyse. Une autre IP neuve reprend la pile depuis le début.

## Non implémenté

V2 : Traefik + MariaDB livrés. V3 : compte `deploy` **et** Git→image→Traefik (`deploy-app`) livrés ; grants SQL pas encore. Premier cargo (cet agent) : Dockerfile + mode cargo livrés ; **pas encore déployé** (Git HTTPS public + bouton Déployer). Redis, Ansible, backups, IA : pas encore.

## Runtime

- JDK : `C:\env\java\jdk-21` (le `java` PATH est encore 11).
- Config SSH : variables `DEVOPS_SSH_*` (voir `.env.example`).
- Chemin : `d:\PROJECTS\ME\AGENTS\devops-agent` (pas dans BRVM / SmartFlow).
- VPS-2 : acheté ; IP à saisir dans la console (pas encore dans `.env`).
