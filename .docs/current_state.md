# État actuel — VPS Autopilot

> Distinguer spécifié vs implémenté.  
> Dernière mise à jour : 2026-09-29

## Spécifié

Produit Agent DevOps (V0→V9), Ansible dès les CHANGE, Traefik/MariaDB/backups, IA en V5, **plateforme cargo multi-services en V7 (ADR-036)**, plateforme multi-opérateurs en V9 (ADR-031). Catalogue : `documentation.md`.  
V4 backups : dump chiffré + copie hors-site + preuve de déchiffrement minimale. **Option retenue** : dumps sur le **poste opérateur** (`state/backups/`). Rôle exécutable (ADR-034). Restore SQL complet : pas encore.  
V7 : un produit = N conteneurs. **V7-01 + V7-02 livrés** (déclaration + `deploy-platform`). Routage chemins / Git privé / templates Java 11 : pas encore. Lab seulement (pas l’IP prod ARC).  
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
- Console : bandeau Applications **et Plateformes**. Apps : bouton Déployer. Plateformes : Enregistrer + **Déployer** (CHANGE `deploy-platform`, popup de plan). Compose N services, sous-domaines, Redis/`db.env`/volume uploads si cochés.
- Bootstrap clé : mot de passe OVH ponctuel → `~/.ssh/authorized_keys` (ADR-016).
- Clé dédiée `~\.ssh\vps-autopilot` (hors git). `.env` local sans hôte (saisi dans l’UI).
- Console : aucune fixture affichée. Analyse seulement après IP + user SSH. Fixture CLI/tests uniquement.
- Lab : sécurité / Docker moteur / Traefik / **MariaDB SKIP** sur cet hôte. Compose ou groupe docker non détectés → Docker peut rester READY. Compte deploy : bouton **Installer** en haut de Installations après analyse. Une autre IP neuve reprend la pile depuis le début.

## Non implémenté

Ansible, restore SQL complet (V4 suite), IA (V5), routage PathPrefix (V7-04), Git privé (V7-05), templates Spring 11 (V7-06), **plateforme multi-opérateurs V9** (ADR-031). Cargo public ≠ cockpit. Redis n’est **pas** injecté dans les apps 1:1 (seulement les plateformes qui le cochent).

## Runtime

- JDK : `C:\env\java\jdk-21` (le `java` PATH est encore 11).
- Config SSH : variables `DEVOPS_SSH_*` (voir `.env.example`).
- Chemin : `d:\PROJECTS\ME\AGENTS\devops-agent` (pas dans BRVM / SmartFlow).
- VPS-2 : acheté ; IP à saisir dans la console (pas encore dans `.env`).
