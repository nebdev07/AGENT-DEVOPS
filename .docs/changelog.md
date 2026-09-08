# Changelog — VPS Autopilot

> Ne jamais effacer une entrée.  
> Dernière mise à jour : 2026-09-08

## 2026-09-08

- ADR-030 : premier cargo = cet agent. `Dockerfile` racine, profil `cargo` (santé seulement). Cockpit inchangé en local. Aide §7. Cache `?v=app4`.
- Console : menu Docker « 2 restants » sans bouton — les cases sont l’intention, pas l’install. Détails Docker encore cochés (Compose, groupe) → READY + Installer. Detection Compose/`GROUPS=`. Carte Installations au-dessus du menu. Aide §5–§6. Cache `?v=app3`.
- Console : une pile enregistrée avant Compte deploy n’affichait aucun bouton Installer. Les nouvelles sections par défaut sont fusionnées une fois (`catalogSeen`). Installations liste READY en premier. Bandeau Applications si le compte deploy manque. Aide §5–§7. Cache `?v=app2`.
- ADR-029 : CHANGE `deploy-app` (Git HTTPS public, Dockerfile, compose généré, Traefik, pas de port publié). Aide §7. Lab : MariaDB SKIP sur cet hôte.
- ADR-028 : compte `deploy` exécutable (docker, pas sudo, clé agent, AllowUsers). Git → image plus tard. Aide à jour.
- V4 (spec) : dumps chiffrés stockables sur le **poste opérateur** ; restore par l’agent, pas à la main. Pas encore implémenté.
- Console : après une install (composant ou clé SSH), une fenêtre indique succès ou échec, avec la durée et la suite. Aide §6 à jour.
- Console : palette douce (menthe / bleu / sable / gris) et bandes de section (filet gauche + teinte + libellé) pour séparer état, logiciels, pile/install et rapport. Aide §7 à jour.
- Baseline sécurité SKIP sur VPS-2 (`PasswordAuthentication no`). V2-01 Traefik/HTTPS/MariaDB débloquée.
- ADR-023 : déploiements d’apps = module du même `devops-agent` (pas un second agent). Cerveau sur le poste opérateur ; executor déterministe ; apps = cargo.
- Pile « Que doit installer l’agent ? » repliable (fermée par défaut) pour alléger le cockpit.
- Rôle sécurité : drop-in SSH `00-vps-autopilot.conf` (OpenSSH, première valeur gagnante) pour que `PasswordAuthentication no` passe avant cloud-init `50-`. L’ancien `99-` est retiré. Le bouton Installer Sécurité reste tant que le mot de passe SSH est encore accepté.

## 2026-09-07

- Création du dépôt `devops-agent` (produit VPS Autopilot).
- ADR-001 → ADR-011 (produit dédié, risque, Java 21, V0 observation, lab ≠ prod tierce).
- Sprint 1 / V0 : discovery SSH read-only, parseur, état JSON, CLI, API, tests fixtures.
- ADR-012 : déplacement vers `PROJECTS/ME/AGENTS/devops-agent` (catalogue multi-agents, un projet par agent). Ancien chemin `PROJECTS/ME/devops-agent` abandonné.
- ADR-013 / ADR-014 : lab Docker (substitution VPS) + console web statique. Fallback fixture tant que Docker/WSL/VPS absents.
- Alignement doc BRVM/SmartFlow : `documentation.md`, `conventions.md`, `glossary.md`, `api.md`, `backlog.md`, `.cursor/rules.md`.
- Roadmap : V0 marqué **done** (code + console + docs) ; SSH réel reste LAB-01 / VPS-01.
- Console : formulaire hôte / user / port + enregistrement d’empreinte (`POST /api/v1/ssh/accept-host`) pour tester le VPS-2 réel.
- ADR-015 : paramètres VPS (nom, host, user, port, OS). Discovery multi-familles Linux ; Ubuntu n’est plus le seul OS.
- Empreinte SSH via SSHJ (plus `ssh-keyscan` Windows, incompatible OpenSSH 10). Analyse n’affiche plus la fixture après un échec réel.
- ADR-016 : premier CHANGE `install-agent-pubkey` (mot de passe OVH, confirmation, script idempotent). Console : bouton « Installer la clé ».
- Console : mini-tuto SSH (générer / copier / ajouter sur le VPS). Le catalogue OVH n’est plus présenté comme cible.
- Console : accueil produit animé ; tuto déplacé en `#guide` ; cockpit en `#cockpit` ; couleurs d’état (vert / gris / ambre / rouge).
- ADR-017 : pile voulue décochable (`/api/v1/stack`) avec avertissement si ça casse la politique.
- ADR-018 : après analyse, plan d’install + boutons CHANGE (`security`, `docker`).
- Scripts de rôles : fins de ligne normalisées en LF (CRLF Windows cassait `bash -s`).
- Console : plus d’analyse mockée (fixture / 203.0.113.x). Tableau vide tant que les paramètres ne sont pas analysés en SSH.
- Pile voulue après analyse seulement. Pas de bouton Installer si déjà présent. Page Aide `#help`.
- Discovery : fail2ban détecté (`###FAIL2BAN###` : le parseur acceptait `[A-Z]+` et ignorait le `2`). Sécurité = déjà là si fail2ban tourne ou si `fail2ban.service` est dans les services — plus seulement UFW active.
- Console : overlay loader pendant analyse, empreinte, clé SSH et install de composant (titre, description, étapes, durée). Boutons bloqués le temps du traitement.
- ADR-019 : baseline sécurité (UFW + fail2ban + SSH clés seulement). fail2ban seul ≠ SKIP. Discovery : `sudo -n` pour UFW/`sshd -T`, signaux miner/PHP/Apache/Nginx. V2 bloquée tant que READY sécurité.
- ADR-020 : pile par section + détails cochables. Explications (pourquoi / ce que ça fera) dans un menu fermé par défaut. Les rôles n’appliquent que les cases cochées.
- ADR-021 : Nginx hôte retiré de la pile à installer. Bloc « Non proposé » + explication. Toujours détecté à l’analyse s’il est déjà là.
- Détails déjà présents (fail2ban, Docker, …) non cochés par défaut ; mention « déjà installé ». Pas d’écart de politique pour ça.
- fail2ban restait coché : `defaultEnabled` du catalogue ignorait la présence. Le catalogue API pose maintenant `present` + `defaultEnabled=false` si déjà là.
- ADR-022 : textes publics sans noms de projets personnels (console, catalogue, alertes, plan).
