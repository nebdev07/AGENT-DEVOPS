# Backlog — VPS Autopilot

> Enrichir ; marquer `Done` sans effacer.  
> Dernière mise à jour : 2026-10-13 (BIZ-*, ADR-039)

| ID | Priorité | Item | Statut |
|---|---|---|---|
| V0-01 | P0 | Discovery SSH read-only + parseur + tests | Done |
| V0-02 | P0 | Console web cockpit | Done |
| V0-03 | P0 | Docs reprise (catalogue, conventions, API, règles) | Done |
| V0-04 | P0 | Bootstrap clé agent (CHANGE confirmé, mot de passe OVH) | Done |
| V0-05 | P1 | Mini-tuto SSH dans la console (générer / ajouter) | Done |
| V0-06 | P1 | Accueil produit + guide dédié + couleurs d’état | Done |
| V0-07 | P1 | Pile voulue décochable + alertes politique | Done |
| LAB-01 | P1 | Docker Desktop / WSL puis `scripts/lab-up.ps1` + discovery SSH loopback | Open |
| VPS-01 | P1 | Acheter VPS-2, `.env`, discovery réelle | Done — IP `57.128.186.38` ; discovery SSH OK ; sécurité SKIP ; Docker présent |
| V1-01 | P1 | Ansible + module Security (CHANGE + validation) | Open — rôles bash idempotents livrés ; Ansible reporté (executor bash accepté tant que playbooks non requis) |
| V1-02 | P1 | Module Docker + réseaux | Open — moteur Docker présent ; réseaux / compose plus tard |
| V2-01 | P1 | Traefik + HTTPS + MariaDB isolée | Done — Traefik + MariaDB + grants V3 |
| V2-02 | P1 | Redis interne (conteneur, requirepass) | Done — rôle `redis` + discovery conteneur ; test VPS PONG OK |
| V3-01 | P1 | Compte `deploy` + Git → image → Traefik | Done — `deploy` + `deploy-app` + cargo |
| V3-02 | P1 | Grants MariaDB (base + user) au `deploy-app` | Done — `db.env` + SQL si conteneur `mariadb` |
| V4-01 | P1 | Backups chiffrés + restore par l’agent | Done (partiel) — dump+encrypt+copie opérateur+preuve déchiffrement (ADR-034) ; restore SQL complet plus tard |
| V5-01 | P3 | IA : langage naturel → plan (jamais root libre) | Open |
| V7-01 | P1 | ADR-036 : intention `platforms-intent.json` + API/UI déclaration plateforme (sans SSH) | Done — 2026-09-29 |
| V7-02 | P1 | CHANGE `deploy-platform` : compose N services, sous-domaines `Host()`, aucun port public | Done — 2026-09-29 |
| V7-03 | P1 | Brancher Redis (`needs: redis`) + volumes nommés (uploads) | Done — dans `deploy-platform` ; pas les apps 1:1 |
| V7-04 | P2 | Routage par **chemin** (`PathPrefix`) pour un seul nom d’hôte | Done — 2026-09-29 |
| V7-05 | P2 | Clone Git **privé** (clé lecture seule) | Done — 2026-09-29 |
| V7-06 | P2 | Monorepo : `context` Docker (sous-dossier) + templates Next build-args / Spring Java 11 | **Done 2026-10-01** (+ env d’exécution, keepPath, Redis privé, clés Git par dépôt, préréglage ARC, restauration dump SQL + fichiers) |
| V7-07 | P3 | Redeploy d’un seul service d’une plateforme | Open — après V7-02 |
| PLAT-01 | P2 | Auth + sessions opérateur (ADR-031 / V9) | Open — spécifié seulement |
| PLAT-02 | P2 | Paramètres VPS / apps isolés par compte | Open — après PLAT-01 |
| PLAT-03 | P2 | Cockpit public derrière login (remplace cargo anonyme) | Open — après PLAT-01 ; ADR-030 amendé |
| BIZ-01 | P0 | Déploiement long fiable : rôle lancé détaché (`systemd-run`), état suivi par l'agent, plus de faux échec à la coupure SSH | **Done 2026-10-14** (ADR-040, lab : tâche 6 min 20) — ~~Open — défaut constaté 2026-10-08~~ |
| BIZ-02 | P0 | Parcours guidé testé de bout en bout sur un VPS vierge (Analyser → En ligne) | Open |
| BIZ-03 | P0 | Restauration complète depuis les sauvegardes chiffrées de l'agent + test de restauration périodique | **Done 2026-10-14 pour MariaDB** (ADR-041 : minuteur quotidien, vérification complète, exercice de restauration, restauration de plateforme ; lab 25/25 tables). Exercice **à la demande** (mensuel en maintenance, pas encore automatique). ~~Open — preuve de déchiffrement seule aujourd'hui (ADR-034)~~ |
| BIZ-04 | P0 | Rapport d'audit exportable (lecture seule) remis au client : risques, ports, sauvegardes, versions | **Done 2026-10-14** (`GET /api/v1/audit/report`, note A–E, HTML imprimable ; lab C 62 → A 91) — ~~Open~~ |
| BIZ-05 | P0 | Secrets d'exécution (mot de passe SMTP, etc.) posés depuis le cockpit, jamais dans l'intention ni à l'écran | Open — fait à la main pour ARC 2026-10-08 |
| BIZ-06 | P0 | Journal des actions par serveur (qui, quoi, quand, résultat), exportable | Open |
| BIZ-07 | P0 | Mandat d'intervention type + procédure de retrait des accès en fin de mission | **Partiel 2026-10-14** : modèle `commercial/mandat-intervention.md` (à faire relire par un juriste) ; procédure pas à pas de retrait des accès à écrire — hors code |
| BIZ-08 | P1 | Étude de cas ARC (avec accord) + page de présentation de l'offre | Open |
| BIZ-09 | P1 | Sauvegardes hors-site vers un stockage objet (le poste opérateur ne tient pas plusieurs clients) | Open — amende ADR-034 le moment venu |
| BIZ-10 | P2 | Installation de l'agent par un tiers sans le porteur (prérequis licence) | Open — étape 2 ADR-039 |
| BIZ-11 | P0 | Sauvegarde quotidienne des fichiers déposés par les utilisateurs (volumes `/app/data` des plateformes) + restauration | **Done 2026-10-14** (ADR-041 amendé) : archive `files-*.tar.gz.enc` des volumes `plat_*_uploads` et `*app_data_*` avec chaque dump, vérifiée, exercice par volume, restauration de plateforme à l'identique ; lab : 6/6 fichiers restaurés, propriétaires gardés. Restauration des fichiers d'une **app** 1:1 depuis la console : non faite (sauvegardée et testée seulement) — ~~Open — ADR-041 ne couvre que MariaDB ; limite écrite dans l'offre~~ |
| BIZ-14 | P1 | Réinstaller le rôle `backups` (script v2, fichiers des volumes) sur ARC `213.156.134.92` — CHANGE en production, accord de l'opérateur | Open — 2026-10-14 ; ARC a le script v1 (base seule), l'analyse le voit maintenant « à installer » |
| BIZ-12 | P1 | Exercice de restauration automatique (mensuel) + purge des copies locales anciennes (`state/backups/<serveur>/`) | Open — 2026-10-14 |
| BIZ-13 | P0 | **VPS ARC `213.156.134.92` : aucune sauvegarde de l'agent** (analyse 2026-10-07). Installer le rôle `backups` (ADR-041) puis exercice de restauration — CHANGE en production, accord explicite de l'opérateur requis | **Done 2026-10-14** (accord opérateur) : rôle installé, minuteur actif, sauvegarde `app_arc` 46 tables, exercice 46/46, site en ligne, aucune base jetable restante — ~~Open — constaté 2026-10-14~~ |

Les items Open ne s’exécutent **pas** sur ARC (`54.37.156.11`) ni DTSI (`154.0.30.233`).
