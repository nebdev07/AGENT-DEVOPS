# Conventions — VPS Autopilot

> Mémoire permanente. Enrichir, ne jamais effacer.  
> Dernière mise à jour : 2026-09-08

## Gouvernance documentation

1. Source de vérité : `.docs/` (voir `documentation.md`).
2. Ne jamais supprimer une information ; archiver / amender / `obsolete`.
3. Après évolution : `current_state.md` + `changelog.md` + **Aide `#help`** si l’humain voit ou utilise le changement.
4. Distinguer **spécifié** vs **implémenté**.
5. Décision structurante → ADR dans `decisions.md`.
6. Pas de commit automatique par l’agent.

## Code

- Identifiants : anglais. Docs / console : français.
- Java 21, Spring Boot 3.3, package racine `com.nebdev.devopsagent`.
- Un module Maven V0 (ADR-008). Pas de split jars prématuré.
- L’executor n’envoie que des actions autorisées (`ActionGuard` + whitelist / plus tard Ansible).
- Script de discovery : stdin `bash -s`, aucune écriture distante. Exception CHANGE : `install-agent-pubkey` (ADR-016).
- Secrets : `.env` — jamais dans le code, les tests ou `.docs`.
- Host SSH : pas de valeur par défaut. Cibles ARC/DTSI refusées.

## Tests

- JUnit 5 + AssertJ.
- Parseur et gardes de risque : fixtures locales, pas Internet.
- Impératif avant de clôturer une phase discovery/risque : `DiscoveryParserTest`, `ActionGuardTest`, `ForbiddenTargetGuardTest`.

## Git

- Pas de secrets, pas de `state/*.json` réel, pas de `infrastructure/lab/keys/`.
- Un agent = un dépôt sous `AGENTS/` (ADR-012).
