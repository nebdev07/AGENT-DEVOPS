# Contexte — VPS Autopilot (devops-agent)

> Mémoire permanente. Enrichir, ne jamais effacer.  
> Dernière mise à jour : 2026-09-07

## Produit

Agent DevOps personnel : à partir d’un VPS Ubuntu vierge et de quelques paramètres, produire une **infrastructure reproductible** prête à accueillir des projets (Spring Boot, Node, Next.js, etc.).

Ce n’est pas un agent qui « exécute des commandes au hasard ».  
C’est un **provisioning contrôlé** : observation → plan → validation → exécution déterministe → vérification.

Nom de travail : **VPS Autopilot**.  
Dépôt : `d:\PROJECTS\ME\AGENTS\devops-agent` (premier agent du dossier `AGENTS` ; d’autres agents auront chacun leur projet).

## Pourquoi ce projet existe

- Ne plus configurer chaque serveur à la main.
- Ne plus mélanger tous les projets sur un OS nu (modèle Apache + Tomcat + PM2).
- Ne jamais répéter la perte / compromission d’un VPS sans backups 3-2-1 ni isolation.
- Construire **l’outil** qui construira tous les serveurs suivants, pas seulement le premier.

## Premier laboratoire

OVH **VPS-2** personnel (4 vCPU, 8 Go RAM, 75 Go NVMe, Ubuntu LTS, Europe).  
**Pas** le VPS de production ARC (`54.37.156.11` / `arc-nwanyo.org`).  
**Pas** le staging DTSI (`154.0.30.233`). *(Amendé 2026-10-05, ADR-037 : lecture seule permise pour l'inventaire avant migration ; aucune modification.)*

## Utilisateur

Toi. Plus tard : le même agent pour plusieurs VPS (OVH, Hetzner, autre) et plusieurs projets SaaS.

## Contrainte V0

Observer. Ne rien modifier sur le serveur.

## Paramètres (pas un OS unique)

Ubuntu LTS est le **premier lab** (VPS-2), pas le seul système. L’UI fournit nom, hôte, user, port, OS (`auto` ou famille Linux courante). Voir ADR-015.
