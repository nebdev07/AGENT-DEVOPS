# Suite de session

- Reprise : `.docs/documentation.md` puis `current_state.md`.
- Console locale : `http://127.0.0.1:8088/` (profil platform, mono-poste).
- **Prod** : `https://autopilot.inovne.com` = profil **`saas`** (bascule 2026-11-08).

## Plan A→G — avancement 2026-11-08

| Item | Statut |
|---|---|
| UI Angular fiches deploy + propose/approve | **Fait** |
| Deploy keys Git SSH (coffre org + runner scellé) | **Fait** |
| PLATFORM_SQL_READ / WRITE SaaS + UI | **Fait** |
| Restore plateforme + secrets runtime SaaS | **Fait** |
| Vérif locale CI (mvn test + ng build) | **Fait** |
| Push `main` + CI GitHub | **Fait** |
| Deploy `vps-autopilot` lab (méthode Autopilot) | **Fait** |
| Bascule `autopilot.inovne.com` → `saas` | **Fait** 2026-11-08 |
| Profils `saas-local` / `saas-manual` | **Fait** |

## Ensuite

1. Se reconnecter sur prod (sessions platform non reprises) ; activer MFA personnel (Sécurité).
2. Reconfirmer empreintes SSH des serveurs importés.
3. Smoke deploy SaaS réel (lab) ; suivre CI.

**Interdit** : CHANGE sur ARC / DTSI ; DNS sans accord.
