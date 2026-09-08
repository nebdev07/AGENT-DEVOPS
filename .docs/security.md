# Sécurité — VPS Autopilot

> Dernière mise à jour : 2026-09-08

## Règle d’or

L’IA n’a jamais un accès root illimité. Elle propose. L’humain valide les CHANGE/DESTRUCTIVE. L’executor n’envoie que des actions autorisées.

## Niveaux

| Niveau | Exemples | V0 |
|---|---|---|
| READ | OS, CPU, RAM, disque, ports, services | autorisé, auto |
| CHANGE | installer la clé agent (`authorized_keys`) | **whitelist + confirmation** (ADR-016) |
| CHANGE | installer sécurité / Docker (rôles versionnés) | **whitelist + confirmation** (ADR-018) |
| CHANGE | installer Traefik (Docker, 80/443) | **whitelist + confirmation** (ADR-024) |
| CHANGE | installer MariaDB (conteneur interne) | **whitelist + confirmation** (ADR-026) |
| CHANGE | compte `deploy` (docker, pas sudo) | **whitelist + confirmation** (ADR-028) |
| CHANGE | déployer une app (Git → image → Traefik) | **whitelist + confirmation** (ADR-029) |
| Public | instance cargo de cet agent | **lecture santé seulement** (ADR-030) — pas de CHANGE exposé |
| DESTRUCTIVE | drop DB, rm, reset | **refusé** |

## SSH

- Clé privée pour la discovery. Bootstrap initial : mot de passe OVH **en mémoire le temps de la requête**, jamais persisté.
- `known_hosts` OpenSSH. `DEVOPS_SSH_TRUST_UNKNOWN=false` par défaut.
- Script de discovery passé sur **stdin** (`bash -s`) : rien n’est écrit sur le disque distant.
- Whitelist : le client n’exécute pas une chaîne arbitraire fournie par un LLM.

## Cibles interdites par défaut

Pas d’IP préremplie. Ne pas pointer l’agent vers la prod ARC ni le staging DTSI « pour tester ».

## Secrets

`.env`, clés PEM, JSON d’état réel : hors git.

## Pile voulue

Décocher un composant n’installe rien. Décocher sécurité / Docker / Traefik / backups, ou cocher Nginx hôte, **casse la politique** : l’humain doit confirmer. Redis peut rester off.

## Leçon ARC (août 2026)

Compromission (miner, webshell PHP, persistance). D’où : pas de PHP sur un webroot d’app, backups externes, isolation Docker, durcissement SSH **avant** les apps.

Baseline V1 exécutable (ADR-019), pas du bricolage SSH :

- UFW : deny incoming, 22/OpenSSH ; 80/443 **seulement** après le rôle Traefik (ADR-024)
- fail2ban jail `sshd`
- SSH : `PasswordAuthentication no`, root fermé, `AllowUsers` = compte actuel, `MaxAuthTries 3`
- unattended-upgrades
- Discovery : alerte si miner / PHP / Apache / Nginx hôte

fail2ban tout seul ne clôt **pas** la sécurité **de cet hôte**. Sur **ce** VPS, 80/443 seulement après SKIP sécurité (le rôle Traefik existe déjà dans le produit).
