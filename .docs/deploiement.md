# Déploiement et versions — VPS Autopilot

> Statut : en vigueur depuis 1.0.0 (ADR-045). Enrichir, ne jamais effacer.  
> Dernière mise à jour : 2026-10-15

L'agent a **deux formes** :

| Forme | Où | Rôle | Accès |
|---|---|---|---|
| **Console opérateur** (« cerveau ») | Poste de l'opérateur | Analyse, plans, CHANGE confirmés, sauvegardes copiées, journal | `http://127.0.0.1:8088`, sans authentification, jamais exposée |
| **Instance cargo** (ADR-030) | VPS (lab), derrière Traefik | Page de preuve + `GET /api/v1/health` | Publique, lecture seule (`CargoModeFilter`, tout le reste en 403) |

## Publier une version

1. Mettre la version dans `pom.xml` (`<version>`), le changelog et `current_state.md`.
2. `.\scripts\release.ps1` (tests complets) ou `-SkipTests` (déconseillé). Produit `dist/vps-autopilot-<version>.zip` et `.zip.sha256` (`dist/` hors git).
3. Commit, tag `v<version>`, `git push origin main --tags`.
4. Facultatif : joindre le zip et son `.sha256` à une release GitHub (`gh` n'est pas installé sur le poste de développement : le faire depuis l'interface web).

Contenu du paquet : `vps-autopilot.jar`, `start.cmd` / `start.ps1` (Windows), `start.sh` (Linux, macOS), `LISEZMOI.txt`, `.env.example`, `state/agent-local.example.yml`.

## Installer ou mettre à jour la console (poste opérateur)

Tout est dans `LISEZMOI.txt`. En bref :

- Java 21+. Dézipper, lancer `start.cmd`. Au premier lancement `.env` est créé : y mettre `DEVOPS_SSH_KEY`.
- **À sauvegarder** : `state/` (dont `backup.key` : sans elle, les sauvegardes chiffrées sont illisibles), `.env`, la clé SSH de l'agent.
- **Mise à jour** : arrêter, remplacer `vps-autopilot.jar` et les scripts, garder `.env` et `state/`, relancer. Puis **Analyser** chaque serveur : un rôle proposé « à installer » signifie que son script serveur a changé ; lire le changelog avant de le réinstaller sur une production (accord du client).
- **Retour arrière** : remettre l'ancien jar. Les formats de `state/` restent lisibles par la version précédente tant qu'aucune note contraire n'est écrite dans le changelog.
- Contrôle : `http://127.0.0.1:8088/api/v1/health` → `"status":"UP"`, `"version":"<version>"`.

## Mettre à jour l'instance cargo (lab)

L'instance cargo est l'app `vps-autopilot` de l'intention du lab (Git `main` → image → Traefik). Depuis la console : serveur du lab, bande Applications, **Déployer** `vps-autopilot` (CHANGE `deploy-app`). Contrôle : `GET https://<domaine de l'app>/api/v1/health` renvoie `"role":"cargo"` et la nouvelle version ; `POST /api/v1/discover` y répond 403.

## Ce qui n'est pas couvert

- Pas d'installeur Windows (MSI) ni de service Windows : la console tourne tant que sa fenêtre est ouverte.
- Pas de mise à jour automatique ni de vérification de signature (seulement l'empreinte SHA-256).
- Un seul opérateur par installation ; pas d'authentification (V9, ADR-031).
