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
| Vérif locale CI (mvn test + ng build) | **Fait** (suite complète verte) |
| Deploy réel lab SSH (BIZ-02) | Ouvert — lab à démarrer |
| CI GitHub sur remote | Workflow prêt — commit + push requis |
| Bascule `autopilot.inovne.com` → `saas` | **Autorisation explicite requise** |

## Ensuite

1. Commit + push pour déclencher `.github/workflows/ci.yml` (sur demande).
2. Démarrer lab SSH + rejouer DEPLOY_PLATFORM / restore réels (pas FakeEngine).
3. Staging saas puis bascule prod (accord porteur).

**Interdit** : CHANGE sur ARC / DTSI ; DNS sans accord.
