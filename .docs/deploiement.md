# Déploiement et versions — VPS Autopilot

> Statut : en vigueur depuis 1.0.0 (ADR-045). Enrichir, ne jamais effacer.  
> Dernière mise à jour : 2026-11-07

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

## Instance publique Inovne (lab, 2026-11-07)

- **Marque / domaine** : Inovne — `inovne.com` (sous-domaines).
- **Hostname** : `autopilot.inovne.com` (app `vps-autopilot` sur VPS lab `57.128.186.38`).
- **Profil** : `platform` 2.0.0 (pas cargo). Intention locale : `state/apps-intent.json` + domaine de base `inovne.com` (`state/public-domain.json`). Bourse retirée de l’intention.
- **DNS (LWS)** : **A** `autopilot` → `57.128.186.38` **posé 2026-11-07** (vérifié 8.8.8.8 / 1.1.1.1). Script de secours : `scripts/lws-add-autopilot-dns.ps1`.
- **HTTPS** : Let’s Encrypt `CN=autopilot.inovne.com` actif. Contrôle : `GET https://autopilot.inovne.com/api/v1/health` → `"role":"platform"`, `"version":"2.0.0"` ; `/login.html` ; `POST /api/v1/discover` anonyme → 401.

## Mettre à jour l'instance cargo (lab)

L'instance cargo est l'app `vps-autopilot` de l'intention du lab (Git `main` → image → Traefik). Depuis la console : serveur du lab, bande Applications, **Déployer** `vps-autopilot` (CHANGE `deploy-app`). Contrôle : `GET https://<domaine de l'app>/api/v1/health` renvoie `"role":"cargo"` et la nouvelle version ; `POST /api/v1/discover` y répond 403.  
**Note 2026-11-07** : le lab public tourne en profil **`platform`** sous `autopilot.inovne.com` ; le cargo reste disponible si `SPRING_PROFILES_ACTIVE=cargo`. Le code 2.0.0 platform n’était pas encore poussé sur GitHub au go-live : image construite depuis le poste opérateur puis posée sur le VPS.

## Profil `platform` (ADR-046, V9 / 2.0.0)

Cockpit multi-comptes derrière login. **Prod :**

```text
SPRING_PROFILES_ACTIVE=platform
SERVER_ADDRESS=0.0.0.0
PLATFORM_FOUNDER_EMAIL=nebdev07@gmail.com
PLATFORM_DEFAULT_PASSWORD=VpsAutopilot!ChangeMe
PLATFORM_MAIL_DEV_CAPTURE=false
PLATFORM_COOKIE_SECURE=true
PLATFORM_PUBLIC_URL=https://autopilot.inovne.com
DEVOPS_SSH_KEY=/chemin/vers/cle
```

1. Démarrer → compte fondateur créé avec le mot de passe par défaut.
2. Ouvrir `/login.html` → e-mail + mot de passe défaut.
3. Page `/change-password.html` **obligatoire** avant le cockpit.
4. Admin : `/admin.html` (comptes, rôles, assignations).
5. Mot de passe oublié : `/forgot-password.html` → lien → `/reset-password.html` (nouveau MDP ou défaut usine).

Accueil `/` public (solutions). Santé : `"role":"platform"`, `"version":"2.0.0"`.  
Les clés SSH restent sur **cette** instance (exécuteur).

Lab / dev : `PLATFORM_MAIL_DEV_CAPTURE=true` (lien magique **et** lien « mot de passe oublié » affichés dans la réponse / UI, sans SMTP).  
Oubli local du MDP fondateur : `PLATFORM_FORCE_DEFAULT_PASSWORD=true` au prochain démarrage, ou bouton « Remettre VpsAutopilot!ChangeMe » sur la page reset.  
Profil défaut (mono-poste, `127.0.0.1`) et `cargo` restent disponibles.

## Ce qui n'est pas couvert

- Pas d'installeur Windows (MSI) ni de service Windows : la console tourne tant que sa fenêtre est ouverte.
- Pas de mise à jour automatique ni de vérification de signature (seulement l'empreinte SHA-256).
- ~~Un seul opérateur par installation ; pas d'authentification (V9, ADR-031).~~ Profil `platform` : multi-comptes + RBAC (ADR-046) ; profil défaut inchangé.
