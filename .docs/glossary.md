# Glossaire — VPS Autopilot

> Dernière mise à jour : 2026-09-08

| Terme | Sens |
|---|---|
| **VPS Autopilot** | Nom produit de `devops-agent`. |
| **Discovery** | Lecture de l’état d’un serveur (OS, CPU, RAM, ports, logiciels). READ only. |
| **Fixture / lab-fixture** | Fichier Ubuntu vierge rejoué sans SSH (`ubuntu-vps2-blank.txt`). |
| **Lab Docker** | Conteneur Ubuntu + SSH (`infrastructure/lab`) qui imite un VPS-2. |
| **READ** | Observation. Exécution automatique. |
| **CHANGE** | Mutation (installer Docker, ouvrir un port). Plan + validation. |
| **DESTRUCTIVE** | Suppression / reset. Confirmation + liste de ce qui sera détruit. |
| **ActionGuard** | READ auto. CHANGE : whitelist + confirmation. DESTRUCTIVE refusé. |
| **State** | JSON local `state/<host>.json` (gitignoré). |
| **VPS-2** | Offre OVH cible (4 vCPU, 8 Go, 75 Go NVMe). Premier lab, souvent Ubuntu — pas le seul OS. |
| **VpsParameters** | Nom, hôte, port, user, OS — fournis par l’UI, pas câblés dans le code. |
| **OsFamily** | `auto` ou ubuntu / debian / rhel / rocky / alma / fedora / alpine / amazon. |
| **SKIP** | Plan d’install : déjà présent **sur l’hôte analysé**. Pas un jalon global du produit. |
| **READY** | Plan d’install : manquant sur cet hôte, rôle exécutable, bouton Installer. |
| **Roadmap Vn** | Capacités de l’agent (quels traitements existent), indépendant d’un VPS donné. |
| **Ansible** | Executor déterministe prévu à partir des CHANGE (V1). Pas encore dans le code. |
