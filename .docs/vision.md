# Vision — VPS Autopilot

> Dernière mise à jour : 2026-09-07

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
