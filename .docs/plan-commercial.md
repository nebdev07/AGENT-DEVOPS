# Plan — prérequis du premier client payant (A → D)

> Statut : en cours. Créé le 2026-10-13. Cocher sans effacer.  
> Source : ADR-039, `business.md`, backlog `BIZ-*`.  
> Banc d'essai réel : VPS lab `57.128.186.38` (jamais la prod ARC ni DTSI).

## Constat de départ (2026-10-13)

- Déploiement long : une seule session SSH de 45 min max ; une coupure réseau = faux échec (défaut 2026-10-08).
- **Sauvegardes inutilisables** : le rôle `backups` appelle `mysqldump`, absent de l'image `mariadb:11.4`. Erreur masquée (`2>/dev/null`, pas de `pipefail`) : le fichier chiffré contient le message d'erreur, et la « preuve de restauration » (en-tête gzip) le déclare valide. Vérifié en déchiffrant les deux copies locales du lab.
- Sauvegarde jouée **une seule fois** à l'installation : aucune sauvegarde périodique.
- VPS ARC `213.156.134.92` : aucune sauvegarde de l'agent.

## A — Opérations longues fiables (BIZ-01)

| # | Étape | Critère de fin |
|---|---|---|
| A1 | `RemoteJobRunner` : script déposé dans un dossier de tâche, lancé détaché (`systemd-run`, repli `setsid nohup`), suivi par courtes connexions SSH, coupures tolérées | Même contrat d'erreur que `runStdinScript` |
| A2 | Branché sur Déployer plateforme, Déployer app, Restaurer | Code existant de lecture des marqueurs inchangé |
| A3 | Tests unitaires (coupures, code retour, délai dépassé) | `mvn test` vert |
| A4 | Essai réel sur le lab : tâche de 6 min (au-delà de la coupure observée à ~5 min) | Résultat récupéré, code 0 |

## B — Rapport d'audit client (BIZ-04)

| # | Étape | Critère de fin |
|---|---|---|
| B1 | Analyse enrichie (lecture seule) : mises à jour en attente, redémarrage requis, mises à jour auto, sauvegardes (date, taille) | Parseur testé sur fixture |
| B2 | `AuditReport` (domaine pur) : constats classés par gravité, note A–E, recommandations | Tests des règles |
| B3 | Rapport HTML autonome imprimable en PDF + API + bouton console + Aide | Téléchargeable depuis la console |
| B4 | Audit réel du lab | Rapport généré et relu |

## C — Sauvegardes réelles et restauration (BIZ-03)

| # | Étape | Critère de fin |
|---|---|---|
| C1 | Rôle `backups` corrigé : `mariadb-dump`, `pipefail`, contrôle de fin de dump, phrase secrète hors ligne de commande, minuteur quotidien, rétention | Dump non vide sur le lab |
| C2 | Vérification réelle côté agent : déchiffrement complet, fin de dump présente, bases et tables listées | Un dump vide ou tronqué est refusé (test) |
| C3 | « Sauvegarder maintenant » (CHANGE) : dump, copie sur le poste (par serveur), vérification | Essai réel lab |
| C4 | Exercice de restauration : base extraite de la sauvegarde, importée dans une base temporaire sur le serveur, tables comptées puis base supprimée | Essai réel lab, comptes égaux |
| C5 | Restaurer la base d'une plateforme depuis une sauvegarde de l'agent (réutilise `platform-restore`) | Extraction testée |

## D — Prix et offre commerciale

| # | Étape | Critère de fin |
|---|---|---|
| D1 | Grille de prix (hypothèses à valider) dans `business.md` | Chiffrée, avec méthode |
| D2 | Fiche offre client (sans projet personnel, ADR-022) | `commercial/offre.md` |
| D3 | Modèle de devis + mandat d'intervention (BIZ-07) | `commercial/` |

## Clôture

`mvn test` vert, essais réels consignés dans `changelog.md`, `current_state.md`, `api.md`, ADR, Aide `#help`.

## Suivi (2026-10-14)

| Étape | État | Preuve |
|---|---|---|
| A1–A3 | Fait | `RemoteJobRunner`, `RemoteJobRunnerTest` (7 tests), ADR-040 |
| A4 | Fait | Lab : tâche de 6 min 20 récupérée (399 s), tâche en échec → code 3 |
| B1–B3 | Fait | `DiscoveryParserTest`, `AuditRulesTest`, `AuditHtmlRendererTest`, bouton console, Aide § 7c |
| B4 | Fait | Lab : C 62/100 (rapport relu) |
| C1 | Fait | Lab : dump 4,3 Mo (contre 144 octets avant), minuteur actif |
| C2 | Fait | `BackupArchiveTest` (vide, tronqué, mauvaise clé, erreur Docker refusés) ; vraie sauvegarde cassée du lab refusée (`BackupRestoreProofTest`) |
| C3 | Fait | Lab : Sauvegarder maintenant + Copier la dernière, SHA-256 identique |
| C4 | Fait | Lab : 25/25 tables, base jetable et dossier temporaire supprimés |
| C5 | Fait (partiel sur le lab) | Extraction testée ; refus d'une base absente vérifié sur le lab ; import complet non rejoué (aucune plateforme avec base sur le lab) |
| D1 | Fait | `business.md` § Grille de prix (hypothèses) |
| D2 | Fait | `commercial/offre.md` |
| D3 | Fait (relecture juridique à faire) | `commercial/devis-modele.md`, `commercial/mandat-intervention.md` |

Découvert en route : `BIZ-11` (fichiers des volumes non sauvegardés — **fait 2026-10-14**), `BIZ-12` (exercice automatique, purge locale), `BIZ-13` (ARC sans sauvegarde de l'agent — **fait 2026-10-14**), `BIZ-14` (ARC à passer au script v2 pour ses fichiers).
