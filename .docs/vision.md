# Vision — VPS Autopilot

> Dernière mise à jour : 2026-09-28

## Objectif

Transformer un VPS Ubuntu neuf en infrastructure évolutive, **sans que l’IA ait un accès root illimité**.

```
TOI → Agent (plan) → validation humaine → Ansible/SSH déterministe → VPS
```

## Ce que le produit n’est pas

- Un chatbot qui SSH en root et improvise.
- Kubernetes au jour 1.
- Un substitut au jugement sur les opérations destructives.

## Principes

1. **Idempotence** — ré-appliquer un module ne casse rien ; l’état est consulté avant d’agir.
2. **Risque explicite** — READ auto ; CHANGE = plan + validation ; DESTRUCTIVE = confirmation de ce qui sera détruit.
3. **Déterminisme sous la ceinture** — l’IA propose ; Ansible (puis Docker) exécute.
4. **Portabilité** — pas prisonnier d’OVH ; state + playbooks + Git suffisent à migrer.
5. **Chaque dépense résout un problème réel** — pas de K8s, pas de VPS-4 prématuré.

## Cible long terme

Un outil « VPS Autopilot » : provider, OS, type de projet, base, domaine → provision / secure / docker / reverse-proxy / SSL / deploy / monitor.

**Cargo 1:1 (V3)** : une app, une image, un hôte.

**Plateforme cargo (V7, ADR-036)** : un produit à plusieurs services (site, backoffice, API, Redis, fichiers) sur le même VPS, toujours sans Apache/PM2/Tomcat hôte. Premier terrain = lab. Une infra du type ARC est le **spécimen**, pas la première cible d’exécution.

**Plateforme opérateurs (V9, ADR-031)** : plusieurs opérateurs, chacun une session et des paramètres qui ne valent **que pour lui**. Cockpit en ligne seulement après authentification. Jusqu’à V9 : mono-opérateur sur le poste local ; le lien public cargo n’est qu’une preuve de déploiement.
