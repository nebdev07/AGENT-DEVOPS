# Suite de session

- Reprise : `.docs/documentation.md` puis `current_state.md`.
- Console locale : `http://127.0.0.1:8088/` (profil platform).
- **Prod** : `https://autopilot.inovne.com` encore en profil **`platform`**.

## Plan A→G — avancement 2026-11-08

| Item | Statut |
|---|---|
| UI Angular fiches deploy + propose/approve | **Fait** |
| Deploy keys Git SSH (coffre org + runner scellé) | **Fait** |
| PLATFORM_SQL_READ / WRITE SaaS + UI | **Fait** |
| Restore plateforme + secrets runtime SaaS | **Fait** (staging one-shot + UI) |
| Vérif locale CI (mvn test + ng build) | **Fait** |
| Push `main` + CI GitHub | **Fait** (run en cours / à suivre) |
| Deploy `vps-autopilot` lab (méthode Autopilot) | **Fait** 2026-11-08 — `autopilot.inovne.com` platform 2.0.0 UP |
| Bascule `autopilot.inovne.com` → `saas` | **Autorisation explicite requise** |

## Ensuite

1. Suivre CI : https://github.com/nebdev07/AGENT-DEVOPS/actions
2. Staging saas puis bascule prod (accord porteur).

**Interdit** : CHANGE sur ARC / DTSI ; DNS sans accord.
Console locale : `http://127.0.0.1:8088/` (jar 2.0.0 démarré).
