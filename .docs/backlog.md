# Backlog — VPS Autopilot

> Enrichir ; marquer `Done` sans effacer.  
> Dernière mise à jour : 2026-09-08

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
| V1-01 | P1 | Ansible + module Security (CHANGE + validation) | Open — rôle `security` livré (idempotent, tout hôte) ; pas encore Ansible |
| V1-02 | P1 | Module Docker + réseaux | Open — moteur Docker présent ; réseaux / compose plus tard |
| V2-01 | P1 | Traefik + HTTPS + MariaDB isolée | Open — Traefik + instance MariaDB exécutables ; grants par projet = V3 |
| V3-01 | P1 | Compte `deploy` + Git → image → Traefik | Open — `deploy` + `deploy-app` + Dockerfile cargo (cet agent) livrés ; grants SQL pas encore ; premier deploy réel en attente du Git public |
| V4-01 | P1 | Backups chiffrés + restore par l’agent | Open — hors-site : poste opérateur (option retenue) ; pas encore de rôle |
| V5-01 | P3 | IA : langage naturel → plan (jamais root libre) | Open |

Les items Open ne s’exécutent **pas** sur ARC (`54.37.156.11`) ni DTSI (`154.0.30.233`).
